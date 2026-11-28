package ai.opencode.feature.drawer.list

import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max

/**
 * The lifted row's grow and shadow. The row itself rides the finger in the list ([DrawerPinDrag]),
 * and its raised surface casts this shadow ([drawLiftedRowSurface]) instead of a Material
 * elevation.
 */
object DrawerLiftedRowView {
    /** How a row lifted [lift] (0 at rest in the list, 1 fully lifted) looks; dp for lengths. */
    data class Look(val scale: Float, val shadowOpacity: Float, val shadowOffsetY: Float, val shadowRadius: Float)

    /** Grows by `pinLiftScale`, the shadow's opacity follows the lift. */
    fun apply(lift: Float): Look = Look(
        scale = 1f + (DrawerTunables.pinLiftScale - 1f) * lift,
        shadowOpacity = (max(lift, 0f) * DrawerTunables.pinLiftShadow.value).toFloat(),
        shadowOffsetY = DrawerTunables.pinLiftShadowY.value,
        shadowRadius = DrawerTunables.pinLiftShadowRadius.value,
    )
}

/**
 * The lifted row's raised surface in [surface], rounded by [cornerRadius] px, casting [look]'s
 * black shadow.
 */
internal fun DrawScope.drawLiftedRowSurface(surface: Color, cornerRadius: Float, look: DrawerLiftedRowView.Look) {
    if (look.shadowOpacity <= 0f || look.shadowRadius <= 0f && look.shadowOffsetY == 0f) {
        drawRoundRect(surface, cornerRadius = CornerRadius(cornerRadius))
        return
    }
    drawIntoCanvas { canvas ->
        val paint = Paint()
        paint.nativePaint.apply {
            isAntiAlias = true
            color = surface.toArgb()
            setShadowLayer(
                max(look.shadowRadius.dp(this@drawLiftedRowSurface), 0.01f),
                0f,
                look.shadowOffsetY.dp(this@drawLiftedRowSurface),
                Color.Black.copy(alpha = look.shadowOpacity.coerceIn(0f, 1f)).toArgb(),
            )
        }
        canvas.drawRoundRect(0f, 0f, size.width, size.height, cornerRadius, cornerRadius, paint)
    }
}

private fun Float.dp(scope: DrawScope): Float = this * scope.density
