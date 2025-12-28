package com.kevmo314.kineticstreamer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun AudioVisualizerView(
    audioLevels: List<Float> = emptyList(),
    modifier: Modifier = Modifier,
    barCount: Int = 12,
    barColor: Color = Color.White,
    backgroundColor: Color = Color.Black.copy(alpha = 0.6f)
) {
    var displayLevels by remember { mutableStateOf(List(barCount) { 0f }) }

    // Smooth animation between levels
    LaunchedEffect(audioLevels) {
        if (audioLevels.isNotEmpty()) {
            // Resize audio levels to match bar count
            val resizedLevels = if (audioLevels.size >= barCount) {
                audioLevels.take(barCount)
            } else {
                // Interpolate to fill bar count
                List(barCount) { i ->
                    val index = (i * audioLevels.size.toFloat() / barCount).toInt()
                        .coerceAtMost(audioLevels.size - 1)
                    audioLevels[index]
                }
            }
            displayLevels = resizedLevels
        }
    }

    // Decay animation when no audio
    LaunchedEffect(Unit) {
        while (true) {
            delay(50) // 20 FPS update
            if (audioLevels.isEmpty()) {
                // Gradually decay bars to zero
                displayLevels = displayLevels.map { level ->
                    (level * 0.9f).coerceAtLeast(0f)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .size(150.dp, 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
    ) {
        Canvas(
            modifier = Modifier.size(150.dp, 40.dp)
        ) {
            drawAudioBars(displayLevels, barColor)
        }
    }
}

private fun DrawScope.drawAudioBars(levels: List<Float>, barColor: Color) {
    val barWidth = size.width / levels.size
    val maxBarHeight = size.height * 0.8f // Leave some padding
    val baseY = size.height
    val minBarHeight = 2.dp.toPx()

    levels.forEachIndexed { index, level ->
        val barHeight = (level * maxBarHeight).coerceAtLeast(minBarHeight)
        val barX = index * barWidth + barWidth * 0.1f // Small spacing between bars
        val actualBarWidth = barWidth * 0.8f // Make bars slightly narrower for spacing

        drawRect(
            color = barColor,
            topLeft = Offset(barX, baseY - barHeight),
            size = Size(actualBarWidth, barHeight)
        )
    }
}
