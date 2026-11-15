package com.example.bluetooth

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/**
 * Verifies every GATT prerequisite before starting the cryptographic Challenge-Response handshake.
 * Prevents opaque "MTU- oder Übertragungsfehler" by isolating missing RX/TX, invalid properties,
 * missing CCCD (0x2902), failed setCharacteristicNotification, or failed onDescriptorWrite.
 */
object GattCapabilityValidator {

    data class CharacteristicSpec(
        val uuid: UUID,
        val properties: Int,
        val hasCccdDescriptor: Boolean,
    )

    sealed class ValidationResult {
        data class Ready(val writeType: Int, val writeTypeLabel: String, val txModeLabel: String) : ValidationResult()
        data class Invalid(
            val failedStep: GattStep,
            val characteristicUuid: String?,
            val reason: String,
        ) : ValidationResult()
    }

    fun validateBeforeChallenge(
        rxSpec: CharacteristicSpec?,
        txSpec: CharacteristicSpec?,
        notificationLocallyEnabled: Boolean,
        cccdWriteSucceeded: Boolean,
        cccdGattStatus: Int? = null,
    ): ValidationResult {
        if (rxSpec == null) {
            return ValidationResult.Invalid(
                failedStep = GattStep.RX_CHARACTERISTIC_FOUND,
                characteristicUuid = CyberLinkUuids.RX_CHAR_UUID.toString(),
                reason = "RX-Characteristic (${CyberLinkUuids.RX_CHAR_UUID}) nicht im GATT-Service gefunden.",
            )
        }

        val hasWrite = (rxSpec.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val hasWriteNoResp = (rxSpec.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        if (!hasWrite && !hasWriteNoResp) {
            return ValidationResult.Invalid(
                failedStep = GattStep.RX_CHARACTERISTIC_FOUND,
                characteristicUuid = rxSpec.uuid.toString(),
                reason = "RX-Characteristic besitzt weder WRITE noch WRITE_NO_RESPONSE (properties=0x${rxSpec.properties.toString(16)}).",
            )
        }

        if (txSpec == null) {
            return ValidationResult.Invalid(
                failedStep = GattStep.TX_CHARACTERISTIC_FOUND,
                characteristicUuid = CyberLinkUuids.TX_CHAR_UUID.toString(),
                reason = "TX-Characteristic (${CyberLinkUuids.TX_CHAR_UUID}) nicht im GATT-Service gefunden.",
            )
        }

        val hasNotify = (txSpec.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val hasIndicate = (txSpec.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        if (!hasNotify && !hasIndicate) {
            return ValidationResult.Invalid(
                failedStep = GattStep.TX_CHARACTERISTIC_FOUND,
                characteristicUuid = txSpec.uuid.toString(),
                reason = "TX-Characteristic besitzt weder NOTIFY noch INDICATE (properties=0x${txSpec.properties.toString(16)}).",
            )
        }

        if (!txSpec.hasCccdDescriptor) {
            return ValidationResult.Invalid(
                failedStep = GattStep.CCCD_WRITE_STARTED,
                characteristicUuid = txSpec.uuid.toString(),
                reason = "CCCD-Descriptor 0x2902 fehlt an TX-Characteristic.",
            )
        }

        if (!notificationLocallyEnabled) {
            return ValidationResult.Invalid(
                failedStep = GattStep.CCCD_WRITE_STARTED,
                characteristicUuid = txSpec.uuid.toString(),
                reason = "setCharacteristicNotification(TX, true) fehlgeschlagen.",
            )
        }

        if (!cccdWriteSucceeded) {
            val statusTxt = cccdGattStatus?.let { describeGattStatus(it) } ?: "kein onDescriptorWrite-GATT_SUCCESS"
            return ValidationResult.Invalid(
                failedStep = GattStep.CCCD_WRITE_COMPLETED,
                characteristicUuid = txSpec.uuid.toString(),
                reason = "CCCD-Aktivierung fehlgeschlagen: $statusTxt.",
            )
        }

        // Prefer WRITE_TYPE_DEFAULT (with ATT Write Response) for reliable challenge handshake; fallback to NO_RESPONSE
        val writeType = if (hasWrite) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val writeTypeLabel = if (hasWrite) "WRITE_TYPE_DEFAULT" else "WRITE_TYPE_NO_RESPONSE"
        val txModeLabel = if (hasNotify) "NOTIFY" else "INDICATE"
        return ValidationResult.Ready(
            writeType = writeType,
            writeTypeLabel = writeTypeLabel,
            txModeLabel = txModeLabel,
        )
    }
}
