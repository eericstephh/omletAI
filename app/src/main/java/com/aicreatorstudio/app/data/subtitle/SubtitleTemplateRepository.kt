package com.aicreatorstudio.app.data.subtitle

import android.content.Context
import android.util.Log
import com.aicreatorstudio.app.model.BuiltInSubtitleTemplates
import com.aicreatorstudio.app.model.SubtitleTemplate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-09-11 — repository for «زیرنویس فارسی» templates.
 *
 * Serves the 8 built-in templates ([BuiltInSubtitleTemplates]) plus
 * user-defined ones persisted as one JSON file each under
 * `filesDir/subtitle-templates/`. Deleting a built-in is impossible
 * (they are code-defined); deleting a user template removes its file.
 *
 * Extensible by design: a future sync/store only has to swap the
 * file layer — the model and the UI contract stay untouched.
 */
@Singleton
class SubtitleTemplateRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Built-ins first (brief order), then user templates by mtime. */
    fun all(): List<SubtitleTemplate> {
        val user = runCatching {
            dir.listFiles { f -> f.isFile && f.extension == "json" }
                ?.mapNotNull { SubtitleTemplate.fromJson(it.readText()) }
                ?.sortedBy { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())
        return BuiltInSubtitleTemplates.ALL + user
    }

    fun byId(id: String): SubtitleTemplate? =
        BuiltInSubtitleTemplates.byId(id) ?: loadUser(id)

    /** Save (insert or update) a user template. Returns the stored file. */
    fun saveUserTemplate(template: SubtitleTemplate): Result<SubtitleTemplate> {
        if (template.builtIn) {
            return Result.failure(IllegalArgumentException("قالب داخلی قابل بازنویسی نیست"))
        }
        return runCatching {
            dir.mkdirs()
            val file = File(dir, "${template.id}.json")
            file.writeText(template.toJson())
            template
        }.onFailure { Log.w(TAG, "saveUserTemplate failed", it) }
    }

    /** Idempotent delete of a user template. Built-ins are protected. */
    fun deleteUserTemplate(id: String): Boolean {
        if (BuiltInSubtitleTemplates.byId(id) != null) return false
        return runCatching { File(dir, "$id.json").delete() }.getOrDefault(false)
    }

    private fun loadUser(id: String): SubtitleTemplate? = runCatching {
        val file = File(dir, "$id.json")
        if (file.exists()) SubtitleTemplate.fromJson(file.readText()) else null
    }.getOrNull()

    companion object {
        private const val TAG = "SubtitleTemplateRepo"
        const val DIR_NAME = "subtitle-templates"
    }
}
