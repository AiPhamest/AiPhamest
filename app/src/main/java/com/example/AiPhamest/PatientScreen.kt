package com.example.AiPhamest

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import com.example.AiPhamest.data.PatientViewModel
import com.example.AiPhamest.data.PatientEntity

@Composable
fun rememberSaveableStringList(
    initial: List<String> = emptyList()
): SnapshotStateList<String> = rememberSaveable(
    saver = listSaver(
        save = { stateList -> stateList.toList() },
        restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
    )
) {
    mutableStateListOf(*initial.toTypedArray())
}

@Composable
fun ListEditor(
    label: String,
    list: SnapshotStateList<String>,
    addItem: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    Spacer(Modifier.height(16.dp))

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(8.dp))

        if (list.isEmpty()) {
            Text(
                text = "No ${label.lowercase()} added yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        } else {
            list.forEachIndexed { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = item,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    IconButton(
                        onClick = { list.removeAt(index) },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove $item"
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Add ${label.lowercase()}...") },
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            addItem(text.trim())
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank()
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add $label"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientScreen(viewModel: PatientViewModel) {
    val data by viewModel.patient.collectAsState()

    var name by rememberSaveable { mutableStateOf("") }
    var ageText by rememberSaveable { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf("") }
    var emergencyContact by rememberSaveable { mutableStateOf("") }
    var bloodType by rememberSaveable { mutableStateOf("") }

    var pastSurgeries by rememberSaveable { mutableStateOf("") }
    var chronicDiseases by rememberSaveable { mutableStateOf("") }
    var allergies by rememberSaveable { mutableStateOf("") }
    var medications by rememberSaveable { mutableStateOf("") }

    // Only load from DB once, when empty
    LaunchedEffect(data) {
        if (data != null && name.isEmpty() && pastSurgeries.isEmpty() && allergies.isEmpty()) {
            data?.let { p ->
                name = p.name
                ageText = p.age?.toString() ?: ""
                gender = p.gender ?: ""
                emergencyContact = p.emergencyContact ?: ""
                bloodType = p.bloodType ?: ""
                pastSurgeries = p.pastSurgeries ?: ""
                chronicDiseases = p.chronicDiseases ?: ""
                allergies = p.allergies ?: ""
                medications = p.medications ?: ""
            }
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val hasInput = listOf(
                        name, ageText, gender, bloodType, emergencyContact,
                        pastSurgeries, chronicDiseases, allergies, medications
                    ).any { it.isNotBlank() }

                    if (!hasInput) {
                        coroutineScope.launch { snackbar.showSnackbar("Nothing to save") }
                        return@ExtendedFloatingActionButton
                    }

                    val patientToSave = PatientEntity(
                        name = name.trim(),
                        age = ageText.toIntOrNull(),
                        gender = gender.trim().takeIf { it.isNotBlank() },
                        bloodType = bloodType.trim().takeIf { it.isNotBlank() },
                        emergencyContact = emergencyContact.trim().takeIf { it.isNotBlank() },
                        pastSurgeries = pastSurgeries.trim().takeIf { it.isNotBlank() },
                        chronicDiseases = chronicDiseases.trim().takeIf { it.isNotBlank() },
                        allergies = allergies.trim().takeIf { it.isNotBlank() },
                        medications = medications.trim().takeIf { it.isNotBlank() }
                    )

                    // Log for debugging
                    Log.d("PatientSaveDebug", "--- SAVING PATIENT ---")
                    Log.d("PatientSaveDebug", "Allergies being saved: ${patientToSave.allergies}")

                    viewModel.save(patientToSave)

                    coroutineScope.launch {
                        snackbar.showSnackbar("Patient saved ✓")
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save Patient")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Full Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        isError = name.isBlank()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = ageText,
                            onValueChange = { ageText = it.filter { char -> char.isDigit() } },
                            label = { Text("Age") },
                            leadingIcon = { Icon(Icons.Default.Cake, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            isError = ageText.isBlank()
                        )
                        OutlinedTextField(
                            value = gender,
                            onValueChange = { gender = it },
                            label = { Text("Gender") },
                            leadingIcon = { Icon(Icons.Default.Wc, contentDescription = null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bloodType,
                        onValueChange = { bloodType = it },
                        label = { Text("Blood Type") },
                        leadingIcon = { Icon(Icons.Default.InvertColors, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = emergencyContact,
                        onValueChange = { emergencyContact = it },
                        label = { Text("Emergency Contact") },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    // Medical History Fields as Strings
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pastSurgeries,
                        onValueChange = { pastSurgeries = it },
                        label = { Text("Past Surgeries") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = false,
                        maxLines = 3
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = chronicDiseases,
                        onValueChange = { chronicDiseases = it },
                        label = { Text("Chronic Diseases") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = false,
                        maxLines = 3
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = allergies,
                        onValueChange = { allergies = it },
                        label = { Text("Allergies") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = false,
                        maxLines = 3
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = medications,
                        onValueChange = { medications = it },
                        label = { Text("Current Medications") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = false,
                        maxLines = 3
                    )
                }
            }
        }
    }
}
