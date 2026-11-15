package com.example

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.battery.BatteryMode
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BleAdvertisementCodec
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.CyberLinkUuids
import com.example.bluetooth.DiagnosticComponentState
import com.example.bluetooth.GattCapabilityValidator
import com.example.bluetooth.GattOperationQueue
import com.example.bluetooth.PeerManager
import com.example.bluetooth.PeerTransportSession
import com.example.bluetooth.TransportState
import com.example.bluetooth.describeAdvertiseError
import com.example.bluetooth.describeScanError
import com.example.crypto.EncryptionManager
import com.example.crypto.LocalStorageCipher
import com.example.data.SettingsStore
import com.example.data.db.CyberLinkDatabase
import com.example.data.db.MessageEntity
import com.example.media.AudioCallManager
import com.example.media.BluetoothFileTransferManager
import com.example.media.VideoCallManager
import com.example.protocol.CyberLinkProtocol
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrPairingAndCommsTest {

    private lateinit var context: Context
    private lateinit var db: CyberLinkDatabase
    private lateinit var storageCipher: LocalStorageCipher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CyberLinkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageCipher = LocalStorageCipher(LocalStorageCipher.generateRandomKeyBytes())
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "cyber_media").deleteRecursively()
    }

    @Test
    fun `1 QR generator creates signed expiring payload without private keys and supports renewal`() {
        val enc = EncryptionManager()
        val now = 1_700_000_000_000L
        val raw1 = PeerManager.buildLocalQrPayloadJson(
            encryptionManager = enc,
            displayName = "AlphaPhone",
            btAddress = "aa:bb:cc:dd:ee:11",
            ttlMs = 300_000L,
            nowMs = now,
        )
        assertFalse(raw1.contains("PRIVATE", ignoreCase = true))
        val parsed1 = PeerManager.parseQrPayload(raw1, nowMs = now + 5_000L).getOrThrow()
        assertEquals(PeerManager.QR_PROTOCOL_ID, parsed1.protocol)
        assertEquals(PeerManager.QR_PROTOCOL_VERSION, parsed1.v)
        assertEquals(enc.nodeId, parsed1.nodeId)
        assertEquals("AlphaPhone", parsed1.name)
        assertEquals("AA:BB:CC:DD:EE:11", parsed1.btAddress)
        assertTrue(parsed1.tempDeviceId.startsWith("TMP-"))
        assertTrue(parsed1.pairingToken.length >= 16)
        assertTrue(parsed1.signatureB64.isNotBlank())

        // Renew QR creates fresh tempDeviceId and fresh pairingToken
        val raw2 = PeerManager.buildLocalQrPayloadJson(enc, "AlphaPhone", btAddress = null, nowMs = now + 1000L)
        val parsed2 = PeerManager.parseQrPayload(raw2, nowMs = now + 2000L).getOrThrow()
        assertNotEquals(parsed1.tempDeviceId, parsed2.tempDeviceId)
        assertNotEquals(parsed1.pairingToken, parsed2.pairingToken)
    }

    @Test
    fun `2 QR parser hides masked Android MAC and rejects expired tampered or consumed tokens`() = runBlocking {
        assertNull(PeerManager.sanitizeBluetoothAddress("02:00:00:00:00:00"))
        assertNull(PeerManager.sanitizeBluetoothAddress("00:00:00:00:00:00"))
        assertNull(PeerManager.sanitizeBluetoothAddress("invalid-mac"))
        assertEquals("12:34:56:78:9A:BC", PeerManager.sanitizeBluetoothAddress("12:34:56:78:9a:bc"))

        val enc = EncryptionManager()
        val now = 1_700_000_000_000L
        val maskedPayload = PeerManager.buildLocalQrPayload(
            encryptionManager = enc,
            displayName = "MaskedDevice",
            btAddress = "02:00:00:00:00:00",
            ttlMs = 60_000L,
            nowMs = now,
        )
        assertNull(maskedPayload.btAddress)
        val jsonStr = PeerManager.encodeQrPayload(maskedPayload)

        // Expired QR rejected
        val expiredRes = PeerManager.parseQrPayload(jsonStr, nowMs = now + 120_000L)
        assertTrue("Expired QR must fail", expiredRes.isFailure)

        // Tampered name invalidates ECDSA signature
        val tamperedJson = jsonStr.replace("MaskedDevice", "EvilSpoof")
        val tamperedRes = PeerManager.parseQrPayload(tamperedJson, nowMs = now + 5_000L)
        assertTrue("Tampered QR signature must fail", tamperedRes.isFailure)

        // Single-use token invalidation via SettingsStore
        val store = SettingsStore(context)
        assertFalse(store.isPairingTokenConsumed(maskedPayload.pairingToken, now))
        store.consumePairingToken(maskedPayload.pairingToken, maskedPayload.expiresAtMs, now)
        assertTrue(store.isPairingTokenConsumed(maskedPayload.pairingToken, now))
        val consumedRes = PeerManager.parseQrPayload(
            jsonStr,
            nowMs = now + 1_000L,
            isTokenConsumed = { tok -> tok == maskedPayload.pairingToken },
        )
        assertTrue("Consumed single-use token must fail", consumedRes.isFailure)
    }

    @Test
    fun `3 two-device mutual encrypted handshake and pairing confirmation round-trip`() = runBlocking {
        val encA = EncryptionManager()
        val encB = EncryptionManager()
        val qrFromB = PeerManager.buildLocalQrPayload(encB, "ResponderB")
        val parsedB = PeerManager.parseQrPayload(PeerManager.encodeQrPayload(qrFromB)).getOrThrow()

        val sessionA = PeerTransportSession(parsedB.nodeId, "AA:BB:CC:DD:EE:02", parsedB.name)
        val sessionB = PeerTransportSession(encA.nodeId, "AA:BB:CC:DD:EE:01", "InitiatorA")
        listOf(sessionA, sessionB).forEach { s ->
            s.rxSpec = GattCapabilityValidator.CharacteristicSpec(CyberLinkUuids.RX_CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, false)
            s.txSpec = GattCapabilityValidator.CharacteristicSpec(CyberLinkUuids.TX_CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, true)
            s.notificationLocallyEnabled = true
            s.cccdWriteSucceeded = true
            s.cccdStatusCode = 0
            s.updateNegotiatedMtu(247)
        }

        val handshakeRes = sessionA.runClientChallengeHandshake(
            localEncryption = encA,
            peerExpectedPublicKeyBytes = EncryptionManager.decodeBase64(parsedB.pubB64),
            sendBytesChunked = { payload, _ ->
                val isEnc = sessionA.cipherSession != null
                val chunks = CyberLinkProtocol.chunkPayload(7, payload, sessionA.effectiveMtu, encrypted = isEnc)
                for (c in chunks) {
                    val re = sessionB.reassembler.feed(c) ?: continue
                    if (!re.encrypted) {
                        val env = CyberLinkProtocol.decodeEnvelope(re.payload) as CyberLinkProtocol.ProtocolEnvelope.ChallengeStart
                        val respBytes = sessionB.processIncomingChallengeStart(
                            encB,
                            env,
                            encA.exportPublicKeyX509(),
                        ).getOrThrow()
                        for (rc in CyberLinkProtocol.chunkPayload(8, respBytes, sessionB.effectiveMtu, encrypted = false)) {
                            sessionA.reassembler.feed(rc)?.let { sessionA.onIncomingReassembledFrame(it, encA) }
                        }
                    } else {
                        sessionB.onIncomingReassembledFrame(
                            reassembled = re,
                            localEncryption = encB,
                            onResponderVerifyAckReady = { reply ->
                                for (rc in CyberLinkProtocol.chunkPayload(9, reply, sessionB.effectiveMtu, encrypted = true)) {
                                    sessionA.reassembler.feed(rc)?.let { sessionA.onIncomingReassembledFrame(it, encA) }
                                }
                            },
                        )
                    }
                }
                GattOperationQueue.GattOpResult.Success()
            },
        )
        assertTrue(handshakeRes.isSuccess)
        assertEquals(TransportState.CONNECTED, sessionA.transportState)
        assertEquals(TransportState.CONNECTED, sessionB.transportState)
        assertNotNull(sessionB.peerIdentityPublicKeyB64)

        // Encrypted mutual PairingConfirmRequest / PairingConfirmAck exchange
        val req = CyberLinkProtocol.ProtocolEnvelope.PairingConfirmRequest(
            sessionId = sessionA.sessionId,
            initiatorNodeId = encA.nodeId,
            initiatorName = "InitiatorA",
            tempDeviceId = parsedB.tempDeviceId,
            pairingToken = parsedB.pairingToken,
            cl2Code = parsedB.cl2Code,
            timestampMs = System.currentTimeMillis(),
        )
        val encReq = sessionA.cipherSession!!.encrypt(CyberLinkProtocol.encodeEnvelope(req))
        val decReq = CyberLinkProtocol.decodeEnvelope(sessionB.cipherSession!!.decrypt(encReq))
        assertTrue(decReq is CyberLinkProtocol.ProtocolEnvelope.PairingConfirmRequest)
        assertEquals(parsedB.pairingToken, (decReq as CyberLinkProtocol.ProtocolEnvelope.PairingConfirmRequest).pairingToken)

        val ack = CyberLinkProtocol.ProtocolEnvelope.PairingConfirmAck(
            sessionId = sessionA.sessionId,
            responderNodeId = encB.nodeId,
            accepted = true,
            pairingToken = parsedB.pairingToken,
            timestampMs = System.currentTimeMillis(),
        )
        val encAck = sessionB.cipherSession!!.encrypt(CyberLinkProtocol.encodeEnvelope(ack))
        val decAck = CyberLinkProtocol.decodeEnvelope(sessionA.cipherSession!!.decrypt(encAck)) as CyberLinkProtocol.ProtocolEnvelope.PairingConfirmAck
        assertTrue(decAck.accepted)
    }

    @Test
    fun `4 encrypted Room storage hides plaintext and tracks DELIVERED and READ receipts`() = runBlocking {
        val dao = db.messageDao()
        val peerId = "CL-PEER123456"
        val localId = "CL-LOCAL00001"
        val secretText = "Treffen um 18:30 am Nordtor"
        val encryptedStored = storageCipher.encryptString(secretText)

        assertTrue(encryptedStored.startsWith(LocalStorageCipher.PREFIX))
        assertFalse(encryptedStored.contains(secretText))
        assertEquals(secretText, storageCipher.decryptString(encryptedStored))

        // Outgoing queued -> sent -> delivered -> read
        val outMsg = MessageEntity(
            messageId = "msg-out-1",
            peerNodeId = peerId,
            senderNodeId = localId,
            content = encryptedStored,
            status = "QUEUED",
        )
        dao.upsert(outMsg)
        assertEquals(1, dao.getQueuedMessages().size)
        dao.updateStatus("msg-out-1", "SENT")
        assertEquals(0, dao.getQueuedMessages().size)
        dao.updateStatus("msg-out-1", "DELIVERED")
        dao.updateStatus("msg-out-1", "READ")

        // Incoming unread message triggers read-receipt batch query
        dao.upsert(
            MessageEntity(
                messageId = "msg-in-1",
                peerNodeId = peerId,
                senderNodeId = peerId,
                content = storageCipher.encryptString("Verstanden!"),
                status = "DELIVERED",
            )
        )
        val unread = dao.getUnreadIncomingForPeer(peerId, localId)
        assertEquals(1, unread.size)
        assertEquals("msg-in-1", unread.first().messageId)

        dao.updateStatus("msg-in-1", "READ")
        assertTrue(dao.getUnreadIncomingForPeer(peerId, localId).isEmpty())

        val recent = dao.observeRecent().first()
        assertEquals(2, recent.size)
        assertTrue(recent.all { it.content.startsWith(LocalStorageCipher.PREFIX) })
    }

    @Test
    fun `5 chunked Bluetooth file transfer verifies SHA-256 integrity on receiver`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val enc = EncryptionManager()
        val btMgr = BluetoothManager(context, scope, enc, { null })
        val transferMgr = BluetoothFileTransferManager(context, btMgr, db.messageDao(), storageCipher)

        val originalBytes = ByteArray(1500) { idx -> ((idx * 31) and 0xFF).toByte() }
        val shaHex = MessageDigest.getInstance("SHA-256")
            .digest(originalBytes)
            .joinToString("") { "%02x".format(it) }

        val chunkSize = BluetoothFileTransferManager.CHUNK_SIZE_BLE // 384
        val totalChunks = (originalBytes.size + chunkSize - 1) / chunkSize
        val offer = CyberLinkProtocol.ProtocolEnvelope.FileTransferOffer(
            transferId = "tx-file-1",
            messageId = "msg-file-1",
            senderNodeId = "CL-SENDER001",
            recipientNodeId = enc.nodeId,
            fileName = "lageplan.bin",
            mimeType = "application/octet-stream",
            messageType = "FILE",
            fileSize = originalBytes.size.toLong(),
            sha256 = shaHex,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            timestampMs = System.currentTimeMillis(),
        )
        transferMgr.onOfferReceived("CL-SENDER001", offer)
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, originalBytes.size)
            val chunk = CyberLinkProtocol.ProtocolEnvelope.FileTransferChunk(
                transferId = "tx-file-1",
                chunkIndex = i,
                totalChunks = totalChunks,
                dataB64 = Base64.encodeToString(originalBytes.copyOfRange(start, end), Base64.NO_WRAP),
            )
            transferMgr.onChunkReceived("CL-SENDER001", chunk)
        }

        val recent = db.messageDao().observeRecent().first()
        assertEquals(1, recent.size)
        val saved = recent.first()
        assertEquals("DELIVERED", saved.status)
        assertEquals("FILE", saved.messageType)
        assertTrue(saved.content.startsWith(LocalStorageCipher.PREFIX))
        val savedFile = File(saved.attachmentPath!!)
        assertTrue(savedFile.exists())
        assertArrayEquals(originalBytes, savedFile.readBytes())
        assertEquals("Abgeschlossen ✓", transferMgr.transfers.value["tx-file-1"]?.stateLabel)

        // Corrupt checksum test: must reject and not persist
        val badOffer = offer.copy(
            transferId = "tx-file-bad",
            messageId = "msg-file-bad",
            sha256 = "0".repeat(64),
        )
        transferMgr.onOfferReceived("CL-SENDER001", badOffer)
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, originalBytes.size)
            transferMgr.onChunkReceived(
                "CL-SENDER001",
                CyberLinkProtocol.ProtocolEnvelope.FileTransferChunk(
                    transferId = "tx-file-bad",
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    dataB64 = Base64.encodeToString(originalBytes.copyOfRange(start, end), Base64.NO_WRAP),
                ),
            )
        }
        assertEquals("SHA-256 Fehler", transferMgr.transfers.value["tx-file-bad"]?.stateLabel)
        assertEquals(1, db.messageDao().observeRecent().first().size)
    }

    @Test
    fun `6 audio and video calls honestly enforce RFCOMM requirement and handle call signals`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val enc = EncryptionManager()
        val btMgr = BluetoothManager(context, scope, enc, { null })
        val audioMgr = AudioCallManager(context, scope, btMgr)
        val videoMgr = VideoCallManager(scope, btMgr)

        // BLE-only or unconnected peer must return UNSUPPORTED (never simulate media)
        val audioRes = audioMgr.startOutgoingCall("CL-PEER999", hasMicPermission = true)
        assertTrue(audioRes.isFailure)
        assertEquals(AudioCallManager.CallStatus.UNSUPPORTED, audioMgr.state.value.status)

        val videoRes = videoMgr.startVideoCall("CL-PEER999", hasCameraPermission = true)
        assertTrue(videoRes.isFailure)
        assertEquals(VideoCallManager.VideoCallStatus.UNSUPPORTED, videoMgr.state.value.status)

        // Incoming invite/hangup signaling transitions cleanly for both audio and video
        audioMgr.onIncomingCallSignal(
            "CL-PEER999",
            CyberLinkProtocol.ProtocolEnvelope.CallSignal("c1", "AUDIO", "INVITE", "CL-PEER999", "RFCOMM", 100L),
        )
        assertEquals(AudioCallManager.CallStatus.RINGING_INCOMING, audioMgr.state.value.status)
        audioMgr.onIncomingCallSignal(
            "CL-PEER999",
            CyberLinkProtocol.ProtocolEnvelope.CallSignal("c1", "AUDIO", "HANGUP", "CL-PEER999", "RFCOMM", 105L),
        )
        assertEquals(AudioCallManager.CallStatus.ENDED, audioMgr.state.value.status)

        videoMgr.onIncomingCallSignal(
            "CL-PEER999",
            CyberLinkProtocol.ProtocolEnvelope.CallSignal("v1", "VIDEO", "INVITE", "CL-PEER999", "RFCOMM", 100L),
        )
        assertEquals(VideoCallManager.VideoCallStatus.RINGING_INCOMING, videoMgr.state.value.status)
        videoMgr.onIncomingCallSignal(
            "CL-PEER999",
            CyberLinkProtocol.ProtocolEnvelope.CallSignal("v1", "VIDEO", "REJECT", "CL-PEER999", "RFCOMM", 105L),
        )
        assertEquals(VideoCallManager.VideoCallStatus.ENDED, videoMgr.state.value.status)
    }

    @Test
    fun `7 disconnect invalidates session and queued offline messages remain ready for reconnect flush`() = runBlocking {
        val peerId = "CL-RECONPEER"
        val session = PeerTransportSession(peerId, "11:22:33:44:55:66", "PeerReconn")
        session.updateNegotiatedMtu(247)
        session.updateState(TransportState.CONNECTED)

        session.closeDisconnected("Verbindung abgebrochen")
        assertEquals(TransportState.DISCONNECTED, session.transportState)
        assertEquals(ActiveTransport.NONE, session.activeTransport)
        assertNull(session.negotiatedMtu)

        // Offline message queued while disconnected
        val dao = db.messageDao()
        dao.upsert(
            MessageEntity(
                messageId = "queued-offline-1",
                peerNodeId = peerId,
                senderNodeId = "CL-LOCAL1",
                content = storageCipher.encryptString("Nachricht während Funkloch"),
                status = "QUEUED",
            )
        )
        val queued = dao.getQueuedMessages()
        assertEquals(1, queued.size)
        assertEquals("queued-offline-1", queued.first().messageId)
        assertEquals("Nachricht während Funkloch", storageCipher.decryptString(queued.first().content))
    }

    @Test
    fun `8 BLE advertisement payload stays well under 31 byte legacy limit and preserves exact Node-ID`() {
        val sampleNodeId = "CL-8C91246ABD8F"
        val mfgPayload = BleAdvertisementCodec.encodeManufacturerNodePayload(sampleNodeId)
        assertEquals(7, mfgPayload.size)
        assertEquals(sampleNodeId, BleAdvertisementCodec.decodeManufacturerNodePayload(mfgPayload))

        val primaryBytes = BleAdvertisementCodec.estimatePrimaryUuidPacketBytes()
        val scanRspBytes = BleAdvertisementCodec.estimateScanResponseBytes(mfgPayload.size)
        assertTrue("Primary packet ($primaryBytes B) must fit <= 31 B", primaryBytes <= BleAdvertisementCodec.LEGACY_MAX_BYTES)
        assertTrue("ScanResponse ($scanRspBytes B) must fit <= 31 B", scanRspBytes <= BleAdvertisementCodec.LEGACY_MAX_BYTES)
        assertEquals(21, primaryBytes)
        assertEquals(11, scanRspBytes)

        assertEquals(sampleNodeId, BleAdvertisementCodec.decodeServiceDataNodeId(sampleNodeId.toByteArray(Charsets.UTF_8)))
        assertTrue(describeAdvertiseError(1).contains("DATA_TOO_LARGE"))
        assertTrue(describeScanError(6).contains("TOO_FREQUENTLY"))
    }

    @Test
    fun `9 BluetoothManager surfaces honest FAILED diagnostics when adapter disabled or permissions missing`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val enc = EncryptionManager()
        val btMgr = BluetoothManager(context, scope, enc, { null })

        btMgr.startPeripheralServices()
        btMgr.startDiscovery(BatteryMode.PERFORMANCE)

        val diag = btMgr.bleDiagnostics.value
        assertEquals(enc.nodeId, diag.ownNodeId)
        assertEquals(DiagnosticComponentState.FAILED, diag.advertiseState)
        assertEquals(DiagnosticComponentState.FAILED, diag.scannerState)
        assertNotNull(diag.advertiseErrorCode)
        assertNotNull(diag.scanErrorCode)
        btMgr.shutdown()
    }

    @Test
    fun `10 SettingsStore keeps Node-ID stable across reloads and matches QR and BLE payload`() = runBlocking {
        val store = SettingsStore(context)
        val enc1 = store.loadOrCreateEncryptionManager()
        val enc2 = store.loadOrCreateEncryptionManager()
        assertEquals(enc1.nodeId, enc2.nodeId)
        assertEquals(enc1.publicKeyBase64, enc2.publicKeyBase64)

        val qr = PeerManager.buildLocalQrPayload(enc1, "PhoneB")
        assertEquals(enc1.nodeId, qr.nodeId)
        val decodedAdvId = BleAdvertisementCodec.decodeManufacturerNodePayload(
            BleAdvertisementCodec.encodeManufacturerNodePayload(qr.nodeId)
        )
        assertEquals(qr.nodeId, decodedAdvId)
    }
}
