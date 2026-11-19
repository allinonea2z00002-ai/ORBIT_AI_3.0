package com.orbit.ai.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.orbit.ai.files.formatBytes
import com.orbit.ai.ui.components.*
import com.orbit.ai.viewmodel.IntelViewModel
import com.orbit.ai.viewmodel.orbitViewModel

@Composable
fun IntelScreen(onBack: () -> Unit) {
    val vm = orbitViewModel { IntelViewModel(it) }
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current

    // Refresh on first show and when returning from the Usage Access settings page.
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        OrbitTopBar("Phone Intelligence", onBack, actions = {
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        })
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            ui.storage?.let { s ->
                item {
                    GlowCard(Modifier.fillMaxWidth()) {
                        Text("Storage", style = MaterialTheme.typography.titleMedium)
                        Text("${formatBytes(s.freeBytes)} free of ${formatBytes(s.totalBytes)}")
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { s.usedFraction }, Modifier.fillMaxWidth().height(8.dp))
                        InfoNote("${(s.usedFraction * 100).toInt()}% used (${formatBytes(s.usedBytes)})")
                    }
                }
            }

            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Cleanup suggestions", style = MaterialTheme.typography.titleMedium)
                    if (ui.suggestions.isEmpty()) InfoNote("Scanning files…")
                    ui.suggestions.forEach { Text("• $it", Modifier.padding(vertical = 2.dp)) }
                    InfoNote("ORBIT only suggests. It never deletes anything on its own.")
                }
            }

            ui.battery?.let { b ->
                item {
                    GlowCard(Modifier.fillMaxWidth()) {
                        Text("Battery", style = MaterialTheme.typography.titleMedium)
                        Text(if (b.percent >= 0) "${b.percent}%" else "Level unknown")
                        LinearProgressIndicator(progress = { (b.percent.coerceAtLeast(0)) / 100f }, Modifier.fillMaxWidth().height(8.dp))
                        InfoNote(
                            (if (b.charging) "Charging via ${b.chargeSource}" else "Not charging") +
                                (b.temperatureC?.let { " · ${"%.1f".format(it)} °C" } ?: "") +
                                " · Health reported by Android: ${b.health}" +
                                (b.voltageMv?.let { " · ${it} mV" } ?: "")
                        )
                        InfoNote("Android does not give apps the exact battery capacity or cycle count.")
                    }
                }
            }

            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("Device", style = MaterialTheme.typography.titleMedium)
                    ui.device.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(k, Modifier.width(84.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                            Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("App usage (last 7 days)", style = MaterialTheme.typography.titleMedium)
                    if (!ui.usageAccess) {
                        InfoNote("To see which apps you use most, allow \"Usage access\" for ORBIT. Android asks you to turn it on in its own settings page. ORBIT only reads app names and time used, on this phone.")
                        Spacer(Modifier.height(6.dp))
                        OrbitButton("Open Usage access settings", {
                            ctx.startActivitySafe(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                    } else if (ui.usage.isEmpty()) {
                        InfoNote("No usage data yet.")
                    } else {
                        ui.usage.take(10).forEach { u ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(u.label, Modifier.weight(1f), maxLines = 1)
                                Text(formatDuration(u.foregroundMs), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (ui.categoryCounts.isNotEmpty()) item {
                GlowCard(Modifier.fillMaxWidth()) {
                    Text("App categories (${ui.apps.size} launchable apps)", style = MaterialTheme.typography.titleMedium)
                    InfoNote("Categories come from what each app declares; many apps declare none.")
                    ui.categoryCounts.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth()) { Text(k, Modifier.weight(1f)); Text("$v") }
                    }
                }
            }

            item { SectionTitle("Installed apps (tap to open)") }
            items(ui.apps, key = { it.packageName }) { a ->
                ListItem(
                    headlineContent = { Text(a.label, maxLines = 1) },
                    supportingContent = { Text(a.category) },
                    trailingContent = {
                        TextButton(onClick = { vm.launchIntent(a.packageName)?.let { ctx.startActivitySafe(it) } }) { Text("Open") }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
