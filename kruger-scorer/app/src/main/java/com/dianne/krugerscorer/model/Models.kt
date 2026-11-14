package com.dianne.krugerscorer.model

import androidx.compose.ui.graphics.Color

/**
 * A single detected pellet hole on the target.
 *
 * [score] follows ISSF ring scoring for 10m air-rifle / Krüger training targets.
 * [enabled] lets the shooter include or exclude the hit when verifying the result.
 */
data class ShotDetection(
    val id: Int,
    val x: Float,
    val y: Float,
    val radius: Float,
    val score: Int,
    val confidence: Float,
    val enabled: Boolean = true
)

data class TargetCalibration(
    val centerX: Float,
    val centerY: Float,
    /** Outer radius of the black aiming mark (≈ ring 7 on Krüger airgun targets). */
    val blackRadius: Float,
    val imageWidth: Int,
    val imageHeight: Int
)

data class AnalysisResult(
    val calibration: TargetCalibration?,
    val detections: List<ShotDetection>,
    val sensitivity: Float
) {
    val totalScore: Int
        get() = detections.filter { it.enabled }.sumOf { it.score }

    val activeCount: Int
        get() = detections.count { it.enabled }
}

object ScoreColors {
    private val palette = mapOf(
        10 to Color(0xFFFFD54F),
        9 to Color(0xFFFF8A65),
        8 to Color(0xFFFFB74D),
        7 to Color(0xFFAED581),
        6 to Color(0xFF4DD0E1),
        5 to Color(0xFF64B5F6),
        4 to Color(0xFFBA68C8),
        3 to Color(0xFFF06292),
        2 to Color(0xFFA1887F),
        1 to Color(0xFF90A4AE),
        0 to Color(0xFF78909C)
    )

    fun forScore(score: Int): Color = palette[score.coerceIn(0, 10)] ?: Color.White
}
