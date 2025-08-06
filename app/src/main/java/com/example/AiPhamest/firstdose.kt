package com.example.AiPhamest

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.AiPhamest.data.AppVMFactory
import com.example.AiPhamest.data.PrescriptionEntity
import com.example.AiPhamest.data.PrescriptionViewModel
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartFirstDoseButton(
    /**
     * Optional injection from a parent; if null we create one locally.
     * Again, no composable calls in a default value!
     */
    prescriptionVM: PrescriptionViewModel? = null
) {
    val ctx = LocalContext.current
    val vm: PrescriptionViewModel = prescriptionVM ?: viewModel(
        factory = AppVMFactory(ctx.applicationContext as Application)
    )

    /* ---------- UI state ---------- */
    val prescriptions by vm.list.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    /* ---------- main button ---------- */
    Button(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Start First Dose") }

    /* ---------- pop‑up medicine picker ---------- */
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Choose medicine") },
            confirmButton = {},
            text = {
                Column(Modifier.padding(vertical = 8.dp)) {
                    prescriptions.forEach { pres ->
                        MedicineRow(pres) {
                            showDialog = false
                            vm.createFirstDose(
                                pres.id,
                                LocalTime.now().withSecond(0).withNano(0)
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun MedicineRow(pres: PrescriptionEntity, onSelect: () -> Unit) {
    Text(
        pres.medicine,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 12.dp, horizontal = 4.dp)
    )
}
