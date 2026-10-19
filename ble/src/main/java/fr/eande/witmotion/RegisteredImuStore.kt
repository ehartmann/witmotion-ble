package fr.eande.witmotion

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists registered IMU devices in SharedPreferences.
 * Stored as a JSON array of {address, name} under [PREF_KEY].
 */
class RegisteredImuStore(private val prefs: SharedPreferences) {

    companion object {
        const val PREF_KEY = "registered_imu_devices"
        private const val TAG = "RegisteredImuStore"
    }

    data class RegisteredDevice(
        val address: String,
        val name: String
    )

    /**
     * Unreadable entries are dropped (the user re-registers the sensor): a release build that
     * stored this list with Gson under R8 may have written obfuscated field names.
     */
    fun getAll(): List<RegisteredDevice> {
        val json = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.optJSONObject(it) }.mapNotNull { o ->
                val address = o?.optString("address").orEmpty()
                if (address.isEmpty()) null else RegisteredDevice(address, o?.optString("name").orEmpty())
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Ignoring unreadable registered devices", e)
            emptyList()
        }
    }

    fun register(address: String, name: String) {
        val devices = getAll().toMutableList()
        devices.removeAll { it.address == address }
        devices.add(RegisteredDevice(address, name))
        save(devices)
    }

    fun remove(address: String) {
        val devices = getAll().toMutableList()
        devices.removeAll { it.address == address }
        save(devices)
    }

    fun isRegistered(address: String): Boolean =
        getAll().any { it.address == address }

    private fun save(devices: List<RegisteredDevice>) {
        val array = JSONArray(devices.map { JSONObject().put("address", it.address).put("name", it.name) })
        prefs.edit().putString(PREF_KEY, array.toString()).apply()
    }
}
