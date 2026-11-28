package ai.opencode.core.ui

import ai.opencode.core.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Inter (variable) for UI text and OpenMono for code, as the desktop. Both OFL-1.1; the APK
 * carries the licence texts (`assets/licenses/`).
 */
object OpenCodeFonts {
    /**
     * Inter at the desktop's weights: Normal → 440 (`--font-weight-regular`), Medium → 530
     * (`--font-weight-medium`), SemiBold → 600, Bold → 670 (`--font-weight-bold`).
     */
    @OptIn(ExperimentalTextApi::class)
    val system: FontFamily = FontFamily(
        listOf(
            FontWeight.Light to 340,
            FontWeight.Normal to 440,
            FontWeight.Medium to 530,
            FontWeight.SemiBold to 600,
            FontWeight.Bold to 670,
            FontWeight.ExtraBold to 760,
        ).map { (weight, axis) ->
            Font(
                R.font.inter,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
            )
        },
    )

    /**
     * OpenMono for code, tool output and paths (desktop `--font-family-mono`). It has two weights:
     * Regular also serves Medium and Bold also serves SemiBold, so no weight is synthesized.
     * Italic is the system's slant of these upright faces.
     */
    val monospace: FontFamily = FontFamily(
        Font(R.font.openmono_regular, FontWeight.Normal),
        Font(R.font.openmono_regular, FontWeight.Medium),
        Font(R.font.openmono_bold, FontWeight.SemiBold),
        Font(R.font.openmono_bold, FontWeight.Bold),
    )
}

/** The text styles under Dynamic Type names, sized to the desktop scale. */
object OpenCodeTextStyles {
    private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
        fontFamily = OpenCodeFonts.system,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = if (tracking == 0.0) TextUnit.Unspecified else tracking.sp,
    )

    // The desktop scale (13/14/16/20 px at weights 440/530/670) stretched for a phone held
    // closer than a monitor: body text one step up (15 sp), titles medium-weight and tightly
    // tracked like the desktop's `text-16/20-medium`. Everything stays in sp, so font scaling works.

    /** `.largeTitle`: 28/34 medium. */
    val largeTitle = style(28, 34, FontWeight.Medium, -0.4)
    /** `.title` (title1): 24/30 medium. */
    val title1 = style(24, 30, FontWeight.Medium, -0.32)
    /** `.title2`: 20/26 medium (desktop `text-20-medium`). */
    val title2 = style(20, 26, FontWeight.Medium, -0.32)
    /** `.title3`: 18/24 medium. */
    val title3 = style(18, 24, FontWeight.Medium, -0.16)
    /** `.headline`: 16/22 medium (desktop `text-16-medium`). */
    val headline = style(16, 22, FontWeight.Medium, -0.16)
    /** `.body`: 15/23 (desktop markdown 14 px / 160 %). */
    val body = style(15, 23)
    /** `.callout`: 15/21. */
    val callout = style(15, 21)
    /** `.subheadline`: 14/20 (desktop `text-14`). */
    val subheadline = style(14, 20)
    /** `.footnote`: 13/18 (desktop `text-12`, 13 px). */
    val footnote = style(13, 18)
    /** `.caption` (caption1): 12/16. */
    val caption1 = style(12, 16)
    /** `.caption2`: 11/13. */
    val caption2 = style(11, 13)
}

/** Material 3 type roles mapped onto the text styles. */
val OpenCodeTypography: Typography = with(OpenCodeTextStyles) {
    Typography(
        displayLarge = largeTitle,
        displayMedium = largeTitle,
        displaySmall = title1,
        headlineLarge = title1,
        headlineMedium = title2,
        headlineSmall = title3,
        titleLarge = title3,
        titleMedium = headline,
        titleSmall = subheadline.copy(fontWeight = FontWeight.Medium),
        bodyLarge = body,
        bodyMedium = subheadline,
        bodySmall = footnote,
        labelLarge = subheadline.copy(fontWeight = FontWeight.Medium),
        labelMedium = caption1.copy(fontWeight = FontWeight.Medium),
        labelSmall = caption2.copy(fontWeight = FontWeight.Medium),
    )
}
