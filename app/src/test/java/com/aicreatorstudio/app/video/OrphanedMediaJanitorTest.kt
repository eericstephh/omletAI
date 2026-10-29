package com.aicreatorstudio.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrphanedMediaJanitorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileUri(file: File): String = file.toURI().toString()

    @Test
    fun `deletes an unreferenced file inside the media dir`() {
        val mediaDir = tmp.newFolder("media")
        val orphan = File(mediaDir, "abc.mp4").apply { writeText("x") }

        val deleted = OrphanedMediaJanitor.deleteUnreferenced(
            mediaDir = mediaDir,
            candidateUris = listOf(fileUri(orphan)),
            referencedUris = emptySet()
        )

        assertEquals(1, deleted)
        assertFalse(orphan.exists())
    }

    @Test
    fun `keeps a file still referenced by a surviving project`() {
        val mediaDir = tmp.newFolder("media")
        val referenced = File(mediaDir, "keep.mp4").apply { writeText("x") }

        val deleted = OrphanedMediaJanitor.deleteUnreferenced(
            mediaDir = mediaDir,
            candidateUris = listOf(fileUri(referenced)),
            referencedUris = setOf(fileUri(referenced))
        )

        assertEquals(0, deleted)
        assertTrue(referenced.exists())
    }

    @Test
    fun `never touches content uris`() {
        val mediaDir = tmp.newFolder("media")
        val decoy = File(mediaDir, "decoy.mp4").apply { writeText("x") }

        val deleted = OrphanedMediaJanitor.deleteUnreferenced(
            mediaDir = mediaDir,
            // A content:// candidate can never be inside our media dir, but
            // the janitor must not even try to resolve it as a file path.
            candidateUris = listOf("content://media/external/video/1"),
            referencedUris = setOf(fileUri(decoy))
        )

        assertEquals(0, deleted)
        assertTrue(decoy.exists())
    }

    @Test
    fun `never touches files outside the media dir even when unreferenced`() {
        val mediaDir = tmp.newFolder("media")
        val outside = tmp.newFile("elsewhere.mp4")

        val deleted = OrphanedMediaJanitor.deleteUnreferenced(
            mediaDir = mediaDir,
            candidateUris = listOf(fileUri(outside)),
            referencedUris = emptySet()
        )

        assertEquals(0, deleted)
        assertTrue(outside.exists())
    }

    @Test
    fun `malformed uris are skipped without throwing`() {
        val mediaDir = tmp.newFolder("media")

        val deleted = OrphanedMediaJanitor.deleteUnreferenced(
            mediaDir = mediaDir,
            candidateUris = listOf("not a uri at all", "file://", ""),
            referencedUris = emptySet()
        )

        assertEquals(0, deleted)
    }

    @Test
    fun `dotdot escape attempts are contained by canonical comparison`() {
        val mediaDir = tmp.newFolder("media")
        val outside = tmp.newFile("escape.mp4")
        // A clip whose stored URI walks out of mediaDir via .. must NOT be
        // treated as inside it.
        val escapeUri = fileUri(mediaDir) + "../escape.mp4"

        val deleted = OrphanedMediaJanitor.deleteUnreferenced(
            mediaDir = mediaDir,
            candidateUris = listOf(escapeUri),
            referencedUris = emptySet()
        )

        assertEquals(0, deleted)
        assertTrue(outside.exists())
    }
}
