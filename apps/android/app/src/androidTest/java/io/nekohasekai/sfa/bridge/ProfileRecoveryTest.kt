package io.nekohasekai.sfa.bridge

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.vendor.PackageQueryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ProfileRecoveryTest {
    @Test fun localProfileTimesFollowSuccessfulContentChanges() = runBlocking(Dispatchers.IO) {
        Settings.dataStore.initialize()
        val oldSelection = Settings.selectedProfile
        var failApply = false
        val profiles = BoxProfiles { recover -> if (failApply && !recover) error("Apply failed") }
        val createdAfter = System.currentTimeMillis()
        val id = profiles.save(mapOf("name" to "Local timestamp fixture", "content" to "{}"))
        try {
            Settings.selectedProfile = id
            assertTrue(profiles.get(id).typed.lastUpdated.time >= createdAfter)
            val profile = profiles.get(id)
            profile.typed.lastUpdated = Date(0) // A local profile created before timestamps were recorded.
            ProfileManager.update(profile)
            assertEquals(0L, ProfileManager.get(id)!!.toWire()["updatedAt"])
            val content = """{"log":{"level":"warn"}}"""
            profiles.writeContent(id, content, BoxProfiles.revision("{}"))
            val savedAt = profiles.get(id).typed.lastUpdated.time
            assertTrue(savedAt >= createdAfter)
            assertEquals(savedAt, ProfileManager.get(id)!!.toWire()["updatedAt"])
            profiles.writeContent(id, content, BoxProfiles.revision(content))
            profiles.save(mapOf("id" to id, "name" to "Renamed local profile"))
            assertEquals(savedAt, profiles.get(id).typed.lastUpdated.time)
            val beforeFailure = profiles.get(id)
            beforeFailure.typed.lastUpdated = Date(1_700_000_000_000L)
            ProfileManager.update(beforeFailure)
            failApply = true
            assertNotNull(runCatching {
                profiles.writeContent(id, "{}", BoxProfiles.revision(content))
            }.exceptionOrNull())
            assertEquals(content, profiles.read(id))
            assertEquals(beforeFailure.typed.lastUpdated.time, profiles.get(id).typed.lastUpdated.time)
        } finally {
            failApply = false
            profiles.delete(id)
            Settings.selectedProfile = oldSelection
            Settings.dataStore.flush()
        }
    }

    @Test fun queryModesReachTheClientStore() = runBlocking(Dispatchers.IO) {
        Settings.dataStore.initialize()
        val before = Settings.perAppProxyPackageQueryMode
        try {
            for (mode in listOf(Settings.PACKAGE_QUERY_MODE_ROOT, Settings.PACKAGE_QUERY_MODE_SHIZUKU)) {
                PackageQueryManager.setQueryMode(mode)
                Settings.dataStore.flush()
                // Read the client store itself, not the data store's in-memory cache.
                val stored = CoreStore.call("settings.all").jsonObject[SettingsKey.PER_APP_PROXY_PACKAGE_QUERY_MODE]
                assertEquals(mode, stored?.jsonObject?.get("v")?.jsonPrimitive?.content)
                assertEquals(mode, PackageQueryManager.queryMode.value)
            }
        } finally {
            PackageQueryManager.setQueryMode(before)
            Settings.dataStore.flush()
        }
    }

    @Test fun failedManualUpdateRestoresContentTimestampAndService() {
        run {
            SubscriptionServer(UNIMPORTABLE).use { server ->
                withRunningProfile(server.url) { activity, profiles, id ->
                    val before = profiles.get(id).typed.lastUpdated.time
                    assertTrue(runCatching { BoxProfiles.validate(server.content) }.isFailure)
                    val failure = runCatching { profiles.update(id) }.exceptionOrNull()
                    assertNotNull("A failed subscription must report its reload failure", failure)
                    assertEquals(1, server.requests.get())
                    assertEquals("{}", profiles.read(id))
                    assertEquals(before, profiles.get(id).typed.lastUpdated.time)
                    withTimeout(15_000) { activity.serviceStatus.first { it == Status.Started } }
                    assertEquals(0, failure!!.suppressed.size)
                }
            }
        }
    }

    @Test fun failedAutomaticUpdateRestoresDataAndRequestsRetry() {
        run {
            SubscriptionServer(UNIMPORTABLE).use { server ->
                withRunningProfile(server.url) { activity, profiles, id ->
                    assertTrue(runCatching { BoxProfiles.validate(server.content) }.isFailure)
                    val profile = profiles.get(id)
                    profile.typed.autoUpdate = true
                    profile.typed.lastUpdated = Date(0)
                    ProfileManager.update(profile)
                    assertFalse(UpdateProfileWork.updateProfiles(activity.applicationContext))
                    assertEquals(1, server.requests.get())
                    assertEquals("{}", profiles.read(id))
                    assertEquals(0L, profiles.get(id).typed.lastUpdated.time)
                    assertEquals(id, Settings.selectedProfile)
                    withTimeout(15_000) { activity.serviceStatus.first { it == Status.Started } }
                }
            }
        }
    }

    @Test fun stoppedAndUnselectedUpdatesPreserveServiceState() {
        SubscriptionServer("""{"log":{"disabled":true}}""").use { server ->
            withRunningProfile(server.url) { activity, profiles, id ->
                val other = profiles.save(mapOf("name" to "Unselected subscription", "content" to "{}", "url" to server.url))
                try {
                    profiles.update(other)
                    assertEquals(server.content, profiles.read(other))
                    assertEquals(id, Settings.selectedProfile)
                    assertEquals(Status.Started, activity.serviceStatus.value)

                    server.content = """{"log":{"level":"warn"}}"""
                    val unselected = profiles.get(other)
                    unselected.typed.autoUpdate = true
                    unselected.typed.lastUpdated = Date(0)
                    ProfileManager.update(unselected)
                    assertTrue(UpdateProfileWork.updateProfiles(activity.applicationContext))
                    assertEquals(server.content, profiles.read(other))
                    assertEquals(id, Settings.selectedProfile)
                    assertEquals(Status.Started, activity.serviceStatus.value)
                    val updatedUnselected = profiles.get(other)
                    updatedUnselected.typed.autoUpdate = false
                    ProfileManager.update(updatedUnselected)

                    withContext(Dispatchers.Main) { activity.stopService() }
                    withTimeout(15_000) { activity.serviceStatus.first { it == Status.Stopped } }
                    profiles.update(id)
                    assertEquals(server.content, profiles.read(id))
                    assertEquals(Status.Stopped, activity.serviceStatus.value)

                    server.content = "{}"
                    val profile = profiles.get(id)
                    profile.typed.autoUpdate = true
                    profile.typed.lastUpdated = Date(0)
                    ProfileManager.update(profile)
                    assertTrue(UpdateProfileWork.updateProfiles(activity.applicationContext))
                    assertEquals("{}", profiles.read(id))
                    assertTrue(profiles.get(id).typed.lastUpdated.time > 0L)
                    assertEquals(Status.Stopped, activity.serviceStatus.value)
                    profiles.select(other) { activity.serviceStatus.value }
                    assertEquals(other, Settings.selectedProfile)
                    assertEquals(Status.Stopped, activity.serviceStatus.value)
                } finally {
                    profiles.delete(other)
                }
            }
        }
    }

    private fun withRunningProfile(
        url: String = "",
        block: suspend (MainActivity, BoxProfiles, Long) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS"),
            ).use { it.readBytes() }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            runBlocking(Dispatchers.IO) {
                Settings.dataStore.initialize()
                val oldSelection = Settings.selectedProfile
                val oldMode = Settings.serviceMode
                val profiles = BoxProfiles { recover -> withContext(Dispatchers.Main) { activity.applyProfile(recover = recover) } }
                val id = profiles.save(mapOf("name" to "Subscription recovery fixture", "content" to "{}", "url" to url, "updatedAt" to 0L))
                try {
                    Settings.selectedProfile = id
                    Settings.dataStore.flush()
                    withContext(Dispatchers.Main) { activity.applyProfile(recover = true) }
                    assertEquals(Status.Started, activity.serviceStatus.value)
                    block(activity, profiles, id)
                } finally {
                    withContext(Dispatchers.Main) { activity.stopService() }
                    withTimeout(15_000) { activity.serviceStatus.first { it == Status.Stopped } }
                    ProfileManager.get(id)?.let { ProfileManager.delete(it); File(it.typed.path).delete() }
                    Settings.selectedProfile = oldSelection
                    Settings.serviceMode = oldMode
                    Settings.dataStore.flush()
                    UpdateProfileWork.reconfigureUpdater()
                }
            }
        }
    }

    private companion object {
        /**
         * Content the single-core brain refuses (crates/client-core/src/config/brain/mod.rs
         * import): its check fails the save, or the start when a restore skips the check.
         * Stage 6 replaced the occupied-port fixtures: the brain generates the inbounds
         * itself (TUN only, no local listener), so a profile's listen port no longer fails.
         */
        const val UNIMPORTABLE = "not a subscription ::"
    }

    private class SubscriptionServer(@Volatile var content: String) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/profile.json"
        val requests = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "subscription-fixture") {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val bytes = content.toByteArray(Charsets.UTF_8)
                        requests.incrementAndGet()
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                        output.flush()
                    }
                } catch (failure: Exception) {
                    if (!server.isClosed) throw failure
                }
            }
        }
        override fun close() {
            server.close()
            worker.join(5_000)
        }
    }

    @Test fun failedRestartRollsBackConfigurationAndFullBackup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS"),
            ).use { it.readBytes() }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            runBlocking(Dispatchers.IO) {
                Settings.dataStore.initialize()
                val oldSelection = Settings.selectedProfile
                val profiles = BoxProfiles { recover -> withContext(Dispatchers.Main) { activity.applyProfile(restart = true, recover = recover) } }
                val id = profiles.save(mapOf("name" to "Recovery fixture", "content" to "{}"))
                val oldPreferences = CoreStore.call("preferences.get")
                try {
                    Settings.selectedProfile = id
                    Settings.dataStore.flush()
                    withContext(Dispatchers.Main) { activity.applyProfile(restart = true, recover = true) }
                    assertEquals(Status.Started, activity.serviceStatus.value)
                    run {
                        val invalidAtRuntime = UNIMPORTABLE
                        val failure = runCatching { profiles.writeContent(id, invalidAtRuntime, BoxProfiles.revision("{}")) }.exceptionOrNull()
                        assertNotNull("Applying a configuration must wait for startup failure", failure)
                        assertEquals("{}", profiles.read(id))
                        assertEquals(Status.Started, activity.serviceStatus.value)
                        assertEquals(0, failure!!.suppressed.size)

                        val backups = BoxBackups(activity, {},
                            { restart, recover -> withContext(Dispatchers.Main) { activity.applyProfile(restart, recover) } }, {}, { _, _ -> })
                        val backup = Json.parseToJsonElement(backups.create()).jsonObject
                        val entries = backup.getValue("profiles").jsonArray.map { element ->
                            val entry = element.jsonObject
                            if (entry.getValue("id").jsonPrimitive.long == id) {
                                JsonObject(entry + mapOf("name" to JsonPrimitive("Broken restored profile"), "content" to JsonPrimitive(invalidAtRuntime)))
                            } else entry
                        }
                        val changed = JsonObject(backup + mapOf("profiles" to JsonArray(entries), "preferences" to buildJsonObject { put("theme", "dark") }))
                        val staged = backups.read(changed.toString(), "all")
                        val restoreFailure = runCatching {
                            backups.restore(staged["token"] as String, "all", "Override")
                        }.exceptionOrNull()
                        assertNotNull("A failed restored service must fail the restore", restoreFailure)
                        assertEquals("Recovery fixture", profiles.get(id).name)
                        assertEquals("{}", profiles.read(id))
                        assertEquals(id, Settings.selectedProfile)
                        assertEquals(oldPreferences, CoreStore.call("preferences.get"))
                        assertEquals(Status.Started, activity.serviceStatus.value)
                        assertEquals(0, restoreFailure!!.suppressed.size)
                    }
                } finally {
                    withContext(Dispatchers.Main) { activity.stopService() }
                    withTimeout(15000) { activity.serviceStatus.first { it == Status.Stopped } }
                    val fixture = ProfileManager.get(id)
                    if (fixture != null) { ProfileManager.delete(fixture); File(fixture.typed.path).delete() }
                    Settings.selectedProfile = oldSelection
                    CoreStore.call("preferences.replace") { put("values", oldPreferences) }
                    Settings.dataStore.flush()
                }
            }
        }
    }
}
