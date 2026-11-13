package io.nekohasekai.sfa.backend.connections

import io.nekohasekai.sfa.backend.connections.ConnectionStore.Companion.CLOSED_MAX_AGE_MILLIS
import io.nekohasekai.sfa.backend.connections.ConnectionStore.Companion.EVENT_CLOSED
import io.nekohasekai.sfa.backend.connections.ConnectionStore.Companion.EVENT_NEW
import io.nekohasekai.sfa.backend.connections.ConnectionStore.Companion.EVENT_UPDATE
import io.nekohasekai.sfa.backend.model.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStoreTest {
    private fun connection(id: String, outboundType: String = "direct", closedAt: Long? = null) = Connection(
        id = id, inbound = "tun-in", inboundType = "tun", ipVersion = 4, network = "tcp",
        source = "10.0.0.2:1000", destination = "1.1.1.1:443", domain = "", displayDestination = "1.1.1.1:443",
        protocolName = "tls", user = "", fromOutbound = "", createdAt = 1, closedAt = closedAt,
        upload = 5, download = 7, uploadTotal = 100, downloadTotal = 200, rule = "final",
        outbound = "proxy", outboundType = outboundType, chain = listOf("proxy"), processInfo = null,
    )

    @Test fun updatesApplyDeltasAndKeepUnchangedRows() {
        val store = ConnectionStore()
        store.apply(EVENT_NEW, "a", connection("a"), 0, 0, 0)
        store.apply(EVENT_NEW, "b", connection("b"), 0, 0, 0)
        val before = store.snapshot()
        assertTrue(store.apply(EVENT_UPDATE, "a", null, 10, 20, 0))
        val after = store.snapshot()
        val a = after.first { it.id == "a" }
        assertEquals(10L, a.upload)
        assertEquals(20L, a.download)
        assertEquals(110L, a.uploadTotal)
        assertEquals(220L, a.downloadTotal)
        assertSame(before.first { it.id == "b" }, after.first { it.id == "b" })
    }

    @Test fun closedMarksRowAndZeroesRates() {
        val store = ConnectionStore()
        store.apply(EVENT_NEW, "a", connection("a"), 0, 0, 0)
        store.apply(EVENT_CLOSED, "a", null, 0, 0, 1_000)
        val a = store.snapshot().single()
        assertEquals(1_000L, a.closedAt)
        assertEquals(0L, a.upload)
        assertEquals(0L, a.download)
        assertEquals(100L, a.uploadTotal)
        // A closed snapshot for an unknown id is stored as is.
        assertTrue(store.apply(EVENT_CLOSED, "c", connection("c"), 0, 0, 2_000))
        assertEquals(2_000L, store.snapshot().first { it.id == "c" }.closedAt)
    }

    @Test fun dnsLookupsAreCountedNotListed() {
        val store = ConnectionStore()
        store.apply(EVENT_NEW, "a", connection("a"), 0, 0, 0)
        store.apply(EVENT_NEW, "d", connection("d", outboundType = "dns"), 0, 0, 0)
        store.apply(EVENT_NEW, "p", connection("p").copy(destination = "8.8.8.8:53", protocolName = "dns"), 0, 0, 0)
        assertEquals(2, store.dnsCount())
        assertEquals(listOf("a", "p"), store.snapshot().map { it.id })
    }

    @Test fun eventsForUnknownIdsAreIgnored() {
        val store = ConnectionStore()
        assertFalse(store.apply(EVENT_UPDATE, "x", null, 1, 1, 0))
        assertFalse(store.apply(EVENT_CLOSED, "x", null, 0, 0, 1))
        assertFalse(store.apply(EVENT_NEW, "x", null, 0, 0, 0))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun dnsIsHiddenAndOldClosedRowsAreEvicted() {
        val store = ConnectionStore()
        store.apply(EVENT_NEW, "dns", connection("dns", outboundType = "dns"), 0, 0, 0)
        store.apply(EVENT_NEW, "a", connection("a"), 0, 0, 0)
        store.apply(EVENT_CLOSED, "a", null, 0, 0, 1_000)
        assertEquals(listOf("a"), store.snapshot().map { it.id })
        store.evictClosed(1_000 + CLOSED_MAX_AGE_MILLIS)
        assertEquals(1, store.snapshot().size)
        store.evictClosed(1_001 + CLOSED_MAX_AGE_MILLIS)
        assertTrue(store.snapshot().isEmpty())
        store.clear()
        assertNull(store.snapshot().firstOrNull())
    }
}
