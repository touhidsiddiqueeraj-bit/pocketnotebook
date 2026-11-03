package com.pocketnotebook.app.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pocketnotebook.app.core.llm.StudioService
import com.pocketnotebook.app.ui.theme.LocalGlassColors
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sin

/** Mind map: GBNF-generated topic tree rendered on a pan/zoom canvas. */
@Composable
fun MindMapBody(payload: String) {
    val glass = LocalGlassColors.current
    val json = remember { Json { ignoreUnknownKeys = true } }

    val root = remember(payload) {
        runCatching { json.decodeFromString(StudioService.MindMapNode.serializer(), payload) }.getOrNull()
    }
    if (root == null) {
        Text(
            "Could not read mind map data.",
            style = MaterialTheme.typography.bodyMedium,
            color = glass.accent3,
            modifier = Modifier.padding(24.dp),
        )
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Virtual layout: 1600x1100 px space, centered root, radial rings.
    val W = 1600f
    val H = 1100f
    val positions = remember(root) { flattenRadial(root, W, H) }

    val textColor = glass.text
    val accent = glass.accent
    val accent2 = glass.accent2

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, p, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.35f, 4f)
                    pan += p
                }
            },
    ) {
        val cx = size.width / 2 + pan.x
        val cy = size.height / 2 + pan.y

        fun toScreen(center: Offset): Offset =
            Offset(cx + (center.x - W / 2) * scale, cy + (center.y - H / 2) * scale)

        val paint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // edges
        val byNode = positions.associateBy { it.node }
        positions.forEach { p ->
            p.node.children.forEach { child ->
                val cp = byNode[child] ?: return@forEach
                drawPath(
                    path = Path().apply {
                        val from = toScreen(p.center)
                        val to = toScreen(cp.center)
                        moveTo(from.x, from.y)
                        val midX = (from.x + to.x) / 2
                        cubicTo(midX, from.y, midX, to.y, to.x, to.y)
                    },
                    color = accent2.copy(alpha = 0.45f),
                    style = Stroke(width = 1.8f * scale),
                )
            }
        }

        // nodes + labels
        positions.forEach { p ->
            val c = toScreen(p.center)
            val r = p.radius * scale
            drawCircle(color = glass.fillTop.copy(alpha = 0.9f), radius = r, center = c)
            drawCircle(
                color = if (p.depth == 0) accent.copy(alpha = 0.7f) else accent2.copy(alpha = 0.5f),
                radius = r,
                center = c,
                style = Stroke(width = 2f * scale),
            )
            val textSize = 15f * scale * (if (p.depth == 0) 1.25f else 1f)
            paint.textSize = textSize
            paint.color = android.graphics.Color.argb(
                (textColor.alpha * 255).toInt(),
                (textColor.red * 255).toInt(),
                (textColor.green * 255).toInt(),
                (textColor.blue * 255).toInt(),
            )
            // shrink text to fit the circle
            var label = p.node.title
            if (paint.measureText(label) > r * 1.7f) {
                while (label.length > 4 && paint.measureText(label) > r * 1.7f) {
                    label = label.dropLast(1)
                }
                if (paint.measureText(label) > r * 1.7f) label = label.take(6) + "…"
            }
            drawIntoCanvas {
                it.nativeCanvas.drawText(label, c.x, c.y + textSize * 0.35f, paint)
            }
        }
    }
}

private data class NodePos(
    val node: StudioService.MindMapNode,
    val center: Offset,
    val radius: Float,
    val depth: Int,
)

private fun flattenRadial(root: StudioService.MindMapNode, W: Float, H: Float): List<NodePos> {
    class Holder(val node: StudioService.MindMapNode, val depth: Int, val angle: Float, val slice: Float)

    val radii = listOf(0f, 300f, 560f, 750f)
    val sizes = listOf(120f, 92f, 74f, 60f)
    val out = ArrayList<NodePos>()
    val stack = ArrayDeque<Holder>()
    stack.addLast(Holder(root, 0, -90f, 360f))

    while (stack.isNotEmpty()) {
        val h = stack.removeLast()
        val depth = h.depth.coerceAtMost(3)
        val r = radii[depth]
        val cx = W / 2 + r * cos(Math.toRadians(h.angle.toDouble())).toFloat()
        val cy = H / 2 + r * sin(Math.toRadians(h.angle.toDouble())).toFloat()
        out += NodePos(h.node, Offset(cx, cy), sizes[depth], depth)

        val children = h.node.children
        if (children.isEmpty() || depth >= 3) continue
        val start = h.angle - h.slice / 2
        children.forEachIndexed { i, c ->
            val sliceAngle = h.slice / children.size
            stack.addLast(Holder(c, depth + 1, start + sliceAngle * (i + 0.5f), sliceAngle))
        }
    }
    return out
}
