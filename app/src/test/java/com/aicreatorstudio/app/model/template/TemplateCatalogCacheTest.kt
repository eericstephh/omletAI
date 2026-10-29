package com.aicreatorstudio.app.model.template

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * PHASE 3 — the catalog cache: load-once, thread-safe, and
 * incapable of surfacing an error to callers.
 */
class TemplateCatalogCacheTest {

    private fun payload(ids: List<String> = listOf("a", "b")): String {
        val entries = ids.joinToString(",") { """{"id":"$it","name":{"fa":"$it","en":"$it"}}""" }
        return """{"schemaVersion":"3.0","templates":[$entries]}"""
    }

    @Test
    fun `sequential loads read once and return the same state`() = runTest {
        val cache = TemplateCatalogCache()
        val reads = AtomicInteger(0)
        suspend fun reader(): String? {
            reads.incrementAndGet()
            return payload()
        }
        val first = cache.load(::reader)
        val second = cache.load(::reader)
        assertEquals(1, reads.get())
        assertSame(first, second)
        assertEquals(CatalogState.Source.ASSET, first.source)
        assertEquals(listOf("a", "b"), first.templates.map { it.id })
        assertEquals("a", first.byId("a")?.nameFa)
    }

    @Test
    fun `concurrent loads collapse into a single read`() = runTest {
        val cache = TemplateCatalogCache()
        val reads = AtomicInteger(0)
        val deferred = (1..20).map {
            async {
                cache.load {
                    reads.incrementAndGet()
                    delay(10) // force overlap inside the critical section
                    payload(listOf("x"))
                }
            }
        }
        val results = deferred.map { it.await() }
        assertEquals(1, reads.get())
        results.forEach { assertSame(results[0], it) }
        assertEquals("x", results[0].templates.single().id)
    }

    @Test
    fun `throwing reader resolves to empty fallback without throwing`() = runTest {
        val cache = TemplateCatalogCache()
        val state = cache.load {
            throw IOException("disk gone")
        }
        assertEquals(CatalogState.Empty, state)
        assertTrue(state.templates.isEmpty())
        // ...and the fallback itself is cached (no retry storms).
        val again = cache.load {
            throw IOException("disk gone")
        }
        assertSame(state, again)
    }

    @Test
    fun `null blank and malformed payloads resolve to empty fallback`() = runTest {
        for (bad in listOf<String?>(null, "", "   ", "not json{{{", "{}")) {
            val state = TemplateCatalogCache().load { bad }
            assertEquals("payload $bad must fall back", CatalogState.Empty, state)
        }
    }

    @Test
    fun `valid json with zero templates resolves to empty fallback`() = runTest {
        val state = TemplateCatalogCache().load { """{"schemaVersion":"3.0","templates":[]}""" }
        assertEquals(CatalogState.Empty, state)
    }

    @Test
    fun `peek is null before load and set after`() = runTest {
        val cache = TemplateCatalogCache()
        assertNull(cache.peek())
        val loaded = cache.load { payload() }
        assertSame(loaded, cache.peek())
    }
}
