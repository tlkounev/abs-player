package cz.ondra.absplayer.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Indication for the remote control: the focused element gets a light fill and a [color] ring
 * (circle for round buttons, rounded rectangle otherwise). Touch keeps the normal ripple.
 */
class FocusRing(private val color: Color, private val radius: Dp? = null) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color, radius)
    override fun equals(other: Any?) = other is FocusRing && other.color == color && other.radius == radius
    override fun hashCode() = color.hashCode() * 31 + radius.hashCode()

    private class Node(
        private val source: InteractionSource,
        private val color: Color,
        private val radius: Dp?,
    ) : DelegatingNode(), DrawModifierNode {
        private var focused = false

        init {
            delegate(ripple().create(source))
        }

        override fun onAttach() {
            coroutineScope.launch {
                val active = mutableSetOf<FocusInteraction.Focus>()
                source.interactions.collect { i ->
                    when (i) {
                        is FocusInteraction.Focus -> active += i
                        is FocusInteraction.Unfocus -> active -= i.focus
                    }
                    val f = active.isNotEmpty()
                    if (f != focused) {
                        focused = f
                        invalidateDraw()
                    }
                }
            }
        }

        /** Explicit radius (matches the element's shape), else circle for round buttons, 10 dp otherwise. */
        private fun ContentDrawScope.cornerPx(): Float = when {
            radius != null -> radius.toPx().coerceAtMost(size.minDimension / 2)
            kotlin.math.abs(size.width - size.height) < 6.dp.toPx() -> size.minDimension / 2
            else -> 10.dp.toPx()
        }

        override fun ContentDrawScope.draw() {
            if (focused) drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = CornerRadius(cornerPx()))
            drawContent()
            if (focused) {
                val w = 2.5.dp.toPx()
                val r = cornerPx()
                drawRoundRect(
                    color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius((r - w / 2).coerceAtLeast(0f)), style = Stroke(w),
                )
            }
        }
    }
}
