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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Indication for the remote control: the focused element gets a light fill and a [color] ring
 * (circle for round buttons, rounded rectangle otherwise). Touch keeps the normal ripple.
 */
class FocusRing(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color)
    override fun equals(other: Any?) = other is FocusRing && other.color == color
    override fun hashCode() = color.hashCode()

    private class Node(private val source: InteractionSource, private val color: Color) : DelegatingNode(), DrawModifierNode {
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

        override fun ContentDrawScope.draw() {
            if (focused) {
                val round = kotlin.math.abs(size.width - size.height) < 6.dp.toPx()
                val r = if (round) size.minDimension / 2 else 10.dp.toPx()
                drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = CornerRadius(r))
            }
            drawContent()
            if (focused) {
                val w = 2.5.dp.toPx()
                val round = kotlin.math.abs(size.width - size.height) < 6.dp.toPx()
                val r = if (round) size.minDimension / 2 else 10.dp.toPx()
                drawRoundRect(
                    color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius((r - w / 2).coerceAtLeast(0f)), style = Stroke(w),
                )
            }
        }
    }
}
