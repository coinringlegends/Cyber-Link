package com.example.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Strictly sequential GATT operation queue for a single BLE connection.
 * Prevents Android GATT race conditions where initiating writeCharacteristic / writeDescriptor /
 * readCharacteristic / discoverServices / requestMtu while another operation is still in flight
 * returns false or silently drops callbacks.
 *
 * The next operation ONLY starts once the previous Android BluetoothGattCallback has completed
 * (or the explicit operation timeout fires).
 */
class GattOperationQueue(
    private val defaultTimeoutMs: Long = 5_000L,
) {
    sealed class GattOpResult {
        data class Success(val status: Int = 0, val value: ByteArray = ByteArray(0)) : GattOpResult()
        data class Failed(
            val reason: String,
            val statusCode: Int? = null,
            val callbackName: String? = null,
            val isTimeout: Boolean = false,
        ) : GattOpResult()
    }

    private val mutex = Mutex()

    @Volatile
    private var activeOpName: String? = null

    @Volatile
    private var activeDeferred: CompletableDeferred<GattOpResult>? = null

    val isBusy: Boolean
        get() = activeDeferred != null

    val currentOperationName: String?
        get() = activeOpName

    suspend fun enqueue(
        opName: String,
        timeoutMs: Long = defaultTimeoutMs,
        initiate: () -> Boolean,
    ): GattOpResult = enqueueStatus(opName, timeoutMs) {
        if (initiate()) 0 else -10
    }

    suspend fun enqueueStatus(
        opName: String,
        timeoutMs: Long = defaultTimeoutMs,
        initiateWithStatus: () -> Int,
    ): GattOpResult = mutex.withLock {
        val deferred = CompletableDeferred<GattOpResult>()
        activeOpName = opName
        activeDeferred = deferred

        val initStatus = try {
            initiateWithStatus()
        } catch (t: Throwable) {
            activeDeferred = null
            activeOpName = null
            return@withLock GattOpResult.Failed(
                reason = "Exception during $opName: ${t.message}",
                callbackName = opName,
            )
        }

        if (initStatus != 0) {
            activeDeferred = null
            activeOpName = null
            return@withLock GattOpResult.Failed(
                reason = "Android GATT rejected $opName: ${describeGattStatus(initStatus)}",
                statusCode = initStatus,
                callbackName = opName,
            )
        }

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        if (result == null) {
            activeDeferred = null
            activeOpName = null
            GattOpResult.Failed(
                reason = "GATT operation timeout after ${timeoutMs}ms in $opName",
                callbackName = opName,
                isTimeout = true,
            )
        } else {
            result
        }
    }

    fun completeActive(
        expectedOpPrefix: String? = null,
        status: Int,
        value: ByteArray = ByteArray(0),
        callbackName: String,
    ): Boolean {
        val deferred = activeDeferred ?: return false
        val opName = activeOpName
        if (expectedOpPrefix != null && opName != null && !opName.startsWith(expectedOpPrefix)) {
            return false
        }
        activeDeferred = null
        activeOpName = null
        if (status == 0) {
            deferred.complete(GattOpResult.Success(status = status, value = value))
        } else {
            deferred.complete(
                GattOpResult.Failed(
                    reason = "$callbackName failed with ${describeGattStatus(status)}",
                    statusCode = status,
                    callbackName = callbackName,
                )
            )
        }
        return true
    }

    fun cancelAll(reason: String) {
        val deferred = activeDeferred
        activeDeferred = null
        activeOpName = null
        deferred?.complete(GattOpResult.Failed(reason = reason, callbackName = "DISCONNECT"))
    }
}
