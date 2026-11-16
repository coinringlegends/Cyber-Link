package com.example

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.bluetooth.ActiveTransport
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.CyberLinkUuids
import com.example.bluetooth.GattCapabilityValidator
import com.example.bluetooth.GattOperationQueue
import com.example.bluetooth.RfcommSocketState
import com.example.bluetooth.RfcommTransport
import com.example.bluetooth.StreamRfcommSocketHandle
import com.example.bluetooth.TransportState
import com.example.crypto.EncryptionManager
import com.example.protocol.CyberLinkProtocol
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RfcommTransportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun deriveMatchingCiphers(
        encA: EncryptionManager,
        encB: EncryptionManager,
    ): Pair<EncryptionManager.SessionCipher, EncryptionManager.SessionCipher> {
        val ephA = encA.generateEphemeralKeyPair()
        val ephB = encB.generateEphemeralKeyPair()
        val nonceA = encA.generateNonce(16)
        val nonceB = encB.generateNonce(16)
        val sessionId = "rfcomm-test-session"
        val cipherA = encA.deriveSession(
            sessionId = sessionId,
            localNodeId = encA.nodeId,
            peerNodeId = encB.nodeId,
            localEphemeralPrivate = ephA.private,
            peerEphemeralPublicBytes = ephB.public.encoded,
            nonceA = nonceA,
            nonceB = nonceB,
        )
        val cipherB = encB.deriveSession(
            sessionId = sessionId,
            localNodeId = encB.nodeId,
            peerNodeId = encA.nodeId,
            localEphemeralPrivate = ephB.private,
            peerEphemeralPublicBytes = ephA.public.encoded,
            nonceA = nonceA,
            nonceB = nonceB,
        )
        return cipherA to cipherB
    }

    @Test
    fun `1 BLE GATT ready never marks RFCOMM connected or ready and missing permission surfaces FAILED`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val encA = EncryptionManager()
        val encB = EncryptionManager()
        val btMgr = BluetoothManager(context, scope, encA, { null })

        val sessionA = btMgr.getOrCreateSession(encB.nodeId, "5E:9A:11:22:33:44", "Galaxy-A34")
        val sessionB = com.example.bluetooth.PeerTransportSession(encA.nodeId, "AA:BB:CC:DD:EE:01", "PhoneA")
        listOf(sessionA, sessionB).forEach { s ->
            s.rxSpec = GattCapabilityValidator.CharacteristicSpec(CyberLinkUuids.RX_CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, false)
            s.txSpec = GattCapabilityValidator.CharacteristicSpec(CyberLinkUuids.TX_CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, true)
            s.notificationLocallyEnabled = true
            s.cccdWriteSucceeded = true
            s.cccdStatusCode = 0
            s.updateNegotiatedMtu(247)
        }

        val gattRes = sessionA.runClientChallengeHandshake(
            localEncryption = encA,
            peerExpectedPublicKeyBytes = encB.exportPublicKeyX509(),
            sendBytesChunked = { payload, _, isEnc ->
                val chunks = CyberLinkProtocol.chunkPayload(1, payload, sessionA.effectiveMtu, encrypted = isEnc)
                for (c in chunks) {
                    val re = sessionB.reassembler.feed(c) ?: continue
                    if (!re.encrypted) {
                        val env = CyberLinkProtocol.decodeEnvelope(re.payload) as CyberLinkProtocol.ProtocolEnvelope.ChallengeStart
                        val respBytes = sessionB.processIncomingChallengeStart(encB, env, encA.exportPublicKeyX509()).getOrThrow()
                        for (rc in CyberLinkProtocol.chunkPayload(2, respBytes, sessionB.effectiveMtu, encrypted = false)) {
                            sessionA.reassembler.feed(rc)?.let { sessionA.onIncomingReassembledFrame(it, encA) }
                        }
                    } else {
                        sessionB.onIncomingReassembledFrame(
                            reassembled = re,
                            localEncryption = encB,
                            onResponderVerifyAckReady = { reply ->
                                for (rc in CyberLinkProtocol.chunkPayload(3, reply, sessionB.effectiveMtu, encrypted = true)) {
                                    sessionA.reassembler.feed(rc)?.let { sessionA.onIncomingReassembledFrame(it, encA) }
                                }
                            },
                        )
                    }
                }
                GattOperationQueue.GattOpResult.Success()
            },
        )
        assertTrue(gattRes.isSuccess)
        assertEquals(ActiveTransport.BLE_GATT, btMgr.getEffectiveTransportForPeer(encB.nodeId))

        // Strictly segregated: BLE ready, RFCOMM still NOT started / NOT ready
        btMgr.startPeripheralServices()
        val rfcommDiag = btMgr.rfcommDiagnostics.value
        assertEquals(RfcommSocketState.FAILED, rfcommDiag.serverSocketStatus)
        assertNotNull(rfcommDiag.serverErrorDetail)
        assertFalse(rfcommDiag.rfcommServerReady)
        assertFalse(rfcommDiag.rfcommClientConnectOk)
        assertFalse(rfcommDiag.rfcommSocketConnected)
        assertFalse(rfcommDiag.rfcommBidirectionalReady)

        // Upgrade call without permissions must fail honestly
        val upgradeRes = btMgr.upgradeToRfcomm(encB.nodeId)
        assertTrue(upgradeRes.isFailure)
        assertEquals(RfcommSocketState.FAILED, btMgr.rfcommDiagnostics.value.clientSocketStatus)
        assertNotNull(btMgr.rfcommDiagnostics.value.connectError)
        assertEquals(ActiveTransport.BLE_GATT, btMgr.getEffectiveTransportForPeer(encB.nodeId))

        btMgr.shutdown()
        scope.cancel()
    }

    @Test
    fun `2 piped RFCOMM bidirectional test verifies plaintext A-B-A-ACK and AES-256-GCM frames with byte counters`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val encA = EncryptionManager()
        val encB = EncryptionManager()
        val (cipherA, cipherB) = deriveMatchingCiphers(encA, encB)

        val serverReceivedApps = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val serverAppDeferred = CompletableDeferred<ByteArray>()

        val transportB = RfcommTransport(
            context = context,
            scope = scope,
            localNodeId = encB.nodeId,
            lookupSessionCipher = { peerId -> if (peerId == encA.nodeId) cipherB else null },
            onFrameReceived = { _, peerId, frameType, encrypted ->
                if (frameType == RfcommTransport.FRAME_PROTOCOL_ENVELOPE && peerId != null) {
                    val plain = cipherB.decrypt(encrypted)
                    serverReceivedApps.add(peerId to plain)
                    serverAppDeferred.complete(plain)
                }
            },
            onRfcommStateChanged = { _, _, _, _, _ -> },
        )

        val transportA = RfcommTransport(
            context = context,
            scope = scope,
            localNodeId = encA.nodeId,
            lookupSessionCipher = { peerId -> if (peerId == encB.nodeId) cipherA else null },
            onFrameReceived = { _, _, _, _ -> },
            onRfcommStateChanged = { _, _, _, _, _ -> },
        )

        val aOut = PipedOutputStream()
        val bIn = PipedInputStream(aOut, 16 * 1024)
        val bOut = PipedOutputStream()
        val aIn = PipedInputStream(bOut, 16 * 1024)

        val classicAddrB = "10:20:30:40:50:0B"
        val classicAddrA = "10:20:30:40:50:0A"

        val handleB = StreamRfcommSocketHandle(classicAddrA, "PhoneA", bIn, bOut)
        val handleA = StreamRfcommSocketHandle(classicAddrB, "PhoneB", aIn, aOut)

        transportB.attachSocketHandle(handleB, existingPeerNodeId = null)
        transportA.attachSocketHandle(handleA, existingPeerNodeId = encB.nodeId)

        // Socket connected alone MUST NOT be reported as bidirectionally ready
        assertTrue(transportA.isSocketConnected(classicAddrB, encB.nodeId))
        assertFalse(transportA.isBidirectionallyReady(classicAddrB, null, encB.nodeId))

        val testRes = transportA.runBidirectionalTestOnOpenSocket(
            address = classicAddrB,
            peerNodeId = encB.nodeId,
            cipher = cipherA,
        )
        assertTrue("Bidirectional RFCOMM test should succeed: ${testRes.exceptionOrNull()}", testRes.isSuccess)

        val diagA = transportA.diagnostics.value
        val diagB = transportB.diagnostics.value

        assertTrue(diagA.rfcommSocketConnected)
        assertTrue(diagA.rfcommClientConnectOk)
        assertTrue(diagA.rfcommBidirectionalReady)
        assertEquals(RfcommSocketState.CONNECTED, diagA.clientSocketStatus)
        assertTrue(diagA.bidirectionalTestStatus.contains("BESTANDEN"))
        assertTrue(diagA.bytesSent > 0L)
        assertTrue(diagA.bytesReceived > 0L)

        // Responder B bound Classic MAC <-> Node-ID A cleanly and marked bidirectional ready
        assertEquals(encA.nodeId, transportB.getBoundPeerNodeId(classicAddrA))
        assertTrue(diagB.rfcommBidirectionalReady)
        assertTrue(diagB.bytesSent > 0L)
        assertTrue(diagB.bytesReceived > 0L)
        assertTrue(transportA.isBidirectionallyReady(classicAddrB, null, encB.nodeId))
        assertTrue(transportB.isBidirectionallyReady(classicAddrA, null, encA.nodeId))

        // Send encrypted application envelope over verified RFCOMM channel
        val chat = CyberLinkProtocol.ProtocolEnvelope.ChatMessage(
            messageId = "rfcomm-msg-1",
            senderNodeId = encA.nodeId,
            recipientNodeId = encB.nodeId,
            text = "Hallo über Bluetooth Classic RFCOMM",
            timestampMs = 123456L,
        )
        val sendRes = transportA.sendFrame(
            address = classicAddrB,
            frameType = RfcommTransport.FRAME_PROTOCOL_ENVELOPE,
            payload = CyberLinkProtocol.encodeEnvelope(chat),
            cipher = cipherA,
        )
        assertTrue(sendRes.isSuccess)

        val decoded = CyberLinkProtocol.decodeEnvelope(withTimeout(3_000L) { serverAppDeferred.await() })
        assertTrue(decoded is CyberLinkProtocol.ProtocolEnvelope.ChatMessage)
        assertEquals("Hallo über Bluetooth Classic RFCOMM", (decoded as CyberLinkProtocol.ProtocolEnvelope.ChatMessage).text)

        transportA.stop()
        transportB.stop()
        scope.cancel()
    }

    @Test
    fun `3 missing SessionCipher or broken pipe fails RFCOMM test cleanly without fake CONNECTED`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val encA = EncryptionManager()
        val encB = EncryptionManager()
        val (cipherA, _) = deriveMatchingCiphers(encA, encB)

        val transportA = RfcommTransport(
            context = context,
            scope = scope,
            localNodeId = encA.nodeId,
            lookupSessionCipher = { null },
            onFrameReceived = { _, _, _, _ -> },
            onRfcommStateChanged = { _, _, _, _, _ -> },
        )

        val aOut = PipedOutputStream()
        val bIn = PipedInputStream(aOut, 4096)
        val bOut = PipedOutputStream()
        val aIn = PipedInputStream(bOut, 4096)
        val addrB = "AA:11:22:33:44:55"
        val handleA = StreamRfcommSocketHandle(addrB, "PeerB", aIn, aOut)
        transportA.attachSocketHandle(handleA, existingPeerNodeId = encB.nodeId)

        // Case 1: missing SessionCipher
        val noCipherRes = transportA.runBidirectionalTestOnOpenSocket(addrB, encB.nodeId, cipher = null)
        assertTrue(noCipherRes.isFailure)
        assertFalse(transportA.isBidirectionallyReady(addrB, null, encB.nodeId))
        assertEquals(RfcommSocketState.FAILED, transportA.diagnostics.value.clientSocketStatus)

        // Case 2: close peer output stream so PING times out / IOException fires
        bOut.close()
        bIn.close()
        val brokenRes = transportA.runBidirectionalTestOnOpenSocket(addrB, encB.nodeId, cipher = cipherA, timeoutMs = 400L)
        assertTrue(brokenRes.isFailure)
        assertFalse(transportA.isBidirectionallyReady(addrB, null, encB.nodeId))
        assertFalse(transportA.diagnostics.value.rfcommBidirectionalReady)

        transportA.stop()
        scope.cancel()
    }
}
