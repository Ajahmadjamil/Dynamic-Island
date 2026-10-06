package com.codewithaj.dynamicisland.ui.adb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.codewithaj.dynamicisland.ui.components.IsletTopBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.codewithaj.dynamicisland.util.PermissionUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.codewithaj.dynamicisland.ui.theme.SuccessGreen
import com.codewithaj.dynamicisland.util.AdbCommands

/**
 * Every adb command Islet may need, with live status (re-checked whenever you come back to the
 * app), required ones first, and copy buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbCommandsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Commands are usually run while this screen is open, so there's no resume to trigger a
    // refresh: re-check every 1.5 s while visible (UI process only; stops when you leave).
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(PermissionUtils.status(context)) }
    LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            while (true) {
                status = PermissionUtils.status(context)
                delay(1_500)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    // Only the must-have commands; optional ones live next to their features in Settings.
    val required = AdbCommands.items(context, status).filter { it.level == AdbCommands.Level.REQUIRED }
    val requiredDone = required.count { it.done }
    val missing = required.filter { !it.done }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { IsletTopBar("ADB commands", scroll, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SummaryCard(
                    requiredDone = requiredDone,
                    requiredTotal = required.size,
                    missingCount = missing.size,
                    onCopyMissing = {
                        copy(context, missing.joinToString("\n") { it.command }, "${missing.size} commands copied")
                    },
                )
            }
            item {
                Text(
                    "Run these on a computer with the phone connected and USB debugging on " +
                        "(Settings → System → Developer options). Each command is safe to run again. " +
                        "The ticks update live while this screen is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(required, key = { it.id }) { item -> CommandCard(item) }
        }
    }
}

@Composable
private fun SummaryCard(requiredDone: Int, requiredTotal: Int, missingCount: Int, onCopyMissing: () -> Unit) {
    val allRequired = requiredDone == requiredTotal
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (allRequired) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (allRequired) "All required setup done" else "Required: $requiredDone of $requiredTotal done",
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = { if (requiredTotal == 0) 1f else requiredDone / requiredTotal.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
            Text(
                if (missingCount == 0) "Nothing left to run." else "$missingCount command(s) still to run (required + optional).",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (missingCount > 0) {
                FilledTonalButton(onClick = onCopyMissing, modifier = Modifier.padding(top = 10.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy all missing")
                }
            }
        }
    }
}

@Composable
private fun CommandCard(item: AdbCommands.Item) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = item.done
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = if (ok) "Done" else "Missing",
                    tint = when {
                        ok -> SuccessGreen
                        item.level == AdbCommands.Level.REQUIRED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ok) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                item.why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Command in a monospace box; long ones scroll sideways instead of wrapping mid-token.
            Text(
                item.command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { copy(context, item.command, "Command copied") }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
            }
        }
    }
}

private fun copy(context: Context, text: String, toast: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", text))
    // Android 13+ shows its own clipboard confirmation; older versions get a toast.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}
