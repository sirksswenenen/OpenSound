package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soundcloud.lite.ui.MainViewModel

@Composable
fun DownloadsScreen(viewModel: MainViewModel) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "Offline downloads will live here.\nNot implemented in MVP.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
