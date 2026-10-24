package fr.eande.witmotion

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RegisteredImuStoreTest {

    private lateinit var store: RegisteredImuStore
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        store = RegisteredImuStore(prefs)
    }

    @Test
    fun `initially empty`() {
        assertThat(store.getAll()).isEmpty()
    }

    @Test
    fun `register and retrieve device`() {
        store.register("AA:BB:CC:DD:EE:FF", "WT901")
        val devices = store.getAll()
        assertThat(devices).hasSize(1)
        assertThat(devices[0].address).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(devices[0].name).isEqualTo("WT901")
    }

    @Test
    fun `register same device twice does not duplicate`() {
        store.register("AA:BB:CC:DD:EE:FF", "WT901")
        store.register("AA:BB:CC:DD:EE:FF", "WT901-renamed")
        val devices = store.getAll()
        assertThat(devices).hasSize(1)
        assertThat(devices[0].name).isEqualTo("WT901-renamed")
    }

    @Test
    fun `remove device`() {
        store.register("AA:BB:CC:DD:EE:FF", "WT901")
        store.register("11:22:33:44:55:66", "WT902")
        store.remove("AA:BB:CC:DD:EE:FF")
        val devices = store.getAll()
        assertThat(devices).hasSize(1)
        assertThat(devices[0].address).isEqualTo("11:22:33:44:55:66")
    }

    @Test
    fun `isRegistered returns true for known device`() {
        store.register("AA:BB:CC:DD:EE:FF", "WT901")
        assertThat(store.isRegistered("AA:BB:CC:DD:EE:FF")).isTrue()
        assertThat(store.isRegistered("00:00:00:00:00:00")).isFalse()
    }

    @Test
    fun `names with JSON special characters round-trip`() {
        store.register("AA:BB:CC:DD:EE:FF", "WT \"bow\", {1}\\")
        assertThat(RegisteredImuStore(prefs).getAll().single().name).isEqualTo("WT \"bow\", {1}\\")
    }

    @Test
    fun `reads the format written by the previous Gson implementation`() {
        prefs.edit().putString(RegisteredImuStore.PREF_KEY, """[{"address":"AA:BB","name":"WT901"}]""").apply()
        assertThat(store.getAll()).containsExactly(RegisteredImuStore.RegisteredDevice("AA:BB", "WT901"))
    }

    @Test
    fun `unreadable or obfuscated stored entries are dropped instead of crashing`() {
        prefs.edit().putString(RegisteredImuStore.PREF_KEY, """[{"a":"AA:BB","b":"WT901"},{"address":"CC:DD","name":"WT2"}]""").apply()
        assertThat(store.getAll()).containsExactly(RegisteredImuStore.RegisteredDevice("CC:DD", "WT2"))

        prefs.edit().putString(RegisteredImuStore.PREF_KEY, "not json").apply()
        assertThat(store.getAll()).isEmpty()
    }

    @Test
    fun `data persists across instances`() {
        store.register("AA:BB:CC:DD:EE:FF", "WT901")
        val store2 = RegisteredImuStore(prefs)
        assertThat(store2.getAll()).hasSize(1)
    }

    @Test
    fun `concurrent registrations all survive`() {
        val threads = List(4) { t ->
            Thread { repeat(100) { store.register("AA:$t:$it", "imu") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(store.getAll()).hasSize(400)
    }
}
