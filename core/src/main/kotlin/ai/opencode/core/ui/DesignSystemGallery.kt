package ai.opencode.core.ui

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionSpring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every design-system primitive on one scrolling page, hosted by `:devtools`. */
@Composable
fun DesignSystemGallery(
    modifier: Modifier = Modifier,
    initialTheme: AppTheme = AppTheme.System,
    initialAppearance: AppearanceColorScheme = AppearanceColorScheme.System,
) {
    var theme by remember { mutableStateOf(initialTheme) }
    var appearance by remember { mutableStateOf(initialAppearance) }
    OpenCodeTheme(theme = theme, appearance = appearance) {
        Surface(modifier.fillMaxSize(), color = OpenCodeTheme.colors.groupedCanvas) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(OpenCodeSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s24),
            ) {
                Text("Design System", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                ThemeSection(theme, appearance, onTheme = { theme = it }, onAppearance = { appearance = it })
                ColorsSection()
                TypeSection()
                ButtonsSection()
                NoticesSection()
                RollingNumberSection()
                MotionSection()
                IconsSection()
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s8)) {
        Text(title.uppercase(), style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
        Surface(
            Modifier.fillMaxWidth(),
            shape = OpenCodeShape.card,
            color = OpenCodeTheme.colors.groupedCard,
        ) {
            Column(Modifier.padding(OpenCodeSpacing.s16), verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s12)) {
                content()
            }
        }
    }
}

@Composable
private fun ThemeSection(
    theme: AppTheme,
    appearance: AppearanceColorScheme,
    onTheme: (AppTheme) -> Unit,
    onAppearance: (AppearanceColorScheme) -> Unit,
) = Section("Theme") {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s8)) {
        AppearanceColorScheme.entries.forEach { option ->
            FilterChip(selected = option == appearance, onClick = { onAppearance(option) }, label = { Text(option.title) })
        }
    }
    val isDark = OpenCodeTheme.colors.isDark
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s12)) {
        AppTheme.entries.forEach { option ->
            val variant = rememberThemeDefinition(option).variant(isDark)
            Column(
                Modifier
                    .clickable(role = Role.RadioButton, onClickLabel = option.title) { onTheme(option) }
                    .openCodeTapTarget(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .background(variant.swatchCanvas.color, OpenCodeShape.circle)
                        .border(if (option == theme) 3.dp else 1.dp, if (option == theme) OpenCodeTheme.colors.accent else variant.swatchRing.color, OpenCodeShape.circle),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(22.dp).background(variant.swatchAccent.color, OpenCodeShape.circle))
                }
                Spacer(Modifier.height(OpenCodeSpacing.s6))
                Text(option.title, style = OpenCodeTextStyles.caption1)
            }
        }
    }
}

@Composable
private fun ColorsSection() = Section("Colors") {
    val c = OpenCodeTheme.colors
    val s = MaterialTheme.colorScheme
    val swatches = listOf(
        "canvas" to c.canvas, "groupedCanvas" to c.groupedCanvas, "groupedCard" to c.groupedCard,
        "userBubble" to c.userBubble, "codeBand" to c.codeBand, "inlineCodeFill" to c.inlineCodeFill,
        "text" to c.text, "secondaryText" to c.secondaryText, "tertiaryText" to c.tertiaryText,
        "border" to c.border, "accent" to c.accent, "link" to c.link, "signal" to c.signal,
        "sendButton" to c.sendButtonBackground, "destructive" to c.destructive, "danger" to c.danger, "warning" to c.warning,
        "syntaxKeyword" to c.syntaxKeyword, "syntaxString" to c.syntaxString, "syntaxNumber" to c.syntaxNumber,
        "syntaxComment" to c.syntaxComment, "syntaxType" to c.syntaxType,
        "diffAddedFill" to c.diffAddedFill, "diffRemovedFill" to c.diffRemovedFill,
        "M3 primary" to s.primary, "M3 secondaryContainer" to s.secondaryContainer,
        "M3 surfaceContainer" to s.surfaceContainer, "M3 surfaceContainerHighest" to s.surfaceContainerHighest,
        "M3 outline" to s.outline, "M3 error" to s.error,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s8), verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s8)) {
        swatches.forEach { (name, color) -> Swatch(name, color) }
    }
    Text("Body text at tone 0.7, 0.92, 1.0", style = OpenCodeTextStyles.footnote, color = c.secondaryText)
    listOf(0.7, 0.92, 1.0).forEach { tone ->
        Text("The agent finished the refactor.", style = OpenCodeTextStyles.body, color = c.bodyText(tone))
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column(Modifier.width(96.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(color, OpenCodeShape.fill)
                .border(1.dp, OpenCodeTheme.colors.border, OpenCodeShape.fill),
        )
        Text(name, style = OpenCodeTextStyles.caption2, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
    }
}

@Composable
private fun TypeSection() = Section("Type (iOS text style → M3 role)") {
    val t = MaterialTheme.typography
    listOf(
        Triple("largeTitle", "displayLarge", t.displayLarge),
        Triple("title1", "headlineLarge", t.headlineLarge),
        Triple("title2", "headlineMedium", t.headlineMedium),
        Triple("title3", "titleLarge", t.titleLarge),
        Triple("headline", "titleMedium", t.titleMedium),
        Triple("body", "bodyLarge", t.bodyLarge),
        Triple("callout", "OpenCodeTextStyles.callout", OpenCodeTextStyles.callout),
        Triple("subheadline", "bodyMedium", t.bodyMedium),
        Triple("footnote", "bodySmall", t.bodySmall),
        Triple("caption1", "labelMedium", t.labelMedium),
        Triple("caption2", "labelSmall", t.labelSmall),
    ).forEach { (ios, m3, style) -> TypeRow(ios, m3, style) }
    Text("opencode", style = TextStyle(fontFamily = OpenCodeFonts.monospace, fontWeight = FontWeight.Bold, fontSize = 44.sp))
    Text("opencode pair --remote", style = OpenCodeTextStyles.callout.copy(fontFamily = OpenCodeFonts.monospace))
}

@Composable
private fun TypeRow(ios: String, m3: String, style: TextStyle) {
    Column {
        Text(ios.replaceFirstChar { it.uppercase() }, style = style, maxLines = 1)
        Text("$ios · $m3 · ${style.fontSize}", style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.tertiaryText)
    }
}

@Composable
private fun ButtonsSection() = Section("Buttons & tap targets") {
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s12)) {
        OpenCodeSecondaryButton("Back", onClick = {}, modifier = Modifier.weight(1f))
        OpenCodePrimaryButton("Next", onClick = {}, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s12)) {
        OpenCodeSecondaryButton("Cancel", onClick = {}, modifier = Modifier.weight(1f))
        OpenCodePrimaryButton("Delete", onClick = {}, modifier = Modifier.weight(1f), role = OpenCodeButtonRole.Destructive)
    }
    OpenCodePrimaryButton("Disabled", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s12), verticalAlignment = Alignment.CenterVertically) {
        OpenCodeIconButton(SFSymbol.Xmark, contentDescription = "Close", onClick = {})
        OpenCodeIconButton(SFSymbol.Plus, contentDescription = "Add", onClick = {})
        OpenCodeIconButton(SFSymbol.ArrowUp, contentDescription = "Send", onClick = {}, tint = OpenCodeTheme.colors.sendButtonBackground)
        OpenCodeIconButton(SFSymbol.Ellipsis, contentDescription = "More", onClick = {}, style = OpenCodeIconButtonStyle.Standard)
        Box(
            Modifier
                .clickable(role = Role.Button, onClickLabel = "Small glyph") {}
                .openCodeTapTarget()
                .border(1.dp, OpenCodeTheme.colors.border),
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(SFSymbol.Gearshape, contentDescription = "Settings", size = OpenCodeIconSize.small)
        }
    }
    Text("The outlined box is an 18 dp glyph with its 48 dp target.", style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
}

@Composable
private fun NoticesSection() = Section("Notices") {
    OpenCodeNotice("Reconnecting to Studio...", tone = OpenCodeNoticeTone.progress)
    OpenCodeNotice("Couldn’t connect to Studio", action = "Try Again", onClick = {})
    OpenCodeNotice("Couldn’t sign in to Studio", tone = OpenCodeNoticeTone.attention, symbol = SFSymbol.KeyFill, action = "Pair Again", onClick = {})
    OpenCodeNotice("Couldn’t archive 3 sessions", detail = "Couldn’t reach the OpenCode server")
}

@Composable
private fun RollingNumberSection() = Section("Rolling number") {
    var value by remember { mutableIntStateOf(1280) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s12)) {
        RollingNumber(value, style = OpenCodeTextStyles.title1)
        RollingNumber("$value subagents · ${value % 7} shells")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s8)) {
        OpenCodeSecondaryButton("−37", onClick = { value -= 37 }, modifier = Modifier.weight(1f))
        OpenCodeSecondaryButton("+1", onClick = { value += 1 }, modifier = Modifier.weight(1f))
        OpenCodeSecondaryButton("+999", onClick = { value += 999 }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MotionSection() = Section("Motion roles (enter curve: duration s, bounce → ζ, stiffness)") {
    for (role in MotionRole.entries) {
        MotionRow(role.name, role.spring)
    }
}

@Composable
private fun MotionRow(name: String, spring: MotionSpring) {
    Text(
        "$name  ${spring.duration} s, ${spring.bounce} → ζ ${"%.2f".format(spring.dampingRatio)}, k ${"%.0f".format(spring.stiffness)}",
        style = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace),
    )
}

@Composable
private fun IconsSection() = Section("Icons (${SFSymbol.entries.size} SF Symbols)") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s4), verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s8)) {
        SFSymbol.entries.forEach { symbol ->
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OpenCodeIcon(symbol, contentDescription = null)
                Text(symbol.sfName, style = OpenCodeTextStyles.caption2, color = OpenCodeTheme.colors.secondaryText, maxLines = 2)
            }
        }
    }
}

@Preview(name = "Design system · System light", heightDp = 2400)
@Composable
private fun DesignSystemGalleryLightPreview() = DesignSystemGallery(initialAppearance = AppearanceColorScheme.Light)

@Preview(name = "Design system · System dark", heightDp = 2400)
@Composable
private fun DesignSystemGalleryDarkPreview() = DesignSystemGallery(initialAppearance = AppearanceColorScheme.Dark)

@Preview(name = "Design system · OpenCode dark", heightDp = 2400)
@Composable
private fun DesignSystemGalleryOpenCodePreview() =
    DesignSystemGallery(initialTheme = AppTheme.OpenCode, initialAppearance = AppearanceColorScheme.Dark)
