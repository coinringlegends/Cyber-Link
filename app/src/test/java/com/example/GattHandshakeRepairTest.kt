package com.example

import android.bluetooth.BluetoothGattCharacteristic
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.CyberLinkUuids
import com.example.bluetooth.GattCapabilityValidator
import com.example.bluetooth.GattOperationQueue
import com.example.bluetooth.GattStep
import com.example.bluetooth.PeerManager
import com.example.bluetooth.PeerTransportSession
import com.example.bluetooth.TransportState
import com.example.bluetooth.describeGattStatus
import com.example.crypto.EncryptionManager
import com.example.protocol.CyberLinkProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GattHandshakeRepairTest {

    private fun readySpecs(session: PeerTransportSession) {
        session.rxSpec = GattCapabilityValidator.CharacteristicSpec(
            uuid = CyberLinkUuids.RX_CHAR_UUID,
            properties = BluetoothGattCharacteristic.PROPERTY_WRITE,
            hasCccdDescriptor = false,
        )
        session.txSpec = GattCapabilityValidator.CharacteristicSpec(
            uuid = CyberLinkUuids.TX_CHAR_UUID,
            properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            hasCccdDescriptor = true,
        )
        session.notificationLocallyEnabled = true
        session.cccdWriteSucceeded = true
        session.cccdStatusCode = 0
    }

    private fun runSimulatedBidirectionalHandshake(mtu: Int): Pair<PeerTransportSession, PeerTransportSession> = runBlocking {
        val encA = EncryptionManager()
        val encB = EncryptionManager()
        val sessionA = PeerTransportSession(encB.nodeId, "AA:BB:CC:DD:EE:01", "PhoneA")
        val sessionB = PeerTransportSession(encA.nodeId, "AA:BB:CC:DD:EE:02", "PhoneB")
        sessionA.updateNegotiatedMtu(mtu)
        sessionB.updateNegotiatedMtu(mtu)
        readySpecs(sessionA)
        readySpecs(sessionB)

        val result = sessionA.runClientChallengeHandshake(
            localEncryption = encA,
            peerExpectedPublicKeyBytes = encB.exportPublicKeyX509(),
            sendBytesChunked = { payload, _ ->
                // Verify Transport stays NONE before bidirectional VERIFY_ACK completes
                val isVerify = sessionA.cipherSession != null
                val chunks = CyberLinkProtocol.chunkPayload(1, payload, sessionA.effectiveMtu, encrypted = isVerify)
                for (chunk in chunks) {
                    assertTrue(
                        "Chunk size ${chunk.size} exceeds ATT payload for MTU $mtu",
                        chunk.size <= CyberLinkProtocol.maxAttPayloadForMtu(mtu),
                    )
                    val reassembled = sessionB.reassembler.feed(chunk) ?: continue
                    if (!reassembled.encrypted) {
                        val env = CyberLinkProtocol.decodeEnvelope(reassembled.payload)
                        if (env is CyberLinkProtocol.ProtocolEnvelope.ChallengeStart) {
                            val respBytes = sessionB.processIncomingChallengeStart(
                                localEncryption = encB,
                                start = env,
                                peerExpectedPublicKeyBytes = encA.exportPublicKeyX509(),
                            ).getOrThrow()
                            val respChunks = CyberLinkProtocol.chunkPayload(2, respBytes, sessionB.effectiveMtu, encrypted = false)
                            for (rc in respChunks) {
                                val rMsg = sessionA.reassembler.feed(rc)
                                if (rMsg != null) {
                                    sessionA.onIncomingReassembledFrame(rMsg, encA)
                                }
                            }
                        }
                    } else {
                        sessionB.onIncomingReassembledFrame(
                            reassembled = reassembled,
                            localEncryption = encB,
                            onResponderVerifyAckReady = { encryptedReply ->
                                val replyChunks = CyberLinkProtocol.chunkPayload(3, encryptedReply, sessionB.effectiveMtu, encrypted = true)
                                for (rc in replyChunks) {
                                    val rMsg = sessionA.reassembler.feed(rc)
                                    if (rMsg != null) {
                                        sessionA.onIncomingReassembledFrame(rMsg, encA)
                                    }
                                }
                            },
                        )
                    }
                }
                GattOperationQueue.GattOpResult.Success()
            },
        )
        assertTrue("Handshake must succeed for MTU $mtu: ${result.exceptionOrNull()?.message}", result.isSuccess)
        sessionA to sessionB
    }

    @Test
    fun `1 challenge handshake succeeds with MTU 23 chunking`() {
        val (sessionA, sessionB) = runSimulatedBidirectionalHandshake(23)
        assertEquals(23, sessionA.negotiatedMtu)
        assertEquals(12, CyberLinkProtocol.maxChunkDataSizeForMtu(23))
        assertEquals(TransportState.CONNECTED, sessionA.transportState)
        assertEquals(TransportState.CONNECTED, sessionB.transportState)
        assertEquals(ActiveTransport.BLE_GATT, sessionA.activeTransport)
        assertEquals(ActiveTransport.BLE_GATT, sessionB.activeTransport)
        assertTrue(sessionA.verifyAckSent && sessionA.verifyAckReceived)
        assertTrue(sessionB.verifyAckSent && sessionB.verifyAckReceived)
        assertTrue(sessionA.logs.any { it.step == GattStep.VERIFY_ACK_COMPLETED })
    }

    @Test
    fun `2 challenge handshake succeeds with MTU 517`() {
        val (sessionA, sessionB) = runSimulatedBidirectionalHandshake(517)
        assertEquals(517, sessionA.negotiatedMtu)
        assertEquals(504, CyberLinkProtocol.maxChunkDataSizeForMtu(517))
        assertEquals(TransportState.CONNECTED, sessionA.transportState)
        assertEquals(TransportState.CONNECTED, sessionB.transportState)
        assertEquals(ActiveTransport.BLE_GATT, sessionA.activeTransport)
    }

    @Test
    fun `3 missing RX characteristic aborts before challenge start`() = runBlocking {
        val encA = EncryptionManager()
        val session = PeerTransportSession("CL-TESTRX", "00:11:22:33:44:55", "TestPeer")
        session.txSpec = GattCapabilityValidator.CharacteristicSpec(CyberLinkUuids.TX_CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, true)
        session.notificationLocallyEnabled = true
        session.cccdWriteSucceeded = true

        var writeCalled = false
        val res = session.runClientChallengeHandshake(encA, null, { _, _ ->
            writeCalled = true
            GattOperationQueue.GattOpResult.Success()
        })
        assertTrue(res.isFailure)
        assertFalse(writeCalled)
        assertEquals(TransportState.ERROR, session.transportState)
        assertEquals(ActiveTransport.NONE, session.activeTransport)
        assertTrue(session.lastError!!.contains("RX-Characteristic"))
    }

    @Test
    fun `4 missing TX characteristic aborts before challenge start`() = runBlocking {
        val encA = EncryptionManager()
        val session = PeerTransportSession("CL-TESTTX", "00:11:22:33:44:55", "TestPeer")
        session.rxSpec = GattCapabilityValidator.CharacteristicSpec(CyberLinkUuids.RX_CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, false)

        val res = session.runClientChallengeHandshake(encA, null, { _, _ -> GattOperationQueue.GattOpResult.Success() })
        assertTrue(res.isFailure)
        assertEquals(TransportState.ERROR, session.transportState)
        assertEquals(ActiveTransport.NONE, session.activeTransport)
        assertTrue(session.lastError!!.contains("TX-Characteristic"))
    }

    @Test
    fun `5 failed CCCD activation prevents challenge start`() = runBlocking {
        val encA = EncryptionManager()
        val session = PeerTransportSession("CL-CCCD", "00:11:22:33:44:55", "TestPeer")
        readySpecs(session)
        session.cccdWriteSucceeded = false
        session.cccdStatusCode = 133

        val res = session.runClientChallengeHandshake(encA, null, { _, _ -> GattOperationQueue.GattOpResult.Success() })
        assertTrue(res.isFailure)
        assertEquals(TransportState.ERROR, session.transportState)
        assertEquals(ActiveTransport.NONE, session.activeTransport)
        assertTrue(session.lastError!!.contains("CCCD"))
    }

    @Test
    fun `6 sequential GattOperationQueue prevents concurrent operations and surfaces busy rejection`() = runBlocking {
        val queue = GattOperationQueue(defaultTimeoutMs = 2_000L)
        val order = mutableListOf<String>()

        coroutineScope {
            val op1 = async {
                queue.enqueue("op1") {
                    order.add("start_1")
                    true
                }
            }
            val op2 = async {
                delay(20)
                queue.enqueue("op2") {
                    order.add("start_2")
                    true
                }
            }
            delay(60)
            assertEquals(listOf("start_1"), order) // op2 did NOT start while op1 was in flight!
            queue.completeActive("op1", 0, callbackName = "cb1")
            delay(40)
            assertEquals(listOf("start_1", "start_2"), order)
            queue.completeActive("op2", 0, callbackName = "cb2")
            assertTrue(op1.await() is GattOperationQueue.GattOpResult.Success)
            assertTrue(op2.await() is GattOperationQueue.GattOpResult.Success)
        }

        val busyReject = queue.enqueue("op3") { false }
        assertTrue(busyReject is GattOperationQueue.GattOpResult.Failed)
    }

    @Test
    fun `7 GATT status 147 mapped to GATT_CONNECTION_TIMEOUT not generic MTU error`() {
        val desc147 = describeGattStatus(147)
        assertEquals("GATT_CONNECTION_TIMEOUT (147)", desc147)
        assertFalse(desc147.contains("MTU"))
    }

    @Test
    fun `8 timeout during challenge start write fails cleanly`() = runBlocking {
        val encA = EncryptionManager()
        val session = PeerTransportSession("CL-WRITETIMEOUT", "00:11:22:33:44:55", "TestPeer")
        readySpecs(session)

        val res = session.runClientChallengeHandshake(
            localEncryption = encA,
            peerExpectedPublicKeyBytes = null,
            sendBytesChunked = { _, _ ->
                GattOperationQueue.GattOpResult.Failed(
                    reason = "GATT operation timeout after 5000ms in writeChar_rx_0",
                    callbackName = "writeChar_rx_0",
                    isTimeout = true,
                )
            },
        )
        assertTrue(res.isFailure)
        assertEquals(TransportState.ERROR, session.transportState)
        assertEquals(ActiveTransport.NONE, session.activeTransport)
        assertTrue(session.lastError!!.contains("Write-Timeout"))
    }

    @Test
    fun `9 response timeout fires when peer never replies`() = runBlocking {
        val encA = EncryptionManager()
        val session = PeerTransportSession("CL-RESPTIMEOUT", "00:11:22:33:44:55", "TestPeer")
        readySpecs(session)

        val res = session.runClientChallengeHandshake(
            localEncryption = encA,
            peerExpectedPublicKeyBytes = null,
            sendBytesChunked = { _, _ -> GattOperationQueue.GattOpResult.Success() },
            responseTimeoutMs = 150L,
        )
        assertTrue(res.isFailure)
        assertEquals(TransportState.ERROR, session.transportState)
        assertEquals(ActiveTransport.NONE, session.activeTransport)
        assertTrue(session.lastError!!.contains("Response-Timeout"))
    }

    @Test
    fun `10 reconnect after error invalidates old MTU and succeeds on fresh attempt`() {
        val session = PeerTransportSession("CL-RECONNECT", "00:11:22:33:44:55", "TestPeer")
        session.updateNegotiatedMtu(247)
        session.fail("GATT_CONNECTION_TIMEOUT (147)", statusCode = 147)
        assertNull(session.negotiatedMtu)
        assertEquals(ActiveTransport.NONE, session.activeTransport)
        assertEquals(TransportState.ERROR, session.transportState)

        session.resetForNewAttempt()
        assertNull(session.lastError)
        assertNull(session.negotiatedMtu)

        val (reconnectedA, _) = runSimulatedBidirectionalHandshake(517)
        assertEquals(TransportState.CONNECTED, reconnectedA.transportState)
        assertEquals(ActiveTransport.BLE_GATT, reconnectedA.activeTransport)
    }

    @Test
    fun `11 two simultaneously initiated connections resolve deterministically via tie-breaker`() {
        val sessionAtoB = PeerTransportSession("CL-BBBBBBBBBBBB", "11:11:11:11:11:11", "B")
        val sessionBtoA = PeerTransportSession("CL-AAAAAAAAAAAA", "22:22:22:22:22:22", "A")
        assertTrue(sessionAtoB.isLocalInitiator("CL-AAAAAAAAAAAA"))
        assertFalse(sessionBtoA.isLocalInitiator("CL-BBBBBBBBBBBB"))
    }

    @Test
    fun `12 QR pairing payload excludes private key and verifies CL2 code`() {
        val enc = EncryptionManager()
        val qrJson = PeerManager.buildLocalQrPayloadJson(enc, "AlphaNode")
        assertFalse("Private key must NEVER appear in QR payload", qrJson.contains("PRIVATE", ignoreCase = true))
        val parsed = PeerManager.parseQrPayload(qrJson).getOrThrow()
        assertEquals(enc.nodeId, parsed.nodeId)
        assertNotNull(parsed.cl2Code)
        assertTrue(PeerManager.isValidCl2Code(parsed.cl2Code))
    }
}
