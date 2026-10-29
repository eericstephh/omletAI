package com.aicreatorstudio.app.model.template

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PHASE 3 — loaded view of the JSON template catalog.
 *
 * [source] tells the UI whether it shows the real shipped catalog
 * ([Source.ASSET]) or the safe empty fallback ([Source.FALLBACK]).
 */
data class CatalogState(
    val templates: List<JsonTemplate> = emptyList(),
    val schemaVersion: String = "",
    val source: Source = Source.FALLBACK,
    val skipped: Int = 0
) {
    enum class Source { ASSET, FALLBACK }

    fun byId(id: String): JsonTemplate? = templates.firstOrNull { it.id == id }

    companion object {
        val Empty: CatalogState = CatalogState()
    }
}

/**
 * PHASE 4 — picker-facing load status for the JSON catalog.
 * LOADING keeps the picker on a stable skeleton (no flicker);
 * READY carries mapped templates; EMPTY is the safe fallback.
 */
enum class JsonCatalogStatus { LOADING, READY, EMPTY }

/**
 * PHASE 3 — thread-safe, load-once cache over a catalog reader.
 *
 * Contract (this is what keeps the main thread safe):
 *  - [load] always runs the reader on [Dispatchers.IO], so callers
 *    may invoke it from anywhere — including the main thread —
 *    without doing I/O or JSON parsing on it;
 *  - the first completed load wins and is served forever after
 *    (no repeated asset reads, no repeated parsing);
 *  - concurrent in-flight loads collapse into ONE reader call
 *    ([Mutex] + double-checked [cached]);
 *  - ANY failure (null/blank payload, `IOException`, malformed
 *    JSON) resolves to [CatalogState.Empty] — the function never
 *    throws, so the app can never crash on a broken catalog.
 *
 * Pure Kotlin (the reader is injected) — fully JVM unit-tested.
 * The Android asset reader lives in
 * [com.aicreatorstudio.app.data.subtitle.JsonTemplateCatalog].
 */
class TemplateCatalogCache {

    private val mutex = Mutex()

    @Volatile
    private var cached: CatalogState? = null

    /** Non-blocking snapshot: null until the first [load] finishes. */
    fun peek(): CatalogState? = cached

    /**
     * Load (once) and return the catalog state. The reader runs on
     * IO; concurrent callers share the single in-flight read.
     */
    suspend fun load(reader: suspend () -> String?): CatalogState {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cached?.let { return@withLock it }
                val loaded = runCatching {
                    val json = reader()?.trim().orEmpty()
                    if (json.isEmpty()) {
                        CatalogState.Empty
                    } else {
                        val parsed = JsonTemplateParser.parse(json)
                        if (parsed.catalog.templates.isEmpty()) {
                            CatalogState.Empty
                        } else {
                            CatalogState(
                                templates = parsed.catalog.templates,
                                schemaVersion = parsed.catalog.schemaVersion,
                                source = CatalogState.Source.ASSET,
                                skipped = parsed.skipped.size
                            )
                        }
                    }
                }.getOrElse {
                    CatalogState.Empty
                }
                cached = loaded
                loaded
            }
        }
    }
}
