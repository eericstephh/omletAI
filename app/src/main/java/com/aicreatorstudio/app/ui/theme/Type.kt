package com.aicreatorstudio.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ============================================================================
// Premium Typography — Apple rhythm, Persian-safe metrics.
//
// NOTE (fa/AR): positive letterSpacing breaks Arabic-script joining and
// must stay 0 for Persian text. Tight tracking is applied ONLY to
// Latin/numeric styles via code that renders numbers; base styles keep 0.
//
// Line heights are generous (≈1.45× the font size for body, ≈1.25× for
// display) so that fontScale 1.3× does not clip Persian ascenders or the
// descenders/diacritics of Vazirmatn/Sahel/Shabnam.
//
// The scale follows a 1.15 ratio step from body 15sp, which keeps the
// hierarchy legible on compact 360dp phones without oversized headings.
// ============================================================================

private val SF = FontFamily.Default

val Typography = Typography(
    // Large titles — iOS Large Title style
    displayLarge = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // Headlines — used for screen titles
    headlineLarge = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),

    // Titles — section headers, card titles
    titleLarge = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    ),

    // Body — paragraph text, list rows.
    // Sizes are kept at the project's Persian-tested 17/15/13sp floor (one
    // step above the Material defaults): Arabic-script x-height is small, so
    // shrinking body text would cost more legibility than the extra density
    // is worth. Line heights are the 1.45–1.5× that survives fontScale 1.3.
    bodyLarge = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    // Labels — buttons, captions, tags
    labelLarge = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.sp
    )
)

/**
 * Extra, non-Material type roles the product needs. Kept here (not in
 * individual screens) so an eyebrow label or a timecode reads identically
 * everywhere instead of each screen hand-rolling a TextStyle.
 *
 * Persian note: [timecode] and [overline] are the only styles allowed a
 * letter-spacing, and they are only ever applied to Latin/numeric content.
 */
object OmeletType {
    /** Small caps-less section eyebrow above a title (Latin/numeric safe). */
    val overline = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

    /** Timecode / numeric readouts — tabular-ish, tight tracking. */
    val timecode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    )

    /** Dense metadata under a title. */
    val caption = TextStyle(
        fontFamily = SF,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp
    )
}
