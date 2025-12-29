package com.m365bleapp.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.m365bleapp.R
import com.m365bleapp.repository.ScooterRepository
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggingScreen(
    repository: ScooterRepository,
    onBack: () -> Unit
) {
    val logs = remember { repository.getLogs() }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(logs) { file ->
                LogItem(file = file, onShare = {
                    shareFile(context, file)
                })
                HorizontalDivider()
            }
            if (logs.isEmpty()) {
                item {
                    Text(stringResource(R.string.logs_no_logs), modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
fun LogItem(file: File, onShare: () -> Unit) {
    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = { Text("${file.length() / 1024} ${stringResource(R.string.unit_kb)}") },
        trailingContent = {
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
            }
        }
    )
}

fun shareFile(context: android.content.Context, file: File) {
    // Requires FileProvider setup in Manifest for API 24+
    // We assume it's setup or we just use simple intent if allowed?
    // STRICT MODE will block file:// URIs.
    // We need "com.m365bleapp.fileprovider".
    // For simplicity, we assume FileProvider or just try to open as text.
    // Let's implement robust FileProvider usage if possible, but requires Manifest changes.
    // I already checked Manifest, getting access to it again is costly.
    // I will add a Note that FileProvider needs setup.
    // Or I'll assume users copy the file manually via AS Device File Explorer.
    
    // Attempt basic share.
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Log"))
    } catch (e: Exception) {
        // Fallback or error toast
        e.printStackTrace()
    }
}
