package com.aicreatorstudio.app.data.subtitle

import android.content.Context
import com.aicreatorstudio.app.model.template.CatalogState
import com.aicreatorstudio.app.model.template.TemplateCatalogCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 3 — Hilt entry point for the JSON template catalog.
 *
 * Deliberately thin: all caching, threading and fallback policy
 * lives in the JVM-tested [TemplateCatalogCache]. This class only
 * supplies the asset reader (`assets/subtitle_templates.json`).
 * A missing/unreadable asset resolves to [CatalogState.Empty], so
 * the UI layer always gets a usable (possibly empty) state.
 *
 * NOT VERIFIED on JVM (needs Android assets) — covered by
 * device/manual QA in later phases; the cache contract it
 * delegates to is fully unit-tested.
 */
@Singleton
class JsonTemplateCatalog @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val cache = TemplateCatalogCache()

    /**
     * Load (once, cached, IO-safe) and return the catalog state.
     * Safe to call from the main thread; never throws.
     */
    suspend fun load(): CatalogState = cache.load { readAsset() }

    /** Non-blocking snapshot: null until the first [load] finishes. */
    fun peek(): CatalogState? = cache.peek()

    private fun readAsset(): String? = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    }.getOrNull()

    companion object {
        const val ASSET_NAME: String = "subtitle_templates.json"
    }
}
