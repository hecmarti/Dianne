package com.dianne.krugerscorer.vision

import android.graphics.Bitmap
import com.dianne.krugerscorer.model.AnalysisResult
import com.dianne.krugerscorer.model.ShotDetection
import kotlin.math.sqrt

/**
 * End-to-end analysis via template superposition (canonical Kruger frame).
 */
object DetectionPipeline {

    data class Output(
        val result: AnalysisResult,
        val workingBitmap: Bitmap
    )

    fun analyze(source: Bitmap, sensitivity: Float): Output {
        val out = TemplatePipeline.analyze(source, sensitivity)
        return Output(out.result, out.workingBitmap)
    }

    fun reanalyzeKeepingToggles(
        source: Bitmap,
        sensitivity: Float,
        previous: List<ShotDetection>
    ): Output {
        val output = analyze(source, sensitivity)
        if (previous.isEmpty()) return output
        val merged = output.result.detections.map { neu ->
            val match = previous.minByOrNull { old ->
                val dx = old.x - neu.x
                val dy = old.y - neu.y
                dx * dx + dy * dy
            }
            val dist = match?.let {
                val dx = it.x - neu.x
                val dy = it.y - neu.y
                sqrt(dx * dx + dy * dy)
            } ?: Float.MAX_VALUE
            if (match != null && dist < neu.radius * 1.3f) {
                neu.copy(enabled = match.enabled)
            } else {
                neu
            }
        }
        return output.copy(result = output.result.copy(detections = merged))
    }
}
