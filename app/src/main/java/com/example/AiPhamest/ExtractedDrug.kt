// File: ExtractedDrug.kt
package com.example.AiPhamest

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/**
 * Returns true if any non-blank line does NOT end with a pack (e.g. "30p") or volume (e.g. "200ml").
 */
fun missingPack(text: String): Boolean =
    text.lines()
        .filter { it.isNotBlank() }
        .any {
            !Regex("""\|\s*\d+\s*(p|ml)\s*$""", RegexOption.IGNORE_CASE)
                .containsMatchIn(it)
        }

/* ─────── Extraction domain model ─────── */

data class ExtractedDrug(
    var name: String,
    var strengthUnit: String,
    var dose: String,
    var frequency: String,
    var pack: String = ""
) {
    /** Serialises back to the original pipe-delimited format, with NO spaces. */
    fun asLine(): String =
        listOf(name, strengthUnit, dose, frequency, pack.ifBlank { "" })
            .joinToString("|")        // ← no extra spaces
            .trimEnd()
}


/** Turns the LLM output into objects. */
fun parseExtracted(text: String): List<ExtractedDrug> =
    text.lines()
        .filter { it.isNotBlank() }
        .map { line ->
            val parts = line.split('|').map { it.trim() }
            ExtractedDrug(
                name         = parts.getOrNull(0).orEmpty(),
                strengthUnit = parts.getOrNull(1).orEmpty(),
                dose         = parts.getOrNull(2).orEmpty(),
                frequency    = parts.getOrNull(3).orEmpty(),
                pack         = parts.getOrNull(4).orEmpty()        // might be missing
            )
        }

/** Re-builds the raw text string from a list of drugs. */
fun drugsToText(list: List<ExtractedDrug>): String =
    list.joinToString("\n") { it.asLine() }

@Composable
fun ExtractionList(
    rawText: String,
    onTextChange: (String) -> Unit,
    // Optional: allow caller to override max height if desired
    maxListHeight: Dp = 400.dp
) {
    // Convert the input to stateful items once (rememberSaveable keeps edits across recompositions)
    val drugs = rememberSaveable(rawText, saver = listSaver(
        save = { it.map(ExtractedDrug::asLine) },
        restore = { it.map(::parseExtracted).flatten().toMutableStateList() }
    )) {
        parseExtracted(rawText).toMutableStateList()
    }

    // Whenever the list changes, bubble the new raw text up
    LaunchedEffect(drugs) {
        snapshotFlow { drugs.map { it.asLine() } }
            .collect { onTextChange(it.joinToString("\n")) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Extracted Drugs", style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = { /* maybe “add manual item” later */ },
                    label = { Text("${drugs.size} items") }
                )
            }

            Spacer(Modifier.height(12.dp))

            // ========== the list ==========
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight) // constrain the list height!
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(drugs, key = { _, d -> d.name + d.strengthUnit }) { idx, drug ->
                        DrugCard(
                            drug = drug,
                            onUpdate = { drugs[idx] = it },
                            onDelete = { drugs.removeAt(idx) }
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun DrugCard(
    drug: ExtractedDrug,
    onUpdate: (ExtractedDrug) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {

            // ---------- collapsed view ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(drug.name, fontWeight = FontWeight.Bold)
                    Text("${drug.dose}  ·  ${drug.frequency}h", style = MaterialTheme.typography.bodySmall)
                }
                Text(drug.pack.ifBlank { "—" }, fontWeight = FontWeight.SemiBold)
            }

            // ---------- editable details ----------
            if (expanded) {
                Spacer(Modifier.height(12.dp))

                @Composable
                fun tf(value: String, label: String, onVal: (String) -> Unit) =
                    OutlinedTextField(
                        value = value,
                        onValueChange = onVal,
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                tf(drug.name,         "Name")          { onUpdate(drug.copy(name = it)) }
                tf(drug.strengthUnit, "Strength & U.") { onUpdate(drug.copy(strengthUnit = it)) }
                tf(drug.dose,         "Dose")          { onUpdate(drug.copy(dose = it)) }
                tf(drug.frequency,    "Freq (h)")      { onUpdate(drug.copy(frequency = it)) }
                tf(drug.pack,         "Pack (e.g. 30p / 200ml)") {
                    onUpdate(drug.copy(pack = it))
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDelete) { Text("Remove") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { expanded = false }) { Text("Done") }
                }
            }
        }
    }
}
