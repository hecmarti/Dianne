package com.dianne.krugerscorer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import com.dianne.krugerscorer.model.ScoreColors
import com.dianne.krugerscorer.model.ShotDetection
import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws scored shot circles on top of the target image and handles taps
 * to toggle individual detections.
 */
@Composable
fun TargetOverlay(
    detections: List<ShotDetection>,
    calibration: TargetCalibration?,
    imageWidth: Int,
    imageHeight: Int,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(detections, imageWidth, imageHeight) {
                detectTapGestures { tap ->
                    val mapped = mapViewToImage(
                        tap.x,
                        tap.y,
                        size.width.toFloat(),
                        size.height.toFloat(),
                        imageWidth.toFloat(),
                        imageHeight.toFloat()
                    ) ?: return@detectTapGestures
                    val hit = detections.minByOrNull { d ->
                        hypot(d.x - mapped.x, d.y - mapped.y)
                    } ?: return@detectTapGestures
                    val dist = hypot(hit.x - mapped.x, hit.y - mapped.y)
                    if (dist <= hit.radius * 2.2f) {
                        onToggle(hit.id)
                    }
                }
            }
    ) {
        val scale = computeScale(
            size.width,
            size.height,
            imageWidth.toFloat(),
            imageHeight.toFloat()
        )
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f

        calibration?.let { cal ->
            drawCircle(
                color = Color.White.copy(alpha = 0.25f),
                radius = cal.blackRadius * scale,
                center = Offset(offsetX + cal.centerX * scale, offsetY + cal.centerY * scale),
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                radius = 4f,
                center = Offset(offsetX + cal.centerX * scale, offsetY + cal.centerY * scale)
            )
        }

        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 14.sp.toPx()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        detections.forEach { shot ->
            val color = ScoreColors.forScore(shot.score)
            val alpha = if (shot.enabled) 1f else 0.28f
            val cx = offsetX + shot.x * scale
            val cy = offsetY + shot.y * scale
            val r = (shot.radius * scale).coerceAtLeast(8f)

            drawCircle(
                color = color.copy(alpha = 0.22f * alpha),
                radius = r,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 3f)
            )
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(cx - r * 0.35f, cy),
                end = Offset(cx + r * 0.35f, cy),
                strokeWidth = 2f
            )
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(cx, cy - r * 0.35f),
                end = Offset(cx, cy + r * 0.35f),
                strokeWidth = 2f
            )

            textPaint.color = android.graphics.Color.argb(
                (255 * alpha).toInt(),
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt()
            )
            drawContext.canvas.nativeCanvas.drawText(
                shot.score.toString(),
                cx,
                cy - r - 6f,
                textPaint
            )
        }
    }
}

private fun computeScale(
    viewW: Float,
    viewH: Float,
    imgW: Float,
    imgH: Float
): Float = min(viewW / imgW, viewH / imgH)

private fun mapViewToImage(
    vx: Float,
    vy: Float,
    viewW: Float,
    viewH: Float,
    imgW: Float,
    imgH: Float
): Offset? {
    val scale = computeScale(viewW, viewH, imgW, imgH)
    val offsetX = (viewW - imgW * scale) / 2f
    val offsetY = (viewH - imgH * scale) / 2f
    val ix = (vx - offsetX) / scale
    val iy = (vy - offsetY) / scale
    if (ix < 0f || iy < 0f || ix > imgW || iy > imgH) return null
    return Offset(ix, iy)
}
