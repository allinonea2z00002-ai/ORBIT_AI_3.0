package com.orbit.ai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.orbit.ai.ui.theme.OrbitCyan
import com.orbit.ai.ui.theme.OrbitPurple
import com.orbit.ai.ui.theme.OrbitRed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState(val label: String) {
    IDLE("Online"), LISTENING("Listening"), PROCESSING("Thinking"),
    SPEAKING("Speaking"), OFFLINE("Offline"), ERROR("Error")
}

/**
 * The central AI orb: a glowing sphere with rotating rings and a circular audio visualizer.
 * Listening uses the real microphone level ([level], 0..1). Speaking has no audio feed from the
 * system voice, so it uses a gentle synthetic pulse instead of pretending to be a waveform.
 */
@Composable
fun OrbView(state: OrbState, level: Float, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orb")
    val speed = when (state) { OrbState.PROCESSING -> 1500; OrbState.LISTENING -> 5000; else -> 9000 }
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "spin")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val wave by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "wave")

    val core by animateColorAsState(
        when (state) {
            OrbState.OFFLINE -> Color(0xFF64748B)
            OrbState.ERROR -> OrbitRed
            OrbState.LISTENING -> OrbitCyan
            else -> OrbitPurple
        }, tween(500), label = "core"
    )
    val ring by animateColorAsState(
        when (state) {
            OrbState.OFFLINE -> Color(0xFF94A3B8)
            OrbState.ERROR -> Color(0xFFFCA5A5)
            else -> OrbitCyan
        }, tween(500), label = "ring"
    )

    Box(modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.matchParentSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            val orbR = r * (0.42f + 0.02f * pulse + if (state == OrbState.LISTENING) 0.05f * level else 0f)

            // soft glow
            drawCircle(
                Brush.radialGradient(listOf(core.copy(alpha = 0.35f), Color.Transparent), c, r),
                radius = r, center = c
            )

            // circular visualizer
            val bars = 72
            for (i in 0 until bars) {
                val a = (2 * PI * i / bars).toFloat()
                val amp = when (state) {
                    OrbState.LISTENING -> 0.08f + 0.9f * level * (0.4f + 0.6f * abs(sin(a * 3 + wave)))
                    OrbState.SPEAKING -> 0.12f + 0.45f * abs(sin(a * 2 + wave)) * (0.5f + 0.5f * pulse)
                    OrbState.PROCESSING -> 0.06f + 0.12f * abs(sin(a * 4 - wave * 2))
                    else -> 0.04f + 0.04f * abs(sin(a * 5 + wave * 0.5f))
                }
                val inner = orbR * 1.22f
                val outer = inner + r * 0.28f * amp
                drawLine(
                    ring.copy(alpha = 0.75f),
                    Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                    Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                    strokeWidth = r * 0.012f, cap = StrokeCap.Round
                )
            }

            // rotating rings
            rotate(spin, c) {
                drawArc(
                    ring.copy(alpha = 0.9f), 0f, 100f, false,
                    topLeft = Offset(c.x - orbR * 1.12f, c.y - orbR * 1.12f),
                    size = Size(orbR * 2.24f, orbR * 2.24f),
                    style = Stroke(r * 0.012f, cap = StrokeCap.Round)
                )
                drawArc(
                    core.copy(alpha = 0.8f), 180f, 70f, false,
                    topLeft = Offset(c.x - orbR * 1.12f, c.y - orbR * 1.12f),
                    size = Size(orbR * 2.24f, orbR * 2.24f),
                    style = Stroke(r * 0.012f, cap = StrokeCap.Round)
                )
            }
            rotate(-spin * 0.6f, c) {
                drawArc(
                    ring.copy(alpha = 0.5f), 40f, 60f, false,
                    topLeft = Offset(c.x - orbR * 1.05f, c.y - orbR * 1.05f),
                    size = Size(orbR * 2.1f, orbR * 2.1f),
                    style = Stroke(r * 0.008f, cap = StrokeCap.Round)
                )
            }

            // sphere
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.9f), core, core.copy(alpha = 0.55f), Color(0xFF0B0B14)),
                    Offset(c.x - orbR * 0.3f, c.y - orbR * 0.35f), orbR * 1.4f
                ),
                radius = orbR, center = c
            )
        }
    }
}
