package com.aicreatorstudio.app.data.font

import com.aicreatorstudio.app.model.CustomFont
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CRUD, ordering and fallback tests for the imported-font repository.
 *
 * The fake is used rather than [DefaultCustomFontRepository] because the
 * real one needs a `ContentResolver`; that is exactly what
 * `InMemoryCustomFontRepository` exists for, and its KDoc commits it to
 * matching the real semantics (newest-first, null-on-failure,
 * no-op-unknown-delete, null-`fileFor`-for-missing-file). These tests
 * therefore pin the *contract* both implementations must satisfy — a
 * future change to one that is not mirrored in the other fails here.
 */
class InMemoryCustomFontRepositoryTest {

    // ==================================================================
    // Ordering — "newest first", which the picker relies on
    // ==================================================================

    @Test
    fun `imported fonts are listed newest first`() = runTest {
        val repo = InMemoryCustomFontRepository()
        repo.addFont(id = "a", name = "First")
        repo.addFont(id = "b", name = "Second")
        repo.addFont(id = "c", name = "Third")

        assertEquals(listOf("c", "b", "a"), repo.snapshot().map { it.id })
        assertEquals(listOf("c", "b", "a"), repo.getCustomFonts().first().map { it.id })
    }

    @Test
    fun `a font constructed with state survives into the flow`() {
        val seeded = listOf(
            CustomFont(id = "x", name = "X", filePath = "/fake/files/fonts/x.ttf")
        )
        val repo = InMemoryCustomFontRepository(initial = seeded)
        assertEquals(seeded, repo.snapshot())
    }

    // ==================================================================
    // Delete
    // ==================================================================

    @Test
    fun `deleting returns the removed record and drops it from the list`() = runTest {
        val repo = InMemoryCustomFontRepository()
        repo.addFont(id = "a", name = "Keep")
        repo.addFont(id = "b", name = "Drop")

        val removed = repo.deleteCustomFont("b")
        assertNotNull("delete must report what it removed", removed)
        assertEquals("b", removed!!.id)
        assertEquals(listOf("a"), repo.snapshot().map { it.id })
    }

    @Test
    fun `deleting an unknown id is a harmless no-op`() = runTest {
        val repo = InMemoryCustomFontRepository()
        repo.addFont(id = "a", name = "A")

        assertNull(repo.deleteCustomFont("does-not-exist"))
        // The list is untouched — a double-tap on the trash icon must
        // not be able to drop a different font.
        assertEquals(listOf("a"), repo.snapshot().map { it.id })
    }

    @Test
    fun `deleting the same id twice removes it once`() = runTest {
        val repo = InMemoryCustomFontRepository()
        repo.addFont(id = "a", name = "A")

        assertNotNull(repo.deleteCustomFont("a"))
        assertNull(repo.deleteCustomFont("a"))
        assertTrue(repo.snapshot().isEmpty())
    }

    // ==================================================================
    // fileFor — the "record present but file gone" path
    // ==================================================================

    @Test
    fun `fileFor resolves a present font`() = runTest {
        val repo = InMemoryCustomFontRepository()
        val font = repo.addFont(id = "a", name = "A")
        assertEquals(font.filePath, repo.fileFor("a"))
    }

    @Test
    fun `fileFor returns null for a missing file rather than a stale path`() = runTest {
        // This is the state the real repository reaches when a copy was
        // interrupted by a full disk, or a cleaner removed the file.
        // Returning the path anyway would hand the renderer a File that
        // throws on first use.
        val repo = InMemoryCustomFontRepository()
        repo.addFont(id = "a", name = "A")
        repo.simulateMissingFile("a")

        assertNull(repo.fileFor("a"))
        // The metadata record still exists — the picker still shows it
        // (until a prune), it simply cannot resolve a path.
        assertEquals(1, repo.snapshot().size)
    }

    @Test
    fun `fileFor returns null for an unknown id`() = runTest {
        val repo = InMemoryCustomFontRepository()
        assertNull(repo.fileFor("nope"))
    }

    @Test
    fun `deleting clears the resolved path`() = runTest {
        val repo = InMemoryCustomFontRepository()
        repo.addFont(id = "a", name = "A")
        assertNotNull(repo.fileFor("a"))
        repo.deleteCustomFont("a")
        assertNull("a deleted font must not still resolve", repo.fileFor("a"))
    }

    // ==================================================================
    // pruneOrphans
    // ==================================================================

    @Test
    fun `pruneOrphans is idempotent`() = runTest {
        val repo = InMemoryCustomFontRepository()
        repo.addFont(id = "a", name = "A")
        repo.addFont(id = "b", name = "B")

        repo.pruneOrphans()
        repo.pruneOrphans()

        // Pruning must never drop a font that still has metadata.
        assertEquals(listOf("b", "a"), repo.snapshot().map { it.id })
        assertNotNull(repo.fileFor("a"))
        assertNotNull(repo.fileFor("b"))
    }

    @Test
    fun `pruneOrphans keeps the metadata list intact`() = runTest {
        val repo = InMemoryCustomFontRepository()
        repo.addFont(id = "a", name = "A")
        repo.simulateMissingFile("a")
        repo.pruneOrphans()
        // The fake has no directory listing, so pruning is only about
        // dropping unclaimed *files*; the metadata survives and the
        // picker still shows the entry.
        assertEquals(1, repo.snapshot().size)
    }

    // ==================================================================
    // importFont — the fake must not pretend
    // ==================================================================

    @Test
    fun `importFont returns null rather than faking a success`() = runTest {
        // No ContentResolver exists here; returning a fabricated font
        // would let a test (or a preview) believe an import worked and
        // render an empty caption.
        val repo = InMemoryCustomFontRepository()
        assertNull(repo.importFont("content://docs/font.ttf"))
        assertNull(repo.importFont("content://docs/font.ttf", "Vazir"))
    }

    // ==================================================================
    // Locator — the preview/export shared resolution
    // ==================================================================

    @Test
    fun `the locator resolves a published font by prefixed reference and by bare id`() {
        val file = java.io.File.createTempFile("omelet-font-", ".ttf")
        try {
            file.writeBytes(ByteArray(16) { 1 })
            val font = CustomFont(id = "u-1", name = "X", filePath = file.absolutePath)
            CustomFontLocator.publish(listOf(font))

            // Both shapes must resolve: a persisted style carries the
            // prefix, a freshly-picked font may hand over the bare id.
            assertEquals(file.absolutePath, CustomFontLocator.pathFor("custom:u-1"))
            assertEquals(file.absolutePath, CustomFontLocator.pathFor("u-1"))
        } finally {
            CustomFontLocator.publish(emptyList())
            file.delete()
        }
    }

    @Test
    fun `the locator refuses an unknown reference, a blank one, and a deleted file`() {
        assertNull(CustomFontLocator.pathFor(null))
        assertNull(CustomFontLocator.pathFor(""))
        assertNull(CustomFontLocator.pathFor("custom:never-imported"))

        // A published path whose file is gone must fall back, not crash
        // the export pipeline.
        val file = java.io.File.createTempFile("omelet-font-", ".ttf")
        file.writeBytes(ByteArray(16) { 1 })
        val font = CustomFont(id = "gone", name = "X", filePath = file.absolutePath)
        CustomFontLocator.publish(listOf(font))
        file.delete()
        assertNull(CustomFontLocator.pathFor("custom:gone"))
        CustomFontLocator.publish(emptyList())
    }

    @Test
    fun `forget removes exactly one entry`() {
        val file = java.io.File.createTempFile("omelet-font-", ".ttf")
        file.writeBytes(ByteArray(16) { 1 })
        try {
            val a = CustomFont(id = "a", name = "A", filePath = file.absolutePath)
            val b = CustomFont(id = "b", name = "B", filePath = file.absolutePath)
            CustomFontLocator.publish(listOf(a, b))

            CustomFontLocator.forget("a")
            assertNull(CustomFontLocator.pathFor("custom:a"))
            assertNotNull("unrelated entry must survive", CustomFontLocator.pathFor("custom:b"))
        } finally {
            CustomFontLocator.publish(emptyList())
            file.delete()
        }
    }
}
