package com.dianne.krugerscorer.vision

import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.hypot

/**
 * Maps a hole to an ISSF 10 m air-pistol ring score.
 *
 * Black aiming mark = outer edge of ring 7 = 29.75 mm radius.
 * Score uses the edge of the hole nearest the center (hole touches the zone).
 */
object ScoreMapper {

    private const val HOLE_R_MM = 2.25f
    private const val BLACK_R_MM = 29.75f
    /** Outer radii (mm) for scores 10..7. */
    private val ringRadiusMm = floatArrayOf(5.75f, 13.75f, 21.75f, 29.75f)

    fun score(calibration: TargetCalibration, x: Float, y: Float, radius: Float): Int {
        val distPx = hypot(x - calibration.centerX, y - calibration.centerY)
        val mmPerPx = BLACK_R_MM / calibration.blackRadius
        // Distance of hole center in mm (scoring uses center + pellet rule via HOLE_R)
        val dMm = distPx * mmPerPx
        for (i in ringRadiusMm.indices) {
            val s = 10 - i
            if (dMm <= ringRadiusMm[i] + HOLE_R_MM) return s
        }
        return 6
    }
}
