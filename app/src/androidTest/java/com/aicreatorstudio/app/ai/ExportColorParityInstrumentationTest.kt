package com.aicreatorstudio.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side color parity checks: the paths of
 * [ColorGradingMath.fullExportMatrix] that go through real
 * `android.graphics.ColorMatrix` (effect presets + the Lumetri grade)
 * must break identity — i.e. the exported MP4 actually carries the
 * user's filter/grade, not just the preview.
 */
@RunWith(AndroidJUnit4::class)
class ExportColorParityInstrumentationTest {

    @Test
    fun famousEffectBreaksIdentity() {
        val m = ColorGradingMath.fullExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NOIR,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f
        )
        assertFalse(ColorGradingMath.isIdentityMatrix(m))
    }

    @Test
    fun lumetriGradeBreaksIdentity() {
        val grading = ColorGrading.Identity.copy(temperature = 0.4f, vibrance = 0.3f)
        val m = ColorGradingMath.fullExportMatrix(
            grading = grading,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f
        )
        assertFalse(ColorGradingMath.isIdentityMatrix(m))
    }

    @Test
    fun allNeutralStaysIdentityOnDevice() {
        val m = ColorGradingMath.fullExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f
        )
        assertTrue(ColorGradingMath.isIdentityMatrix(m))
    }
}
