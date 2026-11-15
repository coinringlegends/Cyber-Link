package com.example.bluetooth

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TransportState(val labelDe: String) {
    SAVED("GESPEICHERT"),
    BLUETOOTH_FOUND("BLUETOOTH GEFUNDEN"),
    CONNECTING("VERBINDET"),
    SERVICE_DISCOVERY("SERVICE DISCOVERY"),
    AUTHENTICATING("AUTHENTIFIZIERUNG"),
    DATA_CHANNEL_READY("DATENKANAL BEREIT"),
    CONNECTED("VERBUNDEN"),
    DISCONNECTED("GETRENNT"),
    ERROR("FEHLER"),
}

enum class ActiveTransport(val labelDe: String) {
    NONE("KEINER"),
    BLE_GATT("BLE GATT"),
    CLASSIC_RFCOMM("RFCOMM"),
    DUAL_BLE_RFCOMM("BLE + RFCOMM"),
}

enum class GattStep {
    GATT_CONNECTED,
    SERVICE_DISCOVERY_STARTED,
    SERVICE_DISCOVERY_COMPLETED,
    IDENTITY_READ_STARTED,
    IDENTITY_READ_COMPLETED,
    RX_CHARACTERISTIC_FOUND,
    TX_CHARACTERISTIC_FOUND,
    CCCD_WRITE_STARTED,
    CCCD_WRITE_COMPLETED,
    CHALLENGE_CREATED,
    CHALLENGE_WRITE_STARTED,
    CHALLENGE_WRITE_COMPLETED,
    RESPONSE_RECEIVED,
    CONFIRM_OK_RECEIVED,
    VERIFY_ACK_COMPLETED,
    STEP_ERROR,
}

data class GattLogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val step: GattStep,
    val peerNodeId: String?,
    val sessionId: String?,
    val connectionState: TransportState,
    val gattStatusCode: Int? = null,
    val callbackName: String? = null,
    val characteristicUuid: String? = null,
    val writeType: String? = null,
    val currentMtu: Int? = null,
    val detail: String,
) {
    fun formatLine(): String {
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        val statusPart = gattStatusCode?.let { " status=$it (${describeGattStatus(it)})" } ?: ""
        val cbPart = callbackName?.let { " cb=$it" } ?: ""
        val charPart = characteristicUuid?.let { " char=${it.take(8)}" } ?: ""
        val wtPart = writeType?.let { " writeType=$it" } ?: ""
        val mtuPart = currentMtu?.let { " mtu=$it" } ?: ""
        val peerPart = peerNodeId ?: "UNBEKANNT"
        val sessPart = sessionId?.take(8) ?: "-"
        return "[$timeStr] ${step.name} | peer=$peerPart sess=$sessPart state=${connectionState.labelDe}$statusPart$cbPart$charPart$wtPart$mtuPart | $detail"
    }
}

fun describeGattStatus(status: Int): String = when (status) {
    0 -> "GATT_SUCCESS"
    2 -> "GATT_READ_NOT_PERMITTED"
    3 -> "GATT_WRITE_NOT_PERMITTED"
    5 -> "GATT_INSUFFICIENT_AUTHENTICATION"
    6 -> "GATT_REQUEST_NOT_SUPPORTED"
    7 -> "GATT_INVALID_OFFSET"
    8 -> "GATT_CONN_TIMEOUT (8)"
    13 -> "GATT_INVALID_ATTRIBUTE_LENGTH"
    15 -> "GATT_INSUFFICIENT_ENCRYPTION"
    19 -> "GATT_CONN_TERMINATE_PEER_USER (19)"
    22 -> "GATT_CONN_TERMINATE_LOCAL_HOST (22)"
    34 -> "GATT_CONN_LMP_TIMEOUT (34)"
    133 -> "GATT_ERROR (133)"
    147 -> "GATT_CONNECTION_TIMEOUT (147)"
    257 -> "GATT_FAILURE (257)"
    else -> "GATT_STATUS_$status"
}

fun describeAdvertiseError(errorCode: Int): String = when (errorCode) {
    1 -> "ADVERTISE_FAILED_DATA_TOO_LARGE (1)"
    2 -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS (2)"
    3 -> "ADVERTISE_FAILED_ALREADY_STARTED (3)"
    4 -> "ADVERTISE_FAILED_INTERNAL_ERROR (4)"
    5 -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED (5)"
    -1 -> "BLUETOOTH_DISABLED (-1)"
    -2 -> "PERMISSION_DENIED_BLUETOOTH_ADVERTISE (-2)"
    else -> "ADVERTISE_FAILED_CODE_$errorCode"
}

fun describeScanError(errorCode: Int): String = when (errorCode) {
    1 -> "SCAN_FAILED_ALREADY_STARTED (1)"
    2 -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (2)"
    3 -> "SCAN_FAILED_INTERNAL_ERROR (3)"
    4 -> "SCAN_FAILED_FEATURE_UNSUPPORTED (4)"
    5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES (5)"
    6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY (6)"
    -1 -> "BLUETOOTH_DISABLED (-1)"
    -2 -> "PERMISSION_DENIED_BLUETOOTH_SCAN (-2)"
    else -> "SCAN_FAILED_CODE_$errorCode"
}

enum class DiagnosticComponentState(val labelDe: String) {
    NOT_STARTED("NICHT GESTARTET"),
    STARTING("STARTET…"),
    STARTED("STARTED"),
    RUNNING("RUNNING"),
    FAILED("FAILED"),
}

data class BleDiagnostics(
    val bluetoothEnabled: Boolean = false,
    val bleAdvertisingSupported: Boolean = false,
    val advertiseState: DiagnosticComponentState = DiagnosticComponentState.NOT_STARTED,
    val advertiseErrorCode: Int? = null,
    val advertiseErrorDetail: String? = null,
    val scannerState: DiagnosticComponentState = DiagnosticComponentState.NOT_STARTED,
    val scanErrorCode: Int? = null,
    val scanErrorDetail: String? = null,
    val ownNodeId: String = "",
    val targetNodeId: String? = null,
    val detectedServiceUuid: String? = null,
    val detectedNodeIds: List<String> = emptyList(),
    val gattStatusText: String = "BEREIT",
    val lastAndroidErrorCode: Int? = null,
)

/**
 * Compact BLE Legacy Advertisement encoding (< 31 bytes per packet):
 * - Primary AdvertiseData carries only Flags (3B) + 128-bit SERVICE_UUID (18B) = 21 bytes.
 * - ScanResponse carries ManufacturerData (id=0xC1B3): 1B protocol version (0x05) + 6B binary Node-ID hash = 11 bytes total!
 * Never exceeds the 31-byte Android legacy BLE AdvertiseData cap.
 */
object BleAdvertisementCodec {
    const val MANUFACTURER_ID = 0xC1B3
    const val PROTO_VERSION_BYTE: Byte = 0x05
    const val LEGACY_MAX_BYTES = 31
    private val HEX_12_REGEX = Regex("^CL-([0-9A-Fa-f]{12})$")

    fun encodeManufacturerNodePayload(nodeId: String): ByteArray {
        val match = HEX_12_REGEX.matchEntire(nodeId.trim())
        if (match != null) {
            val hex = match.groupValues[1]
            val out = ByteArray(7)
            out[0] = PROTO_VERSION_BYTE
            for (i in 0 until 6) {
                out[1 + i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }
        // Fallback if non-standard ID
        return byteArrayOf(PROTO_VERSION_BYTE) + nodeId.toByteArray(Charsets.UTF_8).take(16).toByteArray()
    }

    fun decodeManufacturerNodePayload(data: ByteArray?): String? {
        if (data == null || data.isEmpty()) return null
        if (data.size == 7 && data[0] == PROTO_VERSION_BYTE) {
            val hex = (1..6).joinToString("") { "%02X".format(data[it]) }
            return "CL-$hex"
        }
        if (data.size == 6) {
            val hex = data.joinToString("") { "%02X".format(it) }
            return "CL-$hex"
        }
        if (data.size > 1 && data[0] == PROTO_VERSION_BYTE) {
            val str = String(data, 1, data.size - 1, Charsets.UTF_8)
            if (str.startsWith("CL-")) return str
        }
        return null
    }

    fun decodeServiceDataNodeId(data: ByteArray?): String? {
        if (data == null || data.isEmpty()) return null
        if (data.size == 6) {
            return "CL-" + data.joinToString("") { "%02X".format(it) }
        }
        if (data.size == 7 && data[0] == PROTO_VERSION_BYTE) {
            return "CL-" + (1..6).joinToString("") { "%02X".format(data[it]) }
        }
        val text = String(data, Charsets.UTF_8).trim()
        return text.takeIf { it.startsWith("CL-") }
    }

    /** Exact AD overhead calculation for ScanResponse carrying only ManufacturerData. */
    fun estimateScanResponseBytes(mfgPayloadSize: Int): Int =
        1 /* len */ + 1 /* 0xFF */ + 2 /* mfgId */ + mfgPayloadSize

    /** Exact AD overhead calculation for Primary AdvertiseData carrying connectable Flags + 128-bit UUID. */
    fun estimatePrimaryUuidPacketBytes(): Int =
        3 /* Flags */ + 1 /* len */ + 1 /* 0x07 */ + 16 /* 128-bit UUID */
}

object CyberLinkUuids {
    val SERVICE_UUID: java.util.UUID = java.util.UUID.fromString("7A4C0001-9E2F-4B8D-A1C3-6D904E5F0001")
    val IDENTITY_CHAR_UUID: java.util.UUID = java.util.UUID.fromString("7A4C0002-9E2F-4B8D-A1C3-6D904E5F0001")
    val RX_CHAR_UUID: java.util.UUID = java.util.UUID.fromString("7A4C0003-9E2F-4B8D-A1C3-6D904E5F0001")
    val TX_CHAR_UUID: java.util.UUID = java.util.UUID.fromString("7A4C0004-9E2F-4B8D-A1C3-6D904E5F0001")
    val CCCD_UUID: java.util.UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val RFCOMM_SDP_UUID: java.util.UUID = java.util.UUID.fromString("7A4C0005-9E2F-4B8D-A1C3-6D904E5F0001")
    const val RFCOMM_SERVICE_NAME = "CyberLinkV5"
}
