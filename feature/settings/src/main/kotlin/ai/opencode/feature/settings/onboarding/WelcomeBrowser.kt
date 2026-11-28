package ai.opencode.feature.settings.onboarding

import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.feature.settings.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun mono(size: Float, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = OpenCodeFonts.monospace, fontSize = size.sp, fontWeight = weight, letterSpacing = 0.sp)

/**
 * The OpenCode website (`opencode.ai`) in dark mode, as the laptop's browser shows it on
 * `localhost:3000`: warm dark canvas, architectural grid rails, the pixel wordmark, monospace hero,
 * install command, and the platform CTA that [grow] expands from a modest desktop chip (0) into a
 * full-width “Download for Android” button (1).
 */
@Composable
internal fun WelcomeSitePage(modifier: Modifier = Modifier, grow: Double = 1.0, sheen: Double = 0.0) {
    Box(
        modifier
            .size(WelcomeSitePage.size.width.dp, WelcomeSitePage.size.height.dp)
            .background(WelcomeSitePage.canvas)
            .drawWithContent {
                drawRect(
                    Brush.radialGradient(
                        listOf(WelcomeInk.hex(0x241D1D), WelcomeSitePage.canvas.copy(alpha = 0f)),
                        center = Offset(size.width * 0.5f, size.height * 0.16f),
                        radius = max(size.width, size.height) * 0.62f,
                    ),
                )
                // `opencode.ai`'s vertical container lines framing the centred column.
                val inset = WelcomeSitePage.RAIL_INSET.dp.toPx()
                val rail = 0.75.dp.toPx()
                drawRect(WelcomeSitePage.grid, Offset(inset, 0f), Size(rail, size.height))
                drawRect(WelcomeSitePage.grid, Offset(size.width - inset - rail, 0f), Size(rail, size.height))
                drawContent()
                drawRect(Color.White.copy(alpha = 0.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(rail))
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The nav bar.
            Row(Modifier.width(WelcomeSitePage.COLUMN_WIDTH.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                WelcomeWordmark(Modifier.size(72.dp, 13.dp))
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (link in listOf(stringResource(R.string.settings_onboarding_site_docs), "Zen", "GitHub")) Text(link, style = mono(8.5f), color = WelcomeSitePage.muted)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.75.dp).background(WelcomeSitePage.grid))
            Hero(grow, sheen)
        }
    }
}

@Composable
private fun Hero(grow: Double, sheen: Double) {
    Column(Modifier.width(WelcomeSitePage.COLUMN_WIDTH.dp).padding(top = 11.dp)) {
        Text(
            stringResource(R.string.settings_onboarding_site_headline),
            style = mono(13.5f, FontWeight.Bold).copy(letterSpacing = (-0.25).sp, lineHeight = 18.sp),
            color = WelcomeSitePage.strong,
        )
        Text(stringResource(R.string.settings_onboarding_site_subhead), style = mono(7.5f), color = WelcomeSitePage.muted, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 4.dp))
        InstallCard(Modifier.padding(top = 9.dp))
        Box(Modifier.padding(top = 9.dp).height(30.dp), contentAlignment = Alignment.CenterStart) { CtaButton(grow, sheen) }
    }
}

@Composable
private fun InstallCard(modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier
            .width(WelcomeSitePage.COLUMN_WIDTH.dp)
            .background(WelcomeSitePage.card, shape)
            .border(0.75.dp, WelcomeSitePage.border, shape)
            .clip(shape),
    ) {
        Row(
            Modifier.fillMaxWidth().height(16.dp).background(WelcomeSitePage.tabStrip).padding(horizontal = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(top = 3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("curl", style = mono(7.5f), color = WelcomeSitePage.strong)
                Box(Modifier.size(18.dp, 1.25.dp).background(WelcomeSitePage.strong))
            }
            Text("bun", style = mono(7.5f), color = WelcomeSitePage.muted)
            Text("brew", style = mono(7.5f), color = WelcomeSitePage.muted)
        }
        Box(Modifier.fillMaxWidth().height(0.75.dp).background(WelcomeSitePage.grid))
        Row(Modifier.fillMaxWidth().height(23.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$ ", style = mono(8f), color = WelcomeInk.hex(0x615959))
            Text("curl -fsSL ", style = mono(8f), color = WelcomeSitePage.muted)
            Text("opencode.ai/install", style = mono(8f), color = WelcomeSitePage.strong)
            Text(" | bash", style = mono(8f), color = WelcomeSitePage.muted)
            Spacer(Modifier.weight(1f))
            OpenCodeIcon("square.on.square", null, size = 7.5.dp, tint = WelcomeSitePage.muted)
        }
    }
}

@Composable
private fun CtaButton(grow: Double, sheen: Double) {
    val g = grow.toFloat()
    val clamped = g.coerceIn(0f, 1f)
    val width = max(96f, 118 + (WelcomeSitePage.COLUMN_WIDTH - 118) * g)
    val height = max(18f, 21 + 8 * min(g, 1.08f))
    val radius = 4 + 2 * clamped
    val beforeOut = max(0f, 1 - clamped * 2.6f)
    val afterIn = ((clamped - 0.22f) / 0.58f).coerceIn(0f, 1f)
    val glow = max(0.0, sin(PI * clamped)) * (1 - clamped * 0.35)
    val shape = RoundedCornerShape(radius.dp)
    Box(Modifier.size(width.dp, height.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxSize().background(WelcomeSitePage.card, shape).border(0.75.dp, WelcomeSitePage.border, shape))
        Box(
            Modifier
                .fillMaxSize()
                .shadow((8 * glow).dp, shape, clip = false, ambientColor = WelcomeInk.hex(0xEEF5B2), spotColor = WelcomeInk.hex(0xEEF5B2, 0.28 * glow))
                .alpha(clamped)
                .background(WelcomeSitePage.strong, shape),
        )
        Row(Modifier.fillMaxWidth().alpha(beforeOut), horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon("desktopcomputer", null, size = 8.dp, tint = WelcomeSitePage.muted)
            Text(stringResource(R.string.settings_onboarding_site_platforms), style = mono(8f), color = WelcomeSitePage.muted, maxLines = 1, softWrap = false)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).alpha(afterIn), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon("platter.filled.top.iphone", null, size = 10.5.dp, tint = WelcomeSitePage.canvas)
            Text(stringResource(R.string.settings_onboarding_site_download_android), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = WelcomeSitePage.canvas, maxLines = 1, softWrap = false)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.settings_onboarding_site_couch_ready),
                style = mono(7.5f),
                color = WelcomeInk.hex(0x3E3737),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.background(WelcomeSitePage.canvas.copy(alpha = 0.09f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (sheen > 0.01 && sheen < 0.99) {
            Canvas(Modifier.fillMaxSize().clip(shape)) {
                val w = size.width * 0.42f
                val left = (sheen.toFloat() * 1.45f - 0.45f) * size.width
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = (0.55 * (1 - sheen)).toFloat()), Color.Transparent), startX = left, endX = left + w),
                    topLeft = Offset(left, 0f),
                    size = Size(w, size.height),
                )
            }
        }
    }
}

internal object WelcomeSitePage {
    val size = Size(330f, 206f)
    const val RAIL_INSET = 18f
    const val COLUMN_WIDTH = 274f

    val canvas = WelcomeInk.hex(0x131010)
    val card = WelcomeInk.hex(0x1A1616)
    val tabStrip = WelcomeInk.hex(0x211C1C)
    val grid = WelcomeInk.hex(0x2E2828)
    val border = WelcomeInk.hex(0x362F2F)
    val strong = WelcomeInk.hex(0xF2ECEC)
    val muted = WelcomeInk.hex(0x827A7A)
}

/**
 * The `opencode` ornate pixel wordmark (`39 × 7` unit grid from `logo-ornate-dark.svg`): `open` in
 * warm silver, `code` in ivory, with dark inner blocks.
 */
@Composable
internal fun WelcomeWordmark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val ux = size.width / 39
        val uy = size.height / 7
        fun fill(cells: List<IntArray>, color: Color) {
            for ((x, y, w, h) in cells) drawRect(color, Offset(x * ux, y * uy), Size(w * ux + 0.15f, h * uy + 0.15f))
        }
        fill(WORDMARK_SHADES, WelcomeInk.hex(0x4B4646))
        fill(WORDMARK_OPEN, WelcomeInk.hex(0xB7B1B1))
        fill(WORDMARK_CODE, WelcomeInk.hex(0xF1ECEC))
    }
}

private fun cells(vararg values: Int): List<IntArray> = values.toList().chunked(4).map { it.toIntArray() }

private val WORDMARK_SHADES = cells(1, 3, 2, 2, 6, 3, 2, 2, 11, 4, 3, 1, 16, 3, 2, 3, 21, 3, 3, 2, 26, 3, 2, 2, 31, 3, 2, 2, 36, 4, 3, 1)
private val WORDMARK_OPEN = cells(
    0, 1, 4, 1, 0, 5, 4, 1, 0, 2, 1, 3, 3, 2, 1, 3, // o
    5, 1, 4, 1, 5, 2, 1, 5, 8, 2, 1, 3, 6, 5, 3, 1, // p
    10, 1, 4, 1, 10, 2, 1, 4, 13, 2, 1, 2, 11, 3, 2, 1, 11, 5, 3, 1, // e
    15, 1, 3, 1, 15, 2, 1, 4, 18, 2, 1, 4, // n
)
private val WORDMARK_CODE = cells(
    20, 1, 4, 1, 20, 2, 1, 4, 21, 5, 3, 1, // c
    25, 1, 4, 1, 25, 5, 4, 1, 25, 2, 1, 3, 28, 2, 1, 3, // o
    33, 0, 1, 1, 30, 1, 4, 1, 30, 5, 4, 1, 30, 2, 1, 3, 33, 2, 1, 3, // d
    35, 1, 4, 1, 35, 2, 1, 4, 38, 2, 1, 2, 36, 3, 2, 1, 36, 5, 3, 1, // e
)

/**
 * The browser OpenCode drives on the laptop: a dark window over the app with the `opencode.ai` dev
 * page, the edit's reload bar, and the capture flash.
 */
@Composable
internal fun WelcomeBrowserWindow(frame: WelcomeFrame, targets: WelcomeTargets?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.5.dp)
    Column(
        modifier
            .size(WelcomeBrowserWindow.size.width.dp, WelcomeBrowserWindow.size.height.dp)
            .shadow(22.dp, shape, ambientColor = Color.Black.copy(alpha = 0.62f), spotColor = Color.Black.copy(alpha = 0.62f))
            .background(WelcomeInk.hex(0x201C1C), shape)
            .clip(shape)
            .border(0.75.dp, Color.White.copy(alpha = (0.16 + 0.55 * frame.flash).toFloat()), shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(WelcomeBrowserWindow.TOOLBAR.dp)
                .drawWithContent {
                    drawContent()
                    val line = 0.75.dp.toPx()
                    drawRect(WelcomeInk.hex(0x322B2B), Offset(0f, size.height - line), Size(size.width, line))
                    // The edit's hot-reload bar, cooling once the page settles.
                    val bar = 1.5.dp.toPx()
                    val width = size.width * frame.reload.toFloat()
                    if (width > 0) {
                        drawRect(
                            Brush.horizontalGradient(listOf(WelcomeInk.hex(0x3679FD), WelcomeInk.hex(0x9BC0FF)), startX = 0f, endX = width),
                            topLeft = Offset(0f, size.height - bar),
                            size = Size(width, bar),
                            alpha = frame.reloading.toFloat(),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.fillMaxSize().padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.5.dp), verticalAlignment = Alignment.CenterVertically) {
                for (color in listOf(0xFF5F57L, 0xFEBC2EL, 0x28C840L)) Box(Modifier.size(7.5.dp).background(WelcomeInk.hex(color), CircleShape))
                OpenCodeIcon("chevron.left", null, Modifier.padding(start = 8.dp), size = 8.5.dp, tint = WelcomeInk.hex(0x968E8E))
                OpenCodeIcon("chevron.right", null, size = 8.5.dp, tint = WelcomeInk.hex(0x555050))
            }
            val pill = RoundedCornerShape(4.5.dp)
            Row(
                Modifier
                    .size(148.dp, 17.dp)
                    .background(WelcomeInk.hex(0x141111), pill)
                    .border(0.5.dp, Color.White.copy(alpha = 0.07f), pill)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(4.5.dp).background(WelcomeInk.hex(0x28C840), CircleShape))
                Text("localhost:3000", style = mono(8.5f), color = WelcomeInk.hex(0xD2CACA), maxLines = 1, softWrap = false)
            }
        }
        Box(Modifier.welcomeTarget(WelcomeTarget.BrowserPage, targets)) {
            WelcomeSitePage(grow = frame.buttonGrow, sheen = frame.buttonSheen)
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = (frame.flash * 0.72).toFloat())))
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = (frame.browserRecede * 0.38).toFloat())))
        }
    }
}

internal object WelcomeBrowserWindow {
    const val TOOLBAR = 26f
    val size: Size get() = Size(WelcomeSitePage.size.width, WelcomeSitePage.size.height + TOOLBAR)
}

/**
 * The browser's screenshot as the agent's tool sends it back: the edited dark-mode OpenCode page.
 * Compose draws the page itself wherever the screenshot shows (the carrier, the phone's tile), so
 * nothing is rasterised, and this keeps the attachment's name and pixel size.
 */
internal object WelcomeScreenshot {
    const val NAME = "opencode.png"
    val pixelWidth: Int get() = (WelcomeSitePage.size.width * 3).toInt()
    val pixelHeight: Int get() = (WelcomeSitePage.size.height * 3).toInt()

    /** The screenshot filling its bounds, as an image scaled to fit them. */
    @Composable
    fun Image(modifier: Modifier = Modifier) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
            val scale = min(maxWidth.value / WelcomeSitePage.size.width, maxHeight.value / WelcomeSitePage.size.height)
            WelcomeScaled(WelcomeSitePage.size, scale) { WelcomeSitePage(grow = 1.0) }
        }
    }
}
