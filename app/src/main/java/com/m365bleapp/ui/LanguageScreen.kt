package com.m365bleapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.m365bleapp.R
import com.m365bleapp.utils.LocaleHelper
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(
    onBack: () -> Unit,
    onLanguageChanged: () -> Unit
) {
    val context = LocalContext.current
    
    // Current selection state - track the initial values
    val initialUseSystemDefault = remember { LocaleHelper.isUsingSystemDefault(context) }
    val initialLanguage = remember { LocaleHelper.getSavedLanguage(context) }
    
    var useSystemDefault by remember { mutableStateOf(initialUseSystemDefault) }
    var selectedLanguage by remember { mutableStateOf(initialLanguage) }
    
    // Show restarting dialog
    var isRestarting by remember { mutableStateOf(false) }
    
    // Function to handle language change
    fun onSelectLanguage(language: LocaleHelper.LanguageOption?, isSystem: Boolean) {
        val hasActualChange = if (isSystem) {
            !initialUseSystemDefault
        } else {
            initialUseSystemDefault || 
                language?.code != initialLanguage?.code || 
                language?.country != initialLanguage?.country
        }
        
        if (hasActualChange) {
            useSystemDefault = isSystem
            selectedLanguage = language
            LocaleHelper.saveLanguage(context, language)
            isRestarting = true
        }
    }
    
    // Auto restart after showing dialog
    LaunchedEffect(isRestarting) {
        if (isRestarting) {
            delay(800) // Brief delay to show the dialog
            onLanguageChanged()
        }
    }
    
    // Restarting Dialog
    if (isRestarting) {
        Dialog(onDismissRequest = { }) {
            Card(
                modifier = Modifier.padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.language_restarting),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.language_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // System Default Option
            item {
                Text(
                    text = stringResource(R.string.language_system_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            
            item {
                ListItem(
                    headlineContent = { 
                        Text(
                            text = stringResource(R.string.language_system_default),
                            fontWeight = if (useSystemDefault) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    supportingContent = {
                        val systemLang = LocaleHelper.findMatchingLanguage(context)
                        val displayText = if (systemLang != null) {
                            "${systemLang.nativeName} (${systemLang.displayName})"
                        } else {
                            stringResource(R.string.language_system_not_supported)
                        }
                        Text(displayText)
                    },
                    leadingContent = {
                        RadioButton(
                            selected = useSystemDefault,
                            onClick = { onSelectLanguage(null, true) }
                        )
                    },
                    trailingContent = {
                        if (useSystemDefault) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier.clickable { onSelectLanguage(null, true) }
                )
                HorizontalDivider()
            }
            
            // Available Languages Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.language_available_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            
            items(LocaleHelper.supportedLanguages) { language ->
                val isSelected = !useSystemDefault && 
                    selectedLanguage?.code == language.code && 
                    selectedLanguage?.country == language.country
                
                ListItem(
                    headlineContent = { 
                        Text(
                            text = language.nativeName,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    supportingContent = { Text(language.displayName) },
                    leadingContent = {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectLanguage(language, false) }
                        )
                    },
                    trailingContent = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier.clickable { onSelectLanguage(language, false) }
                )
                HorizontalDivider()
            }
        }
    }
}
