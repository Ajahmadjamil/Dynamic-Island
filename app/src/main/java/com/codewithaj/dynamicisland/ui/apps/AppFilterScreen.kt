package com.codewithaj.dynamicisland.ui.apps

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.codewithaj.dynamicisland.ServiceLocator
import com.codewithaj.dynamicisland.data.AppFilterMode
import com.codewithaj.dynamicisland.data.IslandSettings
import com.codewithaj.dynamicisland.ui.components.IsletTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppEntry(val packageName: String, val label: String)

/**
 * Which apps may appear on the island (notification previews and app-owned activities such
 * as music). System alerts (charging, silent mode, Bluetooth) aren't app-owned and always show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(settings: IslandSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { pkg -> AppEntry(pkg, appLabel(pm, pkg)) }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun setMode(mode: AppFilterMode) = ServiceLocator.appScope.launch {
        // The list means the opposite in the other mode, so start fresh rather than surprise.
        ServiceLocator.settings.update { it.copy(appFilterMode = mode, appFilterPackages = emptySet()) }
    }

    fun setAllowed(pkg: String, allowed: Boolean) = ServiceLocator.appScope.launch {
        ServiceLocator.settings.update { s ->
            val listed = when (s.appFilterMode) {
                AppFilterMode.ALL_EXCEPT_BLOCKED -> !allowed // list = blocked apps
                AppFilterMode.ONLY_SELECTED -> allowed       // list = allowed apps
            }
            s.copy(appFilterPackages = if (listed) s.appFilterPackages + pkg else s.appFilterPackages - pkg)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { IsletTopBar("Choose apps", scroll, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val modes = AppFilterMode.entries
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = settings.appFilterMode == m,
                            onClick = { if (settings.appFilterMode != m) setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) {
                            Text(if (m == AppFilterMode.ALL_EXCEPT_BLOCKED) "All except…" else "Only selected")
                        }
                    }
                }
            }
            item {
                Text(
                    "Switched-on apps can show previews and live activities on the island. " +
                        "Charging, silent mode and Bluetooth alerts always show.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            val list = apps
            if (list == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                val filtered = if (query.isBlank()) list else list.filter { it.label.contains(query.trim(), ignoreCase = true) }
                items(filtered, key = { it.packageName }) { app ->
                    val allowed = settings.allowsApp(app.packageName)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { setAllowed(app.packageName, !allowed) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        AppIcon(app.packageName)
                        Spacer(Modifier.width(16.dp))
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = allowed, onCheckedChange = { setAllowed(app.packageName, it) })
                    }
                }
            }
        }
    }
}

/** Icons load lazily per visible row, off the main thread. */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    val bmp = icon
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(40.dp))
    else Spacer(Modifier.size(40.dp))
}

private fun appLabel(pm: PackageManager, pkg: String): String = try {
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) { pkg }
