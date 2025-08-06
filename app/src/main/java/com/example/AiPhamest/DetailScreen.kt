package com.example.AiPhamest

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


@Composable
fun DetailScreen(itemId: String?) {
    Text("Detail Screen for: $itemId", modifier = Modifier.padding(16.dp))
}


