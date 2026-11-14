package com.dianne.krugerscorer.vision

import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.hypot

/**
 * Maps a hole position to an ISSF-style ring score.
 *
 * On Krüger airgun training targets the black aiming mark covers rings 7–10.
 * We treat [TargetCalibration.blackRadius] as the outer edge of ring 7 and
 * scale the remaining rings with the official relative diameters.
 *
 * Scoring uses the edge of the hole nearest the center (ISSF: the shot
 * receives the highest value of any scoring zone it touches).
 */
object ScoreMapper {

    // Outer diameters (mm) for 10m air rifle, relative to ring-7 outer diameter 23.5 mm.
    // Black aiming mark on many printed Krüger cards aligns with ring 7.
    private val ringOuterDiameterMm = floatArrayOf(
        59.5f, // 1
        53.5f, // 2
        47.5f, // 3
        41.5f, // 4
        35.5f, // 5
        29.5f, // 6
        23.5f, // 7  ← black outer
        17.5f, // 8
        11.5f, // 9
        5.5f   // 10
    )

    private const val RING7_DIAMETER_MM = 23.5f

    fun score(calibration: TargetCalibration, x: Float, y: Float, radius: Float): Int {
        val distCenter = hypot(x - calibration.centerX, y - calibration.centerY)
        // Edge closest to the bullseye.
        val scoringDistance = (distCenter - radius).coerceAtLeast(0f)
        val mmPerPx = RING7_DIAMETER_MM / (2f * calibration.blackRadius)
        val distMm = scoringDistance * mmPerPx
        for (score in 10 downTo 1) {
            val outerRadiusMm = ringOuterDiameterMm[score - 1] / 2f
            if (distMm <= outerRadiusMm) return score
        }
        return 0
    }
}
