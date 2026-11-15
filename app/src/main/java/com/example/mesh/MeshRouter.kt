package com.example.mesh

import com.example.battery.BatteryMode
import com.example.protocol.CyberLinkProtocol
import java.util.Collections
import java.util.PriorityQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voluntary decentralized store-and-forward Mesh Router.
 * Rules enforced:
 * - Opt-in only; disabled automatically in BatteryMode.ULTRA.
 * - Duplicate detection via seen packetId ring cache.
 * - TTL and Hop-Limit enforcement.
 * - Priority queue (HIGH > NORMAL > LOW).
 * - NEVER relays private LOCATION_SHARE_UPDATE packets.
 */
class MeshRouter {

    enum class RelayDecision {
        DELIVER_LOCALLY,
        FORWARD_QUEUED,
        DROPPED_DISABLED,
        DROPPED_DUPLICATE,
        DROPPED_TTL_EXPIRED,
        DROPPED_LOCATION_FORBIDDEN,
    }

    private data class QueuedRelay(
        val packet: CyberLinkProtocol.ProtocolEnvelope.MeshRelayPacket,
        val priorityRank: Int,
        val enqueuedAtMs: Long,
    ) : Comparable<QueuedRelay> {
        override fun compareTo(other: QueuedRelay): Int {
            val pCmp = priorityRank.compareTo(other.priorityRank)
            return if (pCmp != 0) pCmp else enqueuedAtMs.compareTo(other.enqueuedAtMs)
        }
    }

    private val seenPacketIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val queue = PriorityQueue<QueuedRelay>()
    private val lock = Any()

    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    private val _relayedCount = MutableStateFlow(0)
    val relayedCount: StateFlow<Int> = _relayedCount.asStateFlow()

    fun handleIncomingMeshPacket(
        packet: CyberLinkProtocol.ProtocolEnvelope.MeshRelayPacket,
        localNodeId: String,
        userOptInEnabled: Boolean,
        batteryMode: BatteryMode,
    ): RelayDecision {
        // Security & privacy rule: location payloads must never travel through mesh relays
        val innerPreview = runCatching {
            val decoded = android.util.Base64.decode(packet.innerPayloadB64, android.util.Base64.NO_WRAP)
            String(decoded, Charsets.UTF_8)
        }.getOrDefault("")
        if (innerPreview.contains("LocationShareUpdate") || innerPreview.contains("LocationShareRevoke")) {
            return RelayDecision.DROPPED_LOCATION_FORBIDDEN
        }

        if (!seenPacketIds.add(packet.packetId)) {
            return RelayDecision.DROPPED_DUPLICATE
        }
        if (seenPacketIds.size > 500) {
            val iter = seenPacketIds.iterator()
            if (iter.hasNext()) {
                iter.next()
                iter.remove()
            }
        }

        if (packet.finalTargetNodeId == localNodeId) {
            return RelayDecision.DELIVER_LOCALLY
        }

        if (!userOptInEnabled || !batteryMode.allowMeshRelay) {
            return RelayDecision.DROPPED_DISABLED
        }

        if (packet.ttl <= 1 || packet.hopCount >= 6) {
            return RelayDecision.DROPPED_TTL_EXPIRED
        }

        val forwarded = packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1,
        )
        val rank = when (packet.priority.uppercase()) {
            "HIGH" -> 0
            "NORMAL" -> 1
            else -> 2
        }
        synchronized(lock) {
            queue.add(QueuedRelay(forwarded, rank, System.currentTimeMillis()))
            _queuedCount.value = queue.size
        }
        return RelayDecision.FORWARD_QUEUED
    }

    fun pollNextForward(): CyberLinkProtocol.ProtocolEnvelope.MeshRelayPacket? {
        synchronized(lock) {
            val next = queue.poll()?.packet
            _queuedCount.value = queue.size
            if (next != null) {
                _relayedCount.value = _relayedCount.value + 1
            }
            return next
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            seenPacketIds.clear()
            _queuedCount.value = 0
        }
    }
}
