package com.m365bleapp.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.m365bleapp.R
import com.m365bleapp.utils.TelemetryLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val logger = remember { TelemetryLogger(context) }
    
    var logFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    
    // Logging enabled state
    var loggingEnabled by remember { mutableStateOf(logger.isLoggingEnabled()) }
    
    // Filter tabs
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.log_tab_all),
        stringResource(R.string.log_tab_telemetry),
        stringResource(R.string.log_tab_ble)
    )
    
    // Load log files
    fun refreshFiles() {
        logFiles = when (selectedTab) {
            1 -> logger.getTelemetryLogFiles()
            2 -> logger.getBleLogFiles()
            else -> logger.getLogFiles()
        }
    }
    
    LaunchedEffect(selectedTab) {
        refreshFiles()
    }
    
    // Share file
    fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_share_title)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteConfirmDialog && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.log_delete_confirm_title)) },
            text = { Text(stringResource(R.string.log_delete_confirm_message, selectedFile!!.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        logger.deleteLogFile(selectedFile!!)
                        selectedFile = null
                        fileContent = null
                        showDeleteConfirmDialog = false
                        refreshFiles()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.log_delete_all_title)) },
            text = { Text(stringResource(R.string.log_delete_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        logger.deleteAllLogs()
                        selectedFile = null
                        fileContent = null
                        showDeleteAllDialog = false
                        refreshFiles()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (selectedFile != null) selectedFile!!.name
                        else stringResource(R.string.log_viewer_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedFile != null) {
                            selectedFile = null
                            fileContent = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (selectedFile != null) {
                        IconButton(onClick = { shareFile(selectedFile!!) }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                        }
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    } else {
                        IconButton(onClick = { refreshFiles() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                        if (logFiles.isNotEmpty()) {
                            IconButton(onClick = { showDeleteAllDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.log_delete_all))
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (selectedFile != null && fileContent != null) {
                // File content view
                FileContentView(
                    content = fileContent!!,
                    fileName = selectedFile!!.name
                )
            } else {
                // File list view
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
                
                // Logging toggle switch
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.log_enable_logging),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = loggingEnabled,
                            onCheckedChange = { enabled ->
                                loggingEnabled = enabled
                                logger.setLoggingEnabled(enabled)
                            }
                        )
                    }
                }
                
                if (logFiles.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.log_no_files),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(logFiles) { file ->
                            LogFileItem(
                                file = file,
                                onClick = {
                                    selectedFile = file
                                    fileContent = logger.readLogFile(file)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogFileItem(
    file: File,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val fileType = when {
        file.name.contains("telemetry") -> stringResource(R.string.log_type_telemetry)
        file.name.contains("ble") -> stringResource(R.string.log_type_ble)
        else -> stringResource(R.string.log_type_legacy)
    }
    
    ListItem(
        headlineContent = { 
            Text(
                text = file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = "$fileType • ${formatFileSize(file.length())}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = dateFormat.format(Date(file.lastModified())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}

@Composable
private fun FileContentView(
    content: String,
    fileName: String
) {
    val isCsv = fileName.endsWith(".csv")
    
    if (isCsv) {
        CsvTableView(content = content)
    } else {
        // Plain text view
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = content,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun CsvTableView(content: String) {
    val lines = content.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return
    
    val headers = parseCsvLine(lines.first())
    val rows = lines.drop(1).map { parseCsvLine(it) }
    
    val horizontalScrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(horizontalScrollState)
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            headers.forEach { header ->
                Text(
                    text = header,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .width(120.dp)
                        .padding(4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        HorizontalDivider(thickness = 2.dp)
        
        // Data rows
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(rows) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    row.forEachIndexed { _, cell ->
                        Text(
                            text = cell,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .width(120.dp)
                                .padding(4.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Pad with empty cells if row is shorter than headers
                    repeat(headers.size - row.size) {
                        Spacer(modifier = Modifier.width(120.dp))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var inQuotes = false
    
    for (char in line) {
        when {
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                result.add(current.toString().trim())
                current = StringBuilder()
            }
            else -> current.append(char)
        }
    }
    result.add(current.toString().trim())
    
    return result
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
