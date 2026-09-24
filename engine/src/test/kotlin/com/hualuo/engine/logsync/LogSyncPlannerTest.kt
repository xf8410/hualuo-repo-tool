package com.hualuo.engine.logsync

import com.hualuo.engine.store.SessionStore
import com.hualuo.engine.store.StoredMsg
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSyncPlannerTest {
    private fun tempStore(): SessionStore =
        SessionStore(Files.createTempDirectory("hualuo-logsync").toFile())

    private fun msg(text: String) = StoredMsg(StoredMsg.ROLE_USER, text, 1_700_000_000_000L)

    @Test fun freshCheckpointMeansEverythingDirty() {
        val store = tempStore()
        val a = store.create("m")
        val b = store.create("m")
        val dirty = LogSyncPlanner.planDirty(store, emptyMap())
        assertEquals(listOf(a, b).sorted(), dirty)
    }

    @Test fun unchangedSessionsAreSkipped() {
        val store = tempStore()
        val id = store.create("m")
        store.append(id, msg("第一条"))
        val file = store.pathOf(id)
        val checkpoint = mapOf(id to LogSyncPlanner.checkpointValueFor(file))
        assertTrue(LogSyncPlanner.planDirty(store, checkpoint).isEmpty())
    }

    @Test fun appendedLineMakesSessionDirtyAgain() {
        val store = tempStore()
        val id = store.create("m")
        store.append(id, msg("第一条"))
        val before = LogSyncPlanner.checkpointValueFor(store.pathOf(id))
        store.append(id, msg("第二条"))
        val dirty = LogSyncPlanner.planDirty(store, mapOf(id to before))
        assertEquals(listOf(id), dirty)
    }

    @Test fun checkpointRoundTripKeepsEntries() {
        val original = mapOf("s1-1" to "10:20", "s2-2" to "30:40")
        val decoded = LogSyncPlanner.decodeCheckpoint(LogSyncPlanner.encodeCheckpoint(original))
        assertEquals(original, decoded)
    }

    @Test fun badCheckpointFallsBackToEmpty() {
        assertTrue(LogSyncPlanner.decodeCheckpoint("not json").isEmpty())
        assertTrue(LogSyncPlanner.decodeCheckpoint(null).isEmpty())
    }

    @Test fun repoDefaultsToPrivateLogsRepo() {
        assertEquals("xf8410/hualuo-logs", LogSyncPlanner.normalizeRepo(null))
        assertEquals("xf8410/hualuo-logs", LogSyncPlanner.normalizeRepo(""))
        assertEquals("xf8410/hualuo-logs", LogSyncPlanner.normalizeRepo("xf8410/hualuo-logs"))
    }

    @Test fun branchDefaultsToMain() {
        assertEquals("main", LogSyncPlanner.normalizeBranch(null))
        assertEquals("main", LogSyncPlanner.normalizeBranch("  "))
        assertEquals("main", LogSyncPlanner.normalizeBranch("main"))
    }

    @Test fun prefixHasLogsRootAndTrailingSlash() {
        val prefix = LogSyncPlanner.buildPrefix(1_700_000_000_000L)
        assertTrue(prefix.startsWith("logs/"))
        assertTrue(prefix.endsWith("/"))
    }
}
