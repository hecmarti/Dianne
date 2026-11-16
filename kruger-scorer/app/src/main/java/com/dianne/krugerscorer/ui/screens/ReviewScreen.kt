package com.dianne.krugerscorer.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dianne.krugerscorer.UiState
import com.dianne.krugerscorer.model.ScoreColors
import com.dianne.krugerscorer.ui.components.TargetOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    state: UiState,
    onBack: () -> Unit,
    onSensitivity: (Float) -> Unit,
    onToggle: (Int) -> Unit
) {
    val bitmap = state.bitmap
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text("Verificar impactos", style = MaterialTheme.typography.titleLarge)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Volver")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (bitmap != null) {
                TargetPreview(
                    bitmap = bitmap,
                    state = state,
                    onToggle = onToggle
                )
            }

            Spacer(Modifier.height(16.dp))

            ScoreSummary(state)

            Spacer(Modifier.height(16.dp))

            SensitivityPanel(
                sensitivity = state.sensitivity,
                analyzing = state.analyzing,
                onSensitivity = onSensitivity
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Toca un círculo para incluirlo o excluirlo del total.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            DetectionLegend(state)

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun TargetPreview(
    bitmap: Bitmap,
    state: UiState,
    onToggle: (Int) -> Unit
) {
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.7f, 1.4f))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0E120C))
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Diana analizada",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        val result = state.result
        if (result != null) {
            TargetOverlay(
                detections = result.detections,
                calibration = result.calibration,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                onToggle = onToggle,
                modifier = Modifier.fillMaxSize()
            )
        }
        AnimatedVisibility(
            visible = state.analyzing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ScoreSummary(state: UiState) {
    val result = state.result
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("TOTAL", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${result?.totalScore ?: "—"}",
                style = MaterialTheme.typography.displayLarge
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${result?.activeCount ?: 0} impactos",
                style = MaterialTheme.typography.titleLarge
            )
            if (result?.calibration == null && !state.analyzing) {
                Text(
                    text = "Diana no calibrada",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun SensitivityPanel(
    sensitivity: Float,
    analyzing: Boolean,
    onSensitivity: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text("Umbral de detección", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(
                "${(sensitivity * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Más bajo = solo huecos claros. Más alto = detecta también impactos dudosos o unidos.",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = sensitivity,
            onValueChange = onSensitivity,
            valueRange = 0.15f..0.95f,
            enabled = !analyzing,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun DetectionLegend(state: UiState) {
    val detections = state.result?.detections.orEmpty()
    if (detections.isEmpty()) {
        Text(
            text = if (state.analyzing) "Analizando…" else "Sin impactos detectados. Sube el umbral o mejora la foto.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detections.sortedByDescending { it.score }.forEach { shot ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (shot.enabled) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(ScoreColors.forScore(shot.score).copy(
                            alpha = if (shot.enabled) 1f else 0.35f
                        ))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Impacto #${shot.id + 1}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (shot.enabled) "${shot.score} pts" else "excluido",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (shot.enabled) ScoreColors.forScore(shot.score)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
