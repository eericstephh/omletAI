package com.aicreatorstudio.app.util

/**
 * Generic wrapper for operations that can succeed, fail, or be in flight —
 * useful for both future video-processing operations (export, import) and
 * AI operations (generation, analysis) that take time and can fail.
 */
sealed class UiResult<out T> {
    data object Loading : UiResult<Nothing>()
    data class Success<T>(val data: T) : UiResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : UiResult<Nothing>()
}
