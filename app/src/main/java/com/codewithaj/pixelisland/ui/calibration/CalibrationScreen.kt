package com.codewithaj.pixelisland.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.codewithaj.pixelisland.ServiceLocator
import com.codewithaj.pixelisland.data.Calibration
import com.codewithaj.pixelisland.data.IslandSettings
import com.codewithaj.pixelisland.data.IslandSettings.Companion.AUTO
import com.codewithaj.pixelisland.island.IslandGeometry
import com.codewithaj.pixelisland.ui.components.IsletTopBar
import com.codewithaj.pixelisland.ui.components.rememberPermissionStatus
import com.codewithaj.pixelisland.util.DisplayUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The live preview is the real overlay: every change is written to the multi-process DataStore
 * and the island process redraws immediately. A dashed green guide is drawn around the pill
 * while this screen is visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(settings: IslandSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val status = rememberPermissionStatus()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val auto = remember { IslandGeometry.auto(context) }

    // Local copy so sliders are smooth; writes are coalesced below.
    var cal by remember { mutableStateOf(settings.calibration) }
    val pending = remember { MutableStateFlow<Calibration?>(null) }
    val set: (Calibration) -> Unit = { cal = it; pending.value = it }

    LaunchedEffect(Unit) {
        // collectLatest + a tiny delay = debounce: at most ~40 DataStore writes/sec while dragging.
        pending.filterNotNull().collectLatest { c ->
            delay(24)
            ServiceLocator.settings.updateCalibration { c }
        }
    }

    LifecycleStartEffect(Unit) {
        ServiceLocator.appScope.launch { ServiceLocator.settings.update { it.copy(calibrating = true) } }
        onStopOrDispose {
            val last = pending.value
            ServiceLocator.appScope.launch {
                ServiceLocator.settings.update { s -> s.copy(calibrating = false, calibration = last ?: s.calibration) }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { IsletTopBar("Calibrate", scroll, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    if (status.canShowIsland) "Drag the sliders until the pill covers your camera exactly. Changes apply live."
                    else "Enable the accessibility service to see the live island while calibrating.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { MiniPreview(cal) }

            item {
                LabeledSlider("Horizontal offset", cal.offsetXDp, -120f..120f, "dp") { set(cal.copy(offsetXDp = it)) }
            }
            item {
                LabeledSlider("Vertical offset", cal.offsetYDp, -30f..80f, "dp") { set(cal.copy(offsetYDp = it)) }
            }
            item {
                LabeledSlider(
                    "Width", if (cal.widthDp == AUTO) auto.widthDp else cal.widthDp, 30f..360f, "dp",
                    isAuto = cal.widthDp == AUTO, onAuto = { set(cal.copy(widthDp = AUTO)) },
                ) { set(cal.copy(widthDp = it)) }
            }
            item {
                LabeledSlider(
                    "Height", if (cal.heightDp == AUTO) auto.heightDp else cal.heightDp, 14f..90f, "dp",
                    isAuto = cal.heightDp == AUTO, onAuto = { set(cal.copy(heightDp = AUTO)) },
                ) { set(cal.copy(heightDp = it)) }
            }
            item {
                val h = if (cal.heightDp == AUTO) auto.heightDp else cal.heightDp
                LabeledSlider(
                    "Corner radius", if (cal.cornerRadiusDp == AUTO) h / 2f else cal.cornerRadiusDp, 0f..45f, "dp",
                    isAuto = cal.cornerRadiusDp == AUTO, autoLabel = "Pill", onAuto = { set(cal.copy(cornerRadiusDp = AUTO)) },
                ) { set(cal.copy(cornerRadiusDp = it)) }
            }
            item {
                OutlinedButton(onClick = { set(Calibration()) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Reset to automatic")
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    isAuto: Boolean? = null,
    autoLabel: String = "Auto",
    onAuto: () -> Unit = {},
    onChange: (Float) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (isAuto != null) {
                    FilterChip(selected = isAuto, onClick = onAuto, label = { Text(autoLabel) }, modifier = Modifier.padding(end = 8.dp))
                }
                Text("${value.roundToInt()} $unit", style = MaterialTheme.typography.labelLarge)
            }
            Slider(value = value.coerceIn(range), onValueChange = { onChange((it * 2).roundToInt() / 2f) }, valueRange = range)
        }
    }
}

/** Scaled drawing of the top of the screen: camera hole + where the pill will be. */
@Composable
private fun MiniPreview(cal: Calibration) {
    val context = LocalContext.current
    val screenW = remember { DisplayUtils.screenWidthPx(context).toFloat() }
    val hole = remember { DisplayUtils.topCutoutHole(context) }
    val b = remember(cal) { IslandGeometry.compute(context, cal) }
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline

    Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 8.dp)) {
        // Zoom 2x around the pill so small adjustments are visible.
        val scale = size.width / screenW * 2f
        val originX = size.width / 2f - b.centerX * scale
        drawRoundRect(surface, cornerRadius = CornerRadius(28.dp.toPx()))
        hole?.let {
            drawCircle(
                outline, radius = it.width() / 2f * scale,
                center = Offset(originX + it.centerX() * scale, it.centerY() * scale + 8.dp.toPx()),
            )
        }
        drawRoundRect(
            Color.Black.copy(alpha = 0.85f),
            topLeft = Offset(originX + b.left * scale, b.top * scale + 8.dp.toPx()),
            size = Size(b.width * scale, b.height * scale),
            cornerRadius = CornerRadius(b.cornerRadius * scale),
        )
    }
}
