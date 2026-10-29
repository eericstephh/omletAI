package com.aicreatorstudio.app.ai

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * Minimal [Context] used by the JVM unit tests in this package.
 *
 * The framework's [Context] is enormous — extending it directly
 * forces you to stub every abstract method (`getAssets`,
 * `getResources`, `getPackageManager`, `getClassLoader`, ...). The
 * conventional way to write a test [Context] in the AndroidX
 * world is to subclass [ContextWrapper] instead, which inherits
 * sensible no-op / throwing defaults for the entire framework
 * surface, and only override what your tests actually need.
 *
 * Today we override exactly one method: [getFilesDir], which
 * [ModelManager] reads to lay out the on-device model directory.
 * It points at a test-controlled temp dir so each test gets a
 * fresh, isolated storage area.
 *
 * The class is `internal` so it lives next to its tests but isn't
 * part of the public test surface.
 */
internal class TestContext internal constructor(
    private val tempDir: File
) : android.content.ContextWrapper(null) {

    init {
        // Android's getFilesDir() contract requires a real
        // directory; pre-create it so any test that calls the
        // getter before writing sees an existing path.
        tempDir.mkdirs()
    }

    override fun getFilesDir(): File = tempDir

    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = PACKAGE_NAME

    override fun getMainLooper(): android.os.Looper {
        // Robolectric / instrumented tests have a real main looper.
        // Plain JVM unit tests don't — return the current thread's
        // looper if it has one, otherwise prepare one for the
        // calling thread. Note: `Looper.prepare()` returns Unit;
        // `Looper.myLooper()` after the prepare call returns the
        // freshly-bound looper.
        val main = android.os.Looper.getMainLooper()
        if (main != null) return main
        val mine = android.os.Looper.myLooper()
        if (mine != null) return mine
        android.os.Looper.prepare()
        return android.os.Looper.myLooper()!!
    }

    companion object {
        const val PACKAGE_NAME: String = "com.aicreatorstudio.app.test"
    }
}
