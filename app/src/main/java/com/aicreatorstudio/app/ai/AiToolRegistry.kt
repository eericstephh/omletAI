package com.aicreatorstudio.app.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt-provided lookup for the [AiToolEngine]s that ship with the
 * app. Mirrors the [SubtitleGenerator] shape: a priority-ordered list
 * plus a by-id accessor so the dock can resolve a tool by its
 * stable id (`"matting"`, `"autoframe"`, `"voiceclean"`).
 *
 * Each engine is a normal `@Singleton @Inject class` provided through
 * Hilt. The order they're passed into this constructor = the order
 * they're registered. Today there's exactly one engine per tool id,
 * but the list shape preserves room for a future v2 (e.g. a TFLite
 * MODNet backend for matting) to take priority over the v1 fallback.
 */
@Singleton
class AiToolRegistry @Inject constructor(
    engines: @JvmSuppressWildcards List<AiToolEngine>
) {

    private val engines: List<AiToolEngine> = engines

    /** All registered engines in priority order. */
    val all: List<AiToolEngine> get() = engines

    /**
     * Look up an engine by its [AiToolEngine.id]. Returns the first
     * engine whose id matches — priority order applies. Returns null
     * if no engine is registered for that id (which would indicate a
     * programming error; the dock and the ViewModel should both
     * assume the ids they use are registered).
     */
    fun byId(id: String): AiToolEngine? =
        engines.firstOrNull { it.id == id }

    /**
     * True when at least one engine is registered for [id] AND
     * that engine reports `isAvailable`. Used by the UI to decide
     * whether the dock chip should be tappable.
     */
    fun isReady(id: String): Boolean =
        byId(id)?.isAvailable == true
}