package com.example.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.RfcommTransport
import com.example.protocol.CyberLinkProtocol
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real two-way Video Call manager.
 * Uses CameraX ImageAnalysis frames encoded to adaptive JPEG over encrypted Bluetooth RFCOMM.
 * NEVER fabricates video frames or fake FPS; honestly reports NICHT UNTERSTÜTZT / VERBINDUNG ZU LANGSAM
 * when RFCOMM is unavailable or frame throughput drops.
 */
class VideoCallManager(
    private val scope: CoroutineScope,
    private val bluetoothManager: BluetoothManager,
) {
    enum class VideoCallStatus(val labelDe: String) {
        IDLE("BEREIT"),
        RINGING_OUTGOING("VIDEO-ANFRAGE LÄUFT…"),
        RINGING_INCOMING("EINGEHENDER VIDEOANRUF"),
        ACTIVE("AKTIV"),
        TOO_SLOW("VERBINDUNG ZU LANGSAM"),
        UNSUPPORTED("NICHT UNTERSTÜTZT"),
        ENDED("BEENDET"),
    }

    data class VideoCallState(
        val callId: String? = null,
        val peerNodeId: String? = null,
        val status: VideoCallStatus = VideoCallStatus.IDLE,
        val cameraEnabled: Boolean = true,
        val useFrontCamera: Boolean = true,
        val adaptiveQuality: Int = 35,
        val measuredFps: Float = 0f,
        val measuredKbps: Float = 0f,
        val framesSent: Long = 0L,
        val framesReceived: Long = 0L,
        val remoteFrame: Bitmap? = null,
        val statusDetail: String = "Videoanruf erfordert aktiven RFCOMM-Transport",
    )

    private val _state = MutableStateFlow(VideoCallState())
    val state: StateFlow<VideoCallState> = _state.asStateFlow()

    private var lastFrameSentMs = 0L
    private var framesInWindow = 0
    private var bytesInWindow = 0L
    private var windowStartMs = System.currentTimeMillis()
    private var slowFrameStreak = 0

    init {
        scope.launch {
            bluetoothManager.incomingMediaFrames.collect { (peerNodeId, frameType, payload) ->
                if (frameType == RfcommTransport.FRAME_VIDEO_JPEG &&
                    _state.value.peerNodeId == peerNodeId &&
                    _state.value.status == VideoCallStatus.ACTIVE
                ) {
                    val bmp = BitmapFactory.decodeByteArray(payload, 0, payload.size)
                    if (bmp != null) {
                        _state.value = _state.value.copy(
                            remoteFrame = bmp,
                            framesReceived = _state.value.framesReceived + 1,
                        )
                    }
                }
            }
        }
    }

    suspend fun startVideoCall(peerNodeId: String, hasCameraPermission: Boolean): Result<Unit> {
        if (!hasCameraPermission) {
            val msg = "Kameraberechtigung (CAMERA) fehlt"
            _state.value = VideoCallState(peerNodeId = peerNodeId, status = VideoCallStatus.UNSUPPORTED, statusDetail = msg)
            return Result.failure(SecurityException(msg))
        }
        val transport = bluetoothManager.getEffectiveTransportForPeer(peerNodeId)
        if (transport != ActiveTransport.CLASSIC_RFCOMM && transport != ActiveTransport.DUAL_BLE_RFCOMM) {
            val msg = "NICHT UNTERSTÜTZT über ${transport.labelDe}: Video erfordert aktiven Bluetooth Classic RFCOMM-Kanal."
            _state.value = VideoCallState(peerNodeId = peerNodeId, status = VideoCallStatus.UNSUPPORTED, statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }

        val callId = UUID.randomUUID().toString()
        windowStartMs = System.currentTimeMillis()
        framesInWindow = 0
        bytesInWindow = 0L
        slowFrameStreak = 0
        _state.value = VideoCallState(
            callId = callId,
            peerNodeId = peerNodeId,
            status = VideoCallStatus.RINGING_OUTGOING,
            statusDetail = "Video-Einladung über verschlüsselten RFCOMM-Kanal gesendet…",
        )
        return bluetoothManager.sendEnvelope(
            peerNodeId,
            CyberLinkProtocol.ProtocolEnvelope.CallSignal(
                callId = callId,
                callType = "VIDEO",
                action = "INVITE",
                senderNodeId = bluetoothManager.encryptionManager.nodeId,
                transportHint = transport.name,
                timestampMs = System.currentTimeMillis(),
            ),
            preferRfcomm = true,
        )
    }

    suspend fun onIncomingCallSignal(peerNodeId: String, signal: CyberLinkProtocol.ProtocolEnvelope.CallSignal) {
        if (signal.callType != "VIDEO") return
        when (signal.action) {
            "INVITE" -> {
                _state.value = VideoCallState(
                    callId = signal.callId,
                    peerNodeId = peerNodeId,
                    status = VideoCallStatus.RINGING_INCOMING,
                    statusDetail = "Eingehender Videoanruf von $peerNodeId",
                )
            }
            "ACCEPT" -> {
                if (_state.value.callId == signal.callId) {
                    windowStartMs = System.currentTimeMillis()
                    framesInWindow = 0
                    bytesInWindow = 0L
                    slowFrameStreak = 0
                    _state.value = _state.value.copy(
                        status = VideoCallStatus.ACTIVE,
                        statusDetail = "CameraX Video-Stream über RFCOMM aktiv",
                    )
                }
            }
            "REJECT", "HANGUP" -> {
                _state.value = VideoCallState(
                    status = VideoCallStatus.ENDED,
                    statusDetail = "Videoanruf durch $peerNodeId beendet (${signal.action})",
                )
            }
        }
    }

    suspend fun acceptIncomingVideoCall(hasCameraPermission: Boolean): Result<Unit> {
        val current = _state.value
        val peerId = current.peerNodeId ?: return Result.failure(IllegalStateException("Kein eingehender Videoanruf"))
        val callId = current.callId ?: return Result.failure(IllegalStateException("Keine Call-ID"))
        if (!hasCameraPermission) {
            endCall()
            return Result.failure(SecurityException("Kameraberechtigung fehlt"))
        }
        val transport = bluetoothManager.getEffectiveTransportForPeer(peerId)
        if (transport != ActiveTransport.CLASSIC_RFCOMM && transport != ActiveTransport.DUAL_BLE_RFCOMM) {
            val msg = "NICHT UNTERSTÜTZT: RFCOMM nicht verbunden"
            _state.value = current.copy(status = VideoCallStatus.UNSUPPORTED, statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }
        windowStartMs = System.currentTimeMillis()
        framesInWindow = 0
        bytesInWindow = 0L
        slowFrameStreak = 0
        _state.value = current.copy(
            status = VideoCallStatus.ACTIVE,
            statusDetail = "CameraX Video-Stream über RFCOMM aktiv",
        )
        return bluetoothManager.sendEnvelope(
            peerId,
            CyberLinkProtocol.ProtocolEnvelope.CallSignal(
                callId = callId,
                callType = "VIDEO",
                action = "ACCEPT",
                senderNodeId = bluetoothManager.encryptionManager.nodeId,
                transportHint = "RFCOMM",
                timestampMs = System.currentTimeMillis(),
            ),
            preferRfcomm = true,
        )
    }

    fun processCameraFrame(image: ImageProxy) {
        val current = _state.value
        if (current.status != VideoCallStatus.ACTIVE || !current.cameraEnabled) return
        val peerId = current.peerNodeId ?: return
        val session = bluetoothManager.getSession(peerId) ?: return
        val addr = session.deviceAddress ?: return

        val now = System.currentTimeMillis()
        // Adaptive rate cap (~6 fps max over RFCOMM to avoid bufferbloat)
        if (now - lastFrameSentMs < 160L) return

        val jpegBytes = encodeYuvToJpeg(image, current.adaptiveQuality) ?: return
        val sendStart = System.currentTimeMillis()
        val res = bluetoothManager.rfcommTransport.sendFrame(
            address = addr,
            frameType = RfcommTransport.FRAME_VIDEO_JPEG,
            payload = jpegBytes,
            cipher = session.cipherSession,
        )
        val sendDuration = System.currentTimeMillis() - sendStart

        if (res.isFailure) {
            _state.value = current.copy(
                status = VideoCallStatus.TOO_SLOW,
                measuredFps = 0f,
                measuredKbps = 0f,
                statusDetail = "VERBINDUNG ZU LANGSAM / RFCOMM unterbrochen",
            )
            return
        }

        if (sendDuration > 800L) {
            slowFrameStreak++
            if (slowFrameStreak >= 4) {
                _state.value = current.copy(
                    status = VideoCallStatus.TOO_SLOW,
                    measuredFps = 0f,
                    statusDetail = "VERBINDUNG ZU LANGSAM (Latenz ${sendDuration} ms pro Frame)",
                )
                return
            }
        } else {
            slowFrameStreak = 0
        }

        lastFrameSentMs = now
        framesInWindow++
        bytesInWindow += jpegBytes.size
        val elapsed = now - windowStartMs
        val (fps, kbps) = if (elapsed >= 1000L) {
            val calcFps = (framesInWindow * 1000f) / elapsed.toFloat()
            val calcKbps = (bytesInWindow * 8f / 1000f) / (elapsed / 1000f)
            framesInWindow = 0
            bytesInWindow = 0L
            windowStartMs = now
            calcFps to calcKbps
        } else {
            current.measuredFps to current.measuredKbps
        }
        val nextQuality = when {
            sendDuration > 250L -> (current.adaptiveQuality - 5).coerceAtLeast(15)
            sendDuration < 60L -> (current.adaptiveQuality + 5).coerceAtMost(55)
            else -> current.adaptiveQuality
        }
        _state.value = current.copy(
            measuredFps = fps,
            measuredKbps = kbps,
            framesSent = current.framesSent + 1,
            adaptiveQuality = nextQuality,
        )
    }

    fun toggleCamera() {
        _state.value = _state.value.copy(cameraEnabled = !_state.value.cameraEnabled)
    }

    fun switchCamera() {
        _state.value = _state.value.copy(useFrontCamera = !_state.value.useFrontCamera)
    }

    suspend fun endCall() {
        val current = _state.value
        val action = if (current.status == VideoCallStatus.RINGING_INCOMING) "REJECT" else "HANGUP"
        current.peerNodeId?.let { peerId ->
            current.callId?.let { callId ->
                bluetoothManager.sendEnvelope(
                    peerId,
                    CyberLinkProtocol.ProtocolEnvelope.CallSignal(
                        callId = callId,
                        callType = "VIDEO",
                        action = action,
                        senderNodeId = bluetoothManager.encryptionManager.nodeId,
                        transportHint = "RFCOMM",
                        timestampMs = System.currentTimeMillis(),
                    ),
                    preferRfcomm = true,
                )
            }
        }
        _state.value = VideoCallState(status = VideoCallStatus.ENDED, statusDetail = "Videoanruf beendet")
    }

    private fun encodeYuvToJpeg(image: ImageProxy, quality: Int): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return runCatching {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            out.toByteArray()
        }.getOrNull()
    }
}
