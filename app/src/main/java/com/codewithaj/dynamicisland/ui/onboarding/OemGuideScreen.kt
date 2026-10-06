package com.codewithaj.dynamicisland.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codewithaj.dynamicisland.ui.components.IsletTopBar
import com.codewithaj.dynamicisland.util.OemAutoStart
import com.codewithaj.dynamicisland.util.startFirstResolvable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OemGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val guide = remember { OemAutoStart.guideFor(context) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { IsletTopBar(guide.brand, scroll, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(guide.steps) { i, step ->
                Row {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
                    Text(step, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Button(
                    onClick = { context.startFirstResolvable(*guide.intents.toTypedArray()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Open the setting") }
            }
        }
    }
}
