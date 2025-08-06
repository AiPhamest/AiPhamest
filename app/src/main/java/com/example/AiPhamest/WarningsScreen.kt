package com.example.AiPhamest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.AiPhamest.data.WarningViewModel
import com.example.AiPhamest.data.WarningEntity
import com.example.AiPhamest.data.Severity
import com.example.AiPhamest.data.AppVMFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.roundToInt

private val RedWarning = Color(0xFFE53935)
private val YellowWarning = Color(0xFFFFC107)
private val GreenWarning = Color(0xFF43A047)

@Composable
fun WarningsScreen(
    warningViewModel: WarningViewModel = viewModel(factory = AppVMFactory(LocalContext.current.applicationContext as android.app.Application))
) {
    val warnings by warningViewModel.warnings.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Warnings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Check recent health warnings below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (warnings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No warnings to show.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(warnings, key = { it.id }) { warning ->
                        WarningCard(
                            warning = warning,
                            onDelete = { warningToDelete ->
                                warningViewModel.delete(warningToDelete)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WarningCard(
    warning: WarningEntity,
    onDelete: (WarningEntity) -> Unit
) {
    val degreeColor = when (warning.severity) {
        Severity.HIGH -> RedWarning
        Severity.MEDIUM -> YellowWarning
        Severity.LOW -> GreenWarning
    }

    // Parse once per item id
    val recommendations = remember(warning.id) {
        try {
            warning.recommendations?.let {
                val type = object : TypeToken<List<String>>() {}.type
                Gson().fromJson<List<String>>(it, type)
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    val hasDetails = (warning.reasoning?.isNotBlank() == true) || recommendations.isNotEmpty()
    var expanded by rememberSaveable(warning.id) { mutableStateOf(false) }

    Card(
        onClick = { if (hasDetails) expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        // Draw the 6dp severity bar without intrinsics
        Column(
            modifier = Modifier
                .drawBehind {
                    // left vertical strip across current height (cheap; no extra measure pass)
                    drawRect(
                        color = degreeColor,
                        size = Size(6.dp.toPx(), size.height)
                    )
                }
                .padding(start = 6.dp) // make room for the strip
            // (Choose ONE animation approach; we use AnimatedVisibility below)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${warning.severity} - ${warning.title}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = degreeColor
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Possible Cause: ${warning.drugPossibleCause}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (hasDetails) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (expanded) "Hide details" else "Show details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(onClick = { onDelete(warning) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Warning",
                        tint = Color.Red
                    )
                }
            }

            // Collapsible area (single animation path)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(180)),
                exit = shrinkVertically(animationSpec = tween(160))
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    warning.reasoning?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "Reasoning:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    if (recommendations.isNotEmpty()) {
                        Text(
                            "Recommendations:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        recommendations.forEach { rec ->
                            Text(
                                text = "• $rec",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    warning.confidence?.let {
                        val percent = (it * 100).roundToInt()
                        Text(
                            text = "Confidence: $percent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}


