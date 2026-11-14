package com.dianne.krugerscorer.vision

import android.graphics.Bitmap
import com.dianne.krugerscorer.model.AnalysisResult
import com.dianne.krugerscorer.model.ShotDetection

/**
 * End-to-end analysis: calibrate Krüger target → detect holes → score.
 */
object DetectionPipeline {

    data class Output(
        val result: AnalysisResult,
        val workingBitmap: Bitmap
    )

    fun analyze(source: Bitmap, sensitivity: Float): Output {
        val (buffer, working) = ImageBuffer.fromBitmap(source)
        val calibration = TargetCalibrator.calibrate(buffer)
        val detections = if (calibration != null) {
            HoleDetector.detect(buffer, calibration, sensitivity)
        } else {
            emptyList()
        }
        return Output(
            result = AnalysisResult(
                calibration = calibration,
                detections = detections,
                sensitivity = sensitivity
            ),
            workingBitmap = working
        )
    }

    fun reanalyzeKeepingToggles(
        source: Bitmap,
        sensitivity: Float,
        previous: List<ShotDetection>
    ): Output {
        val output = analyze(source, sensitivity)
        if (previous.isEmpty()) return output

        // Preserve user toggles when a new detection lands near an old one.
        val merged = output.result.detections.map { neu ->
            val match = previous.minByOrNull { old ->
                val dx = old.x - neu.x
                val dy = old.y - neu.y
                dx * dx + dy * dy
            }
            val dist = match?.let {
                val dx = it.x - neu.x
                val dy = it.y - neu.y
                kotlin.math.sqrt(dx * dx + dy * dy)
            } ?: Float.MAX_VALUE
            if (match != null && dist < neu.radius * 1.2f) {
                neu.copy(enabled = match.enabled)
            } else {
                neu
            }
        }
        return output.copy(result = output.result.copy(detections = merged))
    }
}
