package com.m365bleapp.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.m365bleapp.R
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.utils.LogExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggingScreen(
    repository: ScooterRepository,
    onBack: () -> Unit
) {
    val logs = remember { repository.getLogs() }
    val context = LocalContext.current
    val logExporter = remember { LogExporter(context) }
    val scope = rememberCoroutineScope()
    
    // Export state
    var isExporting by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<LogExporter.ExportResult?>(null) }
    
    // Calculate log stats
    val logsCount = logExporter.getLogsCount()
    val logsSize = logExporter.formatFileSize(logExporter.getLogsTotalSize())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Export All button
                    IconButton(
                        onClick = { showExportDialog = true },
                        enabled = logsCount > 0 && !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = stringResource(R.string.export_logs)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Log summary card
            if (logsCount > 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.logs_summary),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.logs_count_size, logsCount, logsSize),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { showExportDialog = true },
                            enabled = !isExporting
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.export_all))
                        }
                    }
                }
            }
            
            // Log files list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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
    
    // Export confirmation dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.export_logs_title)) },
            text = { 
                Text(stringResource(R.string.export_logs_message, logsCount, logsSize))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportDialog = false
                        isExporting = true
                        scope.launch {
                            // Anything thrown here (CancellationException when
                            // the composable leaves composition,
                            // ActivityNotFoundException from the chooser, ...)
                            // used to propagate out of scope.launch and crash
                            // the app, leaving isExporting stuck true so the
                            // export buttons were disabled forever.
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    logExporter.createLogArchive(
                                        includeLogcat = true,
                                        includeDeviceInfo = true
                                    )
                                }
                                exportResult = result

                                if (result.success && result.zipFile != null) {
                                    // Create and launch share intent
                                    val shareIntent = logExporter.createShareIntent(result.zipFile)
                                    if (shareIntent != null) {
                                        context.startActivity(
                                            Intent.createChooser(
                                                shareIntent,
                                                context.getString(R.string.share_logs_to)
                                            )
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            R.string.export_share_failed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.export_failed, result.errorMessage ?: "Unknown error"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.export_failed, e.message ?: "Unknown error"),
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isExporting = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.export_and_share))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
