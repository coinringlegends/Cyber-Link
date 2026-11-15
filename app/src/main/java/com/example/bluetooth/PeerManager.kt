package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.util.Base64
import com.example.crypto.EncryptionManager
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages pairing payloads (signed expiring QR payloads & 6-digit CL2 confirmation codes) and peer verification.
 * SECURITY:
 * - Private keys and SIM secrets are NEVER included in QR codes or CL2 pairing codes.
 * - QR payloads carry ECDSA SHA256withECDSA signature, expiration timestamp, and single-use pairing token.
 * - Masked Android placeholder BT addresses (e.g., 02:00:00:00:00:00) are filtered out.
 */
object PeerManager {

    const val QR_PROTOCOL_ID = "CYBER_LINK"
    const val QR_PROTOCOL_VERSION = 5
    const val DEFAULT_QR_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private const val MASKED_ANDROID_MAC = "02:00:00:00:00:00"
    private const val ZERO_MAC = "00:00:00:00:00:00"
    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val secureRandom = SecureRandom()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class PairingQrPayload(
        val protocol: String = QR_PROTOCOL_ID,
        val v: Int = QR_PROTOCOL_VERSION,
        val nodeId: String,
        val name: String,
        val pubB64: String,
        val cl2Code: String,
        val tempDeviceId: String,
        val btAddress: String? = null,
        val pairingToken: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
        val signatureB64: String,
    )

    fun deriveCl2Code(nodeId: String, publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(nodeId.toByteArray(Charsets.UTF_8) + publicKeyBytes)
        val num = ((digest[0].toInt() and 0xFF) shl 16) or
            ((digest[1].toInt() and 0xFF) shl 8) or
            (digest[2].toInt() and 0xFF)
        return "%06d".format(num % 1_000_000)
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    fun getValidBluetoothAddressOrNull(adapter: BluetoothAdapter?): String? {
        if (adapter == null) return null
        val raw = runCatching { adapter.address }.getOrNull()?.trim() ?: return null
        return sanitizeBluetoothAddress(raw)
    }

    fun sanitizeBluetoothAddress(raw: String?): String? {
        val candidate = raw?.trim()?.uppercase() ?: return null
        if (candidate.isEmpty() || candidate == MASKED_ANDROID_MAC || candidate == ZERO_MAC) return null
        return if (MAC_REGEX.matches(candidate)) candidate else null
    }

    fun generateRandomTempDeviceId(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        return "TMP-" + bytes.joinToString("") { "%02X".format(it) }
    }

    fun generateRandomPairingToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun canonicalSignInput(
        protocol: String,
        v: Int,
        nodeId: String,
        name: String,
        pubB64: String,
        cl2Code: String,
        tempDeviceId: String,
        btAddress: String?,
        pairingToken: String,
        issuedAtMs: Long,
        expiresAtMs: Long,
    ): ByteArray = (
        "$protocol|$v|$nodeId|$name|$pubB64|$cl2Code|$tempDeviceId|${btAddress.orEmpty()}|$pairingToken|$issuedAtMs|$expiresAtMs"
        ).toByteArray(Charsets.UTF_8)

    fun buildLocalQrPayload(
        encryptionManager: EncryptionManager,
        displayName: String,
        btAddress: String? = null,
        ttlMs: Long = DEFAULT_QR_TTL_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): PairingQrPayload {
        val pubBytes = encryptionManager.exportPublicKeyX509()
        val safeName = displayName.trim().ifBlank { encryptionManager.nodeId }
        val safeAddr = sanitizeBluetoothAddress(btAddress)
        val cl2 = deriveCl2Code(encryptionManager.nodeId, pubBytes)
        val tempId = generateRandomTempDeviceId()
        val token = generateRandomPairingToken()
        val expiresAt = nowMs + ttlMs.coerceAtLeast(30_000L)
        val signBytes = canonicalSignInput(
            protocol = QR_PROTOCOL_ID,
            v = QR_PROTOCOL_VERSION,
            nodeId = encryptionManager.nodeId,
            name = safeName,
            pubB64 = encryptionManager.publicKeyBase64,
            cl2Code = cl2,
            tempDeviceId = tempId,
            btAddress = safeAddr,
            pairingToken = token,
            issuedAtMs = nowMs,
            expiresAtMs = expiresAt,
        )
        val sig = EncryptionManager.encodeBase64(encryptionManager.signPayload(signBytes))
        return PairingQrPayload(
            protocol = QR_PROTOCOL_ID,
            v = QR_PROTOCOL_VERSION,
            nodeId = encryptionManager.nodeId,
            name = safeName,
            pubB64 = encryptionManager.publicKeyBase64,
            cl2Code = cl2,
            tempDeviceId = tempId,
            btAddress = safeAddr,
            pairingToken = token,
            issuedAtMs = nowMs,
            expiresAtMs = expiresAt,
            signatureB64 = sig,
        )
    }

    fun buildLocalQrPayloadJson(
        encryptionManager: EncryptionManager,
        displayName: String,
        btAddress: String? = null,
        ttlMs: Long = DEFAULT_QR_TTL_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): String = encodeQrPayload(
        buildLocalQrPayload(
            encryptionManager = encryptionManager,
            displayName = displayName,
            btAddress = btAddress,
            ttlMs = ttlMs,
            nowMs = nowMs,
        )
    )

    fun encodeQrPayload(payload: PairingQrPayload): String = json.encodeToString(payload)

    fun parseQrPayload(
        raw: String,
        nowMs: Long = System.currentTimeMillis(),
        isTokenConsumed: (String) -> Boolean = { false },
    ): Result<PairingQrPayload> = runCatching {
        val parsed = json.decodeFromString<PairingQrPayload>(raw.trim())
        require(parsed.protocol == QR_PROTOCOL_ID) { "Ungültige Protokollkennung: ${parsed.protocol}" }
        require(parsed.v >= QR_PROTOCOL_VERSION) { "Nicht unterstützte Protokollversion: ${parsed.v}" }
        require(parsed.nodeId.startsWith("CL-")) { "Ungültige CYBER LINK Node-ID" }
        require(parsed.tempDeviceId.isNotBlank()) { "Temporäre Geräte-ID fehlt" }
        require(parsed.pairingToken.length >= 16) { "Pairing-Token ungültig" }
        require(parsed.expiresAtMs > parsed.issuedAtMs) { "Ungültiger Ablaufzeitraum" }
        require(nowMs <= parsed.expiresAtMs) { "QR-Code abgelaufen — bitte auf dem Partnergerät erneuern" }
        require(!isTokenConsumed(parsed.pairingToken)) { "Pairing-Token bereits verbraucht (Einmal-Token)" }

        val pubBytes = EncryptionManager.decodeBase64(parsed.pubB64)
        val derivedId = EncryptionManager.deriveNodeId(pubBytes)
        require(derivedId == parsed.nodeId) { "Öffentlicher Schlüssel stimmt nicht mit Node-ID überein" }
        val expectedCl2 = deriveCl2Code(parsed.nodeId, pubBytes)
        require(expectedCl2 == parsed.cl2Code) { "CL2-Bestätigungscode ungültig" }

        val cleanAddr = sanitizeBluetoothAddress(parsed.btAddress)
        val signBytes = canonicalSignInput(
            protocol = parsed.protocol,
            v = parsed.v,
            nodeId = parsed.nodeId,
            name = parsed.name,
            pubB64 = parsed.pubB64,
            cl2Code = parsed.cl2Code,
            tempDeviceId = parsed.tempDeviceId,
            btAddress = parsed.btAddress,
            pairingToken = parsed.pairingToken,
            issuedAtMs = parsed.issuedAtMs,
            expiresAtMs = parsed.expiresAtMs,
        )
        val sigBytes = EncryptionManager.decodeBase64(parsed.signatureB64)
        val signatureValid = EncryptionManager().verifySignature(pubBytes, signBytes, sigBytes)
        require(signatureValid) { "Kryptografische QR-Signatur ungültig" }

        parsed.copy(btAddress = cleanAddr)
    }

    fun isValidCl2Code(code: String): Boolean =
        code.length == 6 && code.all { it.isDigit() }
}
