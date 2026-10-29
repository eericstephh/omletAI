package com.aicreatorstudio.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicreatorstudio.app.ai.ocr.TesseractOcrEngine
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Real on-device Tesseract OCR inference test.**
 *
 * No mocks: the test installs the real tessdata packs (bundled
 * assets → filesDir, SHA-verified), creates a real [TessBaseAPI]
 * against them, renders Persian + English text into a bitmap, and
 * asserts the engine extracts the actual words with boxes and
 * confidence. Runs on every connected Android device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class TesseractOcrInstrumentationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun installPackFromAssets(spec: CatalogEntry) {
        val installer = AiModelInstaller(context)
        val outcome = runBlocking { installer.installWithManifest(spec) }
        assertTrue(
            "install of ${spec.id} must succeed (got ${outcome.failure}: ${outcome.detail})",
            outcome.failure == null
        )
    }

    /** Render solid dark text on white — the easiest case for OCR. */
    private fun textBitmap(text: String, w: Int = 640, h: Int = 200): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 72f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val x = w / 2f
        val y = h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, x, y, paint)
        return bmp
    }

    @Test
    fun realEnglishInference() {
        installPackFromAssets(AiModelCatalog.TESSERACT_ENG)
        val engine = TesseractOcrEngine(context, AiModelInstaller(context))
        val api = TessBaseAPI()
        val rc = api.init(engine.tessDataParentDir().absolutePath, "eng")
        assertTrue("Tesseract init failed rc=$rc", rc)
        try {
            val bmp = textBitmap("HELLO WORLD 123")
            val result = runBlocking { engine.analyzeBitmap(api, bmp) }
            bmp.recycle()
            assertTrue("expected at least one OCR line", result.lines.isNotEmpty())
            val text = result.lines.joinToString(" ") { it.text }
            assertTrue(
                "expected HELLO/123 in OCR output, got: $text",
                text.contains("HELLO", ignoreCase = true) && text.contains("123")
            )
            // Real bounding boxes: inside frame, sane geometry.
            val line = result.lines.first()
            assertTrue(line.right > line.left)
            assertTrue(line.bottom > line.top)
            assertTrue(line.left >= 0f && line.right <= 1f)
            // Real confidence: Tesseract on rendered text is near-certain.
            assertTrue("confidence ${line.confidence} too low", line.confidence > 0.5f)
        } finally {
            api.recycle()
        }
    }

    @Test
    fun realPersianInference() {
        installPackFromAssets(AiModelCatalog.TESSERACT_FAS)
        installPackFromAssets(AiModelCatalog.TESSERACT_ENG)
        val engine = TesseractOcrEngine(context, AiModelInstaller(context))
        val api = TessBaseAPI()
        val rc = api.init(engine.tessDataParentDir().absolutePath, "fas+eng")
        assertTrue("Tesseract init failed rc=$rc", rc)
        try {
            val bmp = textBitmap("سلام دنیا")
            val result = runBlocking { engine.analyzeBitmap(api, bmp) }
            bmp.recycle()
            assertTrue("expected at least one Persian OCR line", result.lines.isNotEmpty())
            val text = result.lines.joinToString(" ") { it.text }
            // The normalized output must contain Persian letters —
            // and specifically Persian ی/ک, not the Arabic forms.
            assertTrue("expected Persian text, got: $text", text.any { it in '؀'..'ۿ' })
            assertFalse("Arabic yeh must be normalized", text.contains('ي'))
        } finally {
            api.recycle()
        }
    }

    @Test
    fun engineIsAvailableAfterInstall() {
        installPackFromAssets(AiModelCatalog.TESSERACT_FAS)
        val engine = TesseractOcrEngine(context, AiModelInstaller(context))
        assertTrue(engine.isAvailable)
        assertFalse(engine.modelStatusText.contains("not installed", ignoreCase = true))
    }
}
