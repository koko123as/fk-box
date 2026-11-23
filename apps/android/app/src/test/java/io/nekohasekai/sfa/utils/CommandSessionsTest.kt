package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.utils.CommandSessions.Stream
import io.nekohasekai.sfa.utils.CommandSessions.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSessionsTest {
    @Test fun decodesEveryStreamOfThePlan() {
        // As mobile-ffi sessions::plan serializes it (crates/mobile-ffi/src/sessions.rs).
        val plan = decodeSessionPlan(Json.parseToJsonElement(
            """{"clashMode":{"connect":true,"remoteServerId":null},"status":{"connect":false,"remoteServerId":null},
               "groups":{"connect":true,"remoteServerId":null},"connections":{"connect":false,"remoteServerId":null},
               "log":{"connect":true,"remoteServerId":null,"localConfig":3},"remoteMonitor":{"connect":false,"remoteServerId":null}}""",
        ))
        assertEquals(Stream.entries.toSet(), plan.keys)
        assertEquals(Target(connect = true), plan[Stream.ClashMode])
        assertEquals(Target(connect = true, localConfig = 3), plan[Stream.Log])
        assertEquals(Target(), plan[Stream.Status])
    }

    @Test fun aRemoteTargetCarriesItsServer() {
        val plan = decodeSessionPlan(Json.parseToJsonElement("""{"groups":{"connect":true,"remoteServerId":7}}"""))
        assertEquals(Target(connect = true, remoteServerId = 7), plan[Stream.Groups])
        assertEquals(Target(), plan[Stream.RemoteMonitor])
    }

    @Test fun anUnreadablePlanConnectsNothing() {
        assertTrue(decodeSessionPlan(JsonNull).isEmpty())
    }
}
