package com.example.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.RfcommTransport
import com.example.protocol.CyberLinkProtocol
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real bidirectional Audio Call manager using Android AudioRecord + AudioTrack over encrypted Bluetooth RFCOMM.
 *
 * Transport Engineering Check:
 * - BLE GATT ATT Write/Notify carries ~5–20 kbps effective application throughput with 15–45 ms connection intervals;
 *   8 kHz 16-bit PCM requires 128 kbps (+ AES-GCM/header overhead -> ~140 kbps), so BLE GATT alone is insufficient.
 * - Bluetooth Classic RFCOMM provides ~700 kbps–2.1 Mbps stream bandwidth and sustains real-time 128 kbps PCM.
 * - Bluetooth LE Audio (LC3/ISO) requires API 33+ and controller ISO support, which is not uniformly available across devices.
 * Honestly marks audio calls as NICHT UNTERSTÜTZT if RFCOMM is unavailable.
 */
class AudioCallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bluetoothManager: BluetoothManager,
) {
    companion object {
        private const val SAMPLE_RATE = 8000
        private const val FRAME_BYTES = 640 // 40ms @ 8kHz 16-bit mono (~128 kbps raw PCM)
        const val TRANSPORT_CAPABILITY_SUMMARY_DE =
            "Audio-Analyse: BLE GATT (~5–20 kbps) unzureichend für 128 kbps Echtzeit-PCM. " +
                "Bluetooth Classic RFCOMM (~700–2100 kbps) aktiv genutzt; BLE Audio (LC3) erfordert API 33+ ISO-Hardware."
    }

    enum class CallStatus(val labelDe: String) {
        IDLE("BEREIT"),
        RINGING_OUTGOING("RUFE AN…"),
        RINGING_INCOMING("EINGEHENDER ANRUF"),
        ACTIVE("AKTIV"),
        RECONNECTING("WIEDERVERBINDUNG…"),
        ENDED("BEENDET"),
        UNSUPPORTED("NICHT UNTERSTÜTZT"),
    }

    data class AudioCallState(
        val callId: String? = null,
        val peerNodeId: String? = null,
        val status: CallStatus = CallStatus.IDLE,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val packetsSent: Long = 0L,
        val packetsReceived: Long = 0L,
        val failedPackets: Long = 0L,
        val bitrateKbps: Float = 0f,
        val lossRatePct: Float = 0f,
        val qualityLabel: String = "—",
        val statusDetail: String = "Kein aktiver Audioanruf",
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(AudioCallState())
    val state: StateFlow<AudioCallState> = _state.asStateFlow()

    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var callActiveStartMs: Long = 0L
    private var totalAudioBytesTransferred: Long = 0L

    init {
        scope.launch {
            bluetoothManager.incomingMediaFrames.collect { (peerNodeId, frameType, payload) ->
                if (frameType == RfcommTransport.FRAME_AUDIO_PCM &&
                    _state.value.status == CallStatus.ACTIVE &&
                    _state.value.peerNodeId == peerNodeId
                ) {
                    playIncomingPcm(payload)
                }
            }
        }
    }

    suspend fun startOutgoingCall(peerNodeId: String, hasMicPermission: Boolean): Result<Unit> {
        if (!hasMicPermission) {
            _state.value = AudioCallState(
                peerNodeId = peerNodeId,
                status = CallStatus.UNSUPPORTED,
                qualityLabel = "NICHT VERFÜGBAR",
                statusDetail = "Mikrofonberechtigung (RECORD_AUDIO) fehlt",
            )
            return Result.failure(SecurityException("Mikrofonberechtigung fehlt"))
        }
        val transport = bluetoothManager.getEffectiveTransportForPeer(peerNodeId)
        if (transport != ActiveTransport.CLASSIC_RFCOMM && transport != ActiveTransport.DUAL_BLE_RFCOMM) {
            val msg = "Audioanruf benötigt aktiven RFCOMM-Transport (BLE GATT allein hat zu geringe Bandbreite; aktuell: ${transport.labelDe})"
            _state.value = AudioCallState(
                peerNodeId = peerNodeId,
                status = CallStatus.UNSUPPORTED,
                qualityLabel = "NICHT UNTERSTÜTZT",
                statusDetail = msg,
            )
            return Result.failure(IllegalStateException(msg))
        }

        val callId = UUID.randomUUID().toString()
        _state.value = AudioCallState(
            callId = callId,
            peerNodeId = peerNodeId,
            status = CallStatus.RINGING_OUTGOING,
            qualityLabel = "VERBINDE",
            statusDetail = "Anrufeinladung über verschlüsselten RFCOMM-Kanal gesendet…",
        )
        return bluetoothManager.sendEnvelope(
            peerNodeId = peerNodeId,
            envelope = CyberLinkProtocol.ProtocolEnvelope.CallSignal(
                callId = callId,
                callType = "AUDIO",
                action = "INVITE",
                senderNodeId = bluetoothManager.encryptionManager.nodeId,
                transportHint = transport.name,
                timestampMs = System.currentTimeMillis(),
            ),
            preferRfcomm = true,
        )
    }

    suspend fun onIncomingCallSignal(peerNodeId: String, signal: CyberLinkProtocol.ProtocolEnvelope.CallSignal) {
        if (signal.callType != "AUDIO") return
        when (signal.action) {
            "INVITE" -> {
                _state.value = AudioCallState(
                    callId = signal.callId,
                    peerNodeId = peerNodeId,
                    status = CallStatus.RINGING_INCOMING,
                    qualityLabel = "EINGEHEND",
                    statusDetail = "Eingehender Audioanruf von $peerNodeId",
                )
            }
            "ACCEPT" -> {
                if (_state.value.callId == signal.callId) {
                    startAudioStreams(peerNodeId, signal.callId)
                }
            }
            "REJECT", "HANGUP" -> {
                stopAudioStreams("Anruf durch $peerNodeId beendet (${signal.action})")
            }
        }
    }

    suspend fun acceptIncomingCall(hasMicPermission: Boolean) {
        val current = _state.value
        val peerId = current.peerNodeId ?: return
        val callId = current.callId ?: return
        if (!hasMicPermission) {
            rejectOrHangup()
            return
        }
        bluetoothManager.sendEnvelope(
            peerNodeId = peerId,
            envelope = CyberLinkProtocol.ProtocolEnvelope.CallSignal(
                callId = callId,
                callType = "AUDIO",
                action = "ACCEPT",
                senderNodeId = bluetoothManager.encryptionManager.nodeId,
                transportHint = "RFCOMM",
                timestampMs = System.currentTimeMillis(),
            ),
            preferRfcomm = true,
        )
        startAudioStreams(peerId, callId)
    }

    suspend fun rejectOrHangup() {
        val current = _state.value
        val peerId = current.peerNodeId
        val callId = current.callId
        if (peerId != null && callId != null) {
            val action = if (current.status == CallStatus.RINGING_INCOMING) "REJECT" else "HANGUP"
            bluetoothManager.sendEnvelope(
                peerNodeId = peerId,
                envelope = CyberLinkProtocol.ProtocolEnvelope.CallSignal(
                    callId = callId,
                    callType = "AUDIO",
                    action = action,
                    senderNodeId = bluetoothManager.encryptionManager.nodeId,
                    transportHint = "RFCOMM",
                    timestampMs = System.currentTimeMillis(),
                ),
                preferRfcomm = true,
            )
        }
        stopAudioStreams("Anruf beendet")
    }

    fun toggleMute() {
        _state.value = _state.value.copy(isMuted = !_state.value.isMuted)
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val next = !_state.value.isSpeakerOn
        audioManager.isSpeakerphoneOn = next
        _state.value = _state.value.copy(isSpeakerOn = next)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioStreams(peerNodeId: String, callId: String) {
        val session = bluetoothManager.getSession(peerNodeId)
        val addr = session?.let {
            bluetoothManager.rfcommTransport.getBoundAddress(peerNodeId) ?: it.effectiveRfcommAddress()
        }
        if (session == null || addr == null || !bluetoothManager.rfcommTransport.isBidirectionallyReady(session.classicAddress, session.deviceAddress, peerNodeId)) {
            _state.value = AudioCallState(
                callId = callId,
                peerNodeId = peerNodeId,
                status = CallStatus.UNSUPPORTED,
                qualityLabel = "NICHT VERFÜGBAR",
                statusDetail = "RFCOMM-Audiokanal nicht bidirektional verifiziert",
            )
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_BYTES * 2)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
            )
        }.getOrNull()

        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_BYTES * 2)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minOut)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED || track == null) {
            runCatching { recorder?.release() }
            runCatching { track?.release() }
            _state.value = AudioCallState(
                callId = callId,
                peerNodeId = peerNodeId,
                status = CallStatus.UNSUPPORTED,
                qualityLabel = "HARDWARE-FEHLER",
                statusDetail = "AudioRecord/AudioTrack Initialisierung fehlgeschlagen",
            )
            return
        }

        audioRecord = recorder
        audioTrack = track
        recorder.startRecording()
        track.play()
        callActiveStartMs = System.currentTimeMillis()
        totalAudioBytesTransferred = 0L

        _state.value = AudioCallState(
            callId = callId,
            peerNodeId = peerNodeId,
            status = CallStatus.ACTIVE,
            isMuted = false,
            isSpeakerOn = _state.value.isSpeakerOn,
            qualityLabel = "STABIL",
            statusDetail = "Echte RFCOMM-Sprachübertragung aktiv (8 kHz 16-bit PCM)",
        )

        recordJob?.cancel()
        recordJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(FRAME_BYTES)
            while (isActive && (_state.value.status == CallStatus.ACTIVE || _state.value.status == CallStatus.RECONNECTING)) {
                if (_state.value.status == CallStatus.RECONNECTING) {
                    kotlinx.coroutines.delay(150)
                    continue
                }
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0 && !_state.value.isMuted) {
                    val frame = if (read == buf.size) buf else buf.copyOf(read)
                    val sendAddr = bluetoothManager.rfcommTransport.getBoundAddress(peerNodeId)
                        ?: session.effectiveRfcommAddress()
                        ?: addr
                    val res = bluetoothManager.rfcommTransport.sendFrame(
                        address = sendAddr,
                        frameType = RfcommTransport.FRAME_AUDIO_PCM,
                        payload = frame,
                        cipher = session.cipherSession,
                    )
                    if (res.isSuccess) {
                        totalAudioBytesTransferred += frame.size
                        val nextSent = _state.value.packetsSent + 1
                        updateQualityMetrics(nextSent, _state.value.packetsReceived, _state.value.failedPackets)
                    } else {
                        val failed = _state.value.failedPackets + 1
                        _state.value = _state.value.copy(
                            status = CallStatus.RECONNECTING,
                            failedPackets = failed,
                            qualityLabel = "GETRENNT",
                            statusDetail = "RFCOMM-Sprachkanal unterbrochen — versuche Wiederverbindung…",
                        )
                        val reconnected = bluetoothManager.upgradeToRfcomm(peerNodeId).isSuccess
                        if (reconnected) {
                            _state.value = _state.value.copy(
                                status = CallStatus.ACTIVE,
                                qualityLabel = "WIEDERHERGESTELLT",
                                statusDetail = "RFCOMM-Sprachkanal wiederhergestellt",
                            )
                        } else {
                            stopAudioStreams("Audio-Verbindung unterbrochen (Wiederverbindung fehlgeschlagen)")
                            break
                        }
                    }
                }
            }
        }
    }

    private fun updateQualityMetrics(sent: Long, received: Long, failed: Long) {
        val elapsedSec = ((System.currentTimeMillis() - callActiveStartMs) / 1000f).coerceAtLeast(1f)
        val kbps = (totalAudioBytesTransferred * 8f / 1000f) / elapsedSec
        val totalAttempts = (sent + failed).coerceAtLeast(1L)
        val lossPct = (failed.toFloat() / totalAttempts.toFloat()) * 100f
        val label = when {
            lossPct > 5f -> "SCHWACH"
            kbps >= 100f && lossPct <= 1f -> "EXZELLENT"
            else -> "STABIL"
        }
        _state.value = _state.value.copy(
            packetsSent = sent,
            packetsReceived = received,
            failedPackets = failed,
            bitrateKbps = kbps,
            lossRatePct = lossPct,
            qualityLabel = label,
        )
    }

    private fun playIncomingPcm(pcm: ByteArray) {
        audioTrack?.write(pcm, 0, pcm.size)
        totalAudioBytesTransferred += pcm.size
        val nextRecv = _state.value.packetsReceived + 1
        updateQualityMetrics(_state.value.packetsSent, nextRecv, _state.value.failedPackets)
    }

    fun stopAudioStreams(detail: String) {
        recordJob?.cancel()
        recordJob = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
        _state.value = AudioCallState(
            status = CallStatus.ENDED,
            qualityLabel = "BEENDET",
            statusDetail = detail,
        )
    }
}
