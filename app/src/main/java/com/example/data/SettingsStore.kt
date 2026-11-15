package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.battery.BatteryMode
import com.example.crypto.EncryptionManager
import com.example.crypto.LocalStorageCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.cyberDataStore by preferencesDataStore(name = "cyber_link_settings")

class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val IDENTITY_PRIV_B64 = stringPreferencesKey("identity_priv_b64")
        val IDENTITY_PUB_B64 = stringPreferencesKey("identity_pub_b64")
        val LOCAL_STORAGE_KEY_B64 = stringPreferencesKey("local_storage_key_b64")
        val CONSUMED_PAIRING_TOKENS = stringPreferencesKey("consumed_pairing_tokens")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val BATTERY_MODE = stringPreferencesKey("battery_mode")
        val MESH_RELAY_OPT_IN = booleanPreferencesKey("mesh_relay_opt_in")
        val GLOBAL_LOCATION_KILL_SWITCH = booleanPreferencesKey("global_location_kill_switch")
    }

    val batteryModeFlow: Flow<BatteryMode> = context.cyberDataStore.data.map { prefs ->
        runCatching {
            BatteryMode.valueOf(prefs[Keys.BATTERY_MODE] ?: BatteryMode.NORMAL.name)
        }.getOrDefault(BatteryMode.NORMAL)
    }

    val meshRelayOptInFlow: Flow<Boolean> = context.cyberDataStore.data.map { prefs ->
        prefs[Keys.MESH_RELAY_OPT_IN] ?: false
    }

    val displayNameFlow: Flow<String> = context.cyberDataStore.data.map { prefs ->
        prefs[Keys.DISPLAY_NAME] ?: android.os.Build.MODEL ?: "CyberNode"
    }

    val globalLocationKillSwitchFlow: Flow<Boolean> = context.cyberDataStore.data.map { prefs ->
        prefs[Keys.GLOBAL_LOCATION_KILL_SWITCH] ?: false
    }

    suspend fun loadOrCreateEncryptionManager(): EncryptionManager {
        val prefs = context.cyberDataStore.data.first()
        val privB64 = prefs[Keys.IDENTITY_PRIV_B64]
        val pubB64 = prefs[Keys.IDENTITY_PUB_B64]
        if (!privB64.isNullOrBlank() && !pubB64.isNullOrBlank()) {
            val loaded = runCatching {
                EncryptionManager.fromEncodedKeys(
                    EncryptionManager.decodeBase64(privB64),
                    EncryptionManager.decodeBase64(pubB64),
                )
            }.getOrNull()
            if (loaded != null) return loaded
        }

        val created = EncryptionManager()
        context.cyberDataStore.edit { editPrefs ->
            editPrefs[Keys.IDENTITY_PRIV_B64] = EncryptionManager.encodeBase64(created.exportPrivateKeyPkcs8())
            editPrefs[Keys.IDENTITY_PUB_B64] = EncryptionManager.encodeBase64(created.exportPublicKeyX509())
        }
        return created
    }

    suspend fun loadOrCreateLocalStorageCipher(): LocalStorageCipher {
        val prefs = context.cyberDataStore.data.first()
        val keyB64 = prefs[Keys.LOCAL_STORAGE_KEY_B64]
        if (!keyB64.isNullOrBlank()) {
            val decoded = runCatching { EncryptionManager.decodeBase64(keyB64) }.getOrNull()
            if (decoded != null && decoded.size == 32) {
                return LocalStorageCipher(decoded)
            }
        }
        val newKey = LocalStorageCipher.generateRandomKeyBytes()
        context.cyberDataStore.edit { editPrefs ->
            editPrefs[Keys.LOCAL_STORAGE_KEY_B64] = EncryptionManager.encodeBase64(newKey)
        }
        return LocalStorageCipher(newKey)
    }

    suspend fun getConsumedPairingTokens(nowMs: Long = System.currentTimeMillis()): Map<String, Long> {
        val raw = context.cyberDataStore.data.first()[Keys.CONSUMED_PAIRING_TOKENS] ?: return emptyMap()
        val map = runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
        return map.filterValues { it > nowMs }
    }

    suspend fun isPairingTokenConsumed(token: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val map = getConsumedPairingTokens(nowMs)
        return map.containsKey(token)
    }

    suspend fun consumePairingToken(token: String, expiresAtMs: Long, nowMs: Long = System.currentTimeMillis()) {
        context.cyberDataStore.edit { editPrefs ->
            val raw = editPrefs[Keys.CONSUMED_PAIRING_TOKENS]
            val current = raw?.let {
                runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrDefault(emptyMap())
            }.orEmpty().filterValues { it > nowMs }.toMutableMap()
            current[token] = maxOf(expiresAtMs, nowMs + 60_000L)
            editPrefs[Keys.CONSUMED_PAIRING_TOKENS] = json.encodeToString(current)
        }
    }

    suspend fun setBatteryMode(mode: BatteryMode) {
        context.cyberDataStore.edit { it[Keys.BATTERY_MODE] = mode.name }
    }

    suspend fun setMeshRelayOptIn(enabled: Boolean) {
        context.cyberDataStore.edit { it[Keys.MESH_RELAY_OPT_IN] = enabled }
    }

    suspend fun setDisplayName(name: String) {
        context.cyberDataStore.edit { it[Keys.DISPLAY_NAME] = name.trim().ifEmpty { "CyberNode" } }
    }

    suspend fun setGlobalLocationKillSwitch(active: Boolean) {
        context.cyberDataStore.edit { it[Keys.GLOBAL_LOCATION_KILL_SWITCH] = active }
    }
}
