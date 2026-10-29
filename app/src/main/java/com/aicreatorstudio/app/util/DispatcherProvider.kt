package com.aicreatorstudio.app.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Wraps [Dispatchers] so ViewModels/repositories depend on an interface
 * instead of the singletons directly, which makes them trivial to swap
 * for test dispatchers later (video processing and AI calls will both be
 * heavy, dispatcher-sensitive work).
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}
