package com.m365bleapp.bond

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.m365bleapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun Context.activity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Owns sensitive flow buffers; late IO results are wiped after navigation away. */
private class BondScreenSession : AutoCloseable {
    private var closed = false
    private var sealed: ByteArray? = null
    private var source: ByteArray? = null
    private var preview: BondDocument? = null
    private val passwords = mutableSetOf<CharArray>()
    private val copies = mutableSetOf<BondDocument>()
    private val writing = mutableSetOf<ByteArray>()
    val permit = ExportPermit(SystemClock::elapsedRealtime)
    var signal: CancellationSignal? = null
    @Synchronized fun sealed(bytes: ByteArray) { if (closed) bytes.fill(0) else { sealed?.fill(0); sealed = bytes } }
    @Synchronized fun takeSealed(): ByteArray? = sealed.also { if (it != null) writing.add(it); sealed = null }
    @Synchronized fun release(bytes: ByteArray) { bytes.fill(0); writing.remove(bytes) }
    @Synchronized fun hold(password: CharArray) { if (closed) password.fill('\u0000') else passwords.add(password) }
    @Synchronized fun release(password: CharArray) { password.fill('\u0000'); passwords.remove(password) }
    @Synchronized fun source(bytes: ByteArray) { if (closed) bytes.fill(0) else { source?.fill(0); source = bytes } }
    @Synchronized fun sourceCopy(): ByteArray = requireNotNull(source).copyOf()
    @Synchronized fun preview(document: BondDocument) { if (closed) document.close() else { preview?.close(); preview = document } }
    @Synchronized fun entriesCopy(): BondDocument = BondDocument(requireNotNull(preview).entries.map { it.duplicate() }).also { copies.add(it) }
    @Synchronized fun release(document: BondDocument) { document.close(); copies.remove(document) }
    @Synchronized fun clear() {
        sealed?.fill(0); source?.fill(0); preview?.close()
        passwords.forEach { it.fill('\u0000') }; passwords.clear()
        copies.forEach { it.close() }; copies.clear()
        writing.forEach { it.fill(0) }; writing.clear()
        sealed = null; source = null; preview = null; permit.clear()
    }
    @Synchronized override fun close() { closed = true; signal?.cancel(); clear() }
}

private enum class BondTransferMode { IMPORT, EXPORT }

/** State and operations are separate from the Compose rendering functions. */
private class BondScreenState(
    val store: BondStore,
    val context: Context,
    var resources: Resources,
    val scope: CoroutineScope,
) {
    val activity = context.activity()
    val session = BondScreenSession()
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    var createDocument: (String) -> Unit = {}
    var openDocument: () -> Unit = {}
    var authenticateLegacy: (Intent) -> Unit = {}
    var entries by mutableStateOf(emptyList<BondSummary>())
    var selected by mutableStateOf(emptySet<String>())
    var preview by mutableStateOf<List<BondSummary>?>(null)
    var importSelected by mutableStateOf(emptySet<String>())
    var replace by mutableStateOf(emptySet<String>())
    var skipped by mutableIntStateOf(0)
    var passphraseMode by mutableStateOf<BondTransferMode?>(null)
    var password by mutableStateOf("")
    var confirm by mutableStateOf("")
    var manual by mutableStateOf(false)
    var mac by mutableStateOf("")
    var credential by mutableStateOf("")
    var label by mutableStateOf("")
    var family by mutableStateOf(BondFamily.XIAOMI)
    var manualConflict by mutableStateOf(false)
    var deleteMac by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var dialogError by mutableStateOf<String?>(null)
    var authenticating by mutableStateOf(false)

    fun reset() {
        password = ""; confirm = ""; credential = ""; mac = ""; label = ""
        passphraseMode = null; manual = false; manualConflict = false; preview = null; dialogError = null
        session.clear()
    }
    suspend fun refresh() {
        entries = withContext(Dispatchers.IO) { store.list() }
        selected = entries.map { it.mac }.toSet()
    }
    suspend fun load() {
        busy = true
        try { withContext(Dispatchers.IO) { store.migrateXiaomi() }; refresh() }
        catch (_: Exception) { message = resources.getString(R.string.pairing_operation_failed) }
        finally { busy = false }
    }
    fun writeDocument(uri: Uri?) {
        val bytes = session.takeSealed()
        if (uri == null || bytes == null) { bytes?.let { session.release(it) }; session.permit.clear() }
        else {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { BondFiles.write(context, uri, bytes, session.permit) }
                    message = resources.getString(R.string.pairing_export_done)
                } catch (_: Exception) { message = resources.getString(R.string.bond_export_expired_or_failed) }
                finally { session.release(bytes); session.permit.clear(); busy = false }
            }
        }
    }
    fun readDocument(uri: Uri?) {
        if (uri != null) {
            reset(); busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { session.source(BondFiles.read(context, uri)) }
                    passphraseMode = BondTransferMode.IMPORT
                } catch (e: Exception) {
                    message = resources.getString(if (e is UnsupportedBondBackup || uri.scheme != "content")
                        R.string.bond_invalid_file else R.string.bond_wrong_password_or_damage)
                    session.clear()
                }
                finally { busy = false }
            }
        }
    }
    fun confirmed() {
        authenticating = false
        if (keyguard?.isDeviceSecure == true) {
            session.permit.grant(true); passphraseMode = BondTransferMode.EXPORT; dialogError = null
        } else message = resources.getString(R.string.bond_screen_lock_required)
    }
    fun authenticate() {
        if (selected.isEmpty()) { message = resources.getString(R.string.bond_select_one); return }
        if (keyguard?.isDeviceSecure != true || activity == null) {
            message = resources.getString(R.string.bond_screen_lock_required); return
        }
        authenticating = true
        try {
            if (Build.VERSION.SDK_INT == 28) {
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent(resources.getString(R.string.bond_auth_title), null)
                if (intent == null) { authenticating = false; message = resources.getString(R.string.bond_screen_lock_required) }
                else authenticateLegacy(intent)
            } else {
                val builder = BiometricPrompt.Builder(context).setTitle(resources.getString(R.string.bond_auth_title))
                if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                else { @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true) }
                val signal = CancellationSignal().also { session.signal = it }
                builder.build().authenticate(signal, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { confirmed() }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { authenticating = false }
                })
            }
        } catch (_: Exception) { authenticating = false; message = resources.getString(R.string.pairing_operation_failed) }
    }
    fun savePreview(choices: Set<String>, replacements: Set<String>) {
        val document = session.entriesCopy()
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    document.use { doc -> store.importEntries(doc.entries, choices, replacements) }
                }
                reset(); refresh()
                message = resources.getString(R.string.bond_import_result, result.added, result.replaced, result.kept)
            } catch (_: Exception) { message = resources.getString(R.string.pairing_operation_failed) }
            finally { session.release(document); busy = false }
        }
    }

    fun submitPassphrase(mode: BondTransferMode) {
        val pass = password.toCharArray()
        val confirmation = confirm.toCharArray()
        try {
            if (mode == BondTransferMode.EXPORT && !BondInput.passwords(pass, confirmation)) {
                pass.fill('\u0000')
                dialogError = resources.getString(R.string.bond_password_invalid)
                return
            }
            if (mode == BondTransferMode.EXPORT && !session.permit.valid()) {
                pass.fill('\u0000'); reset()
                message = resources.getString(R.string.bond_export_expired_or_failed)
                return
            }
        } finally { confirmation.fill('\u0000') }
        session.hold(pass); password = ""; confirm = ""; busy = true
        val choices = selected.toSet()
        scope.launch {
            try {
                when (mode) {
                    BondTransferMode.EXPORT -> sealExport(choices, pass)
                    BondTransferMode.IMPORT -> openImport(pass)
                }
            } catch (_: Exception) {
                transferFailed(mode)
            } finally { session.release(pass); busy = false }
        }
    }
    private suspend fun sealExport(choices: Set<String>, pass: CharArray) {
        withContext(Dispatchers.Default) {
            store.export(choices).use { document ->
                require(document.entries.isNotEmpty())
                session.sealed(BondEnvelope.seal(document.entries, pass))
            }
        }
        check(session.permit.valid())
        passphraseMode = null
        createDocument("m365-bonds-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.rfbond")
    }
    private suspend fun openImport(pass: CharArray) {
        withContext(Dispatchers.Default) {
            val file = session.sourceCopy()
            try {
                val doc = BondEnvelope.open(file, pass)
                session.preview(doc)
                preview = doc.entries.map { BondSummary(it.mac, it.family, it.label, it.model) }
                skipped = doc.skipped
            } finally { file.fill(0) }
        }
        importSelected = preview.orEmpty().map { it.mac }.toSet(); replace = emptySet()
        passphraseMode = null
    }
    private fun transferFailed(mode: BondTransferMode) {
        if (mode == BondTransferMode.IMPORT) {
            dialogError = resources.getString(R.string.bond_wrong_password_or_damage)
        } else {
            reset(); message = resources.getString(R.string.bond_export_expired_or_failed)
        }
    }
    fun submitManualEntry() {
        val chars = credential.toCharArray()
        var bytes: ByteArray? = null
        try {
            bytes = BondInput.hex(chars, family.bytes, manual = true)
            val entry = BondEntry(BondInput.mac(mac), family, bytes, label.ifBlank { null })
            bytes = null // Entry now owns it.
            session.preview(BondDocument(listOf(entry))); credential = ""; manual = false
            checkManualConflict(entry)
        } catch (_: Exception) { dialogError = resources.getString(R.string.bond_manual_invalid) }
        finally { chars.fill('\u0000'); bytes?.fill(0) }
    }
    private fun checkManualConflict(entry: BondEntry) {
        busy = true
        scope.launch {
            try {
                entries = withContext(Dispatchers.IO) { store.list() }
                busy = false
                if (entries.any { it.mac == entry.mac }) manualConflict = true
                else savePreview(setOf(entry.mac), emptySet())
            } catch (_: Exception) {
                busy = false; reset(); message = resources.getString(R.string.pairing_operation_failed)
            }
        }
    }

}

@Composable
fun PairingKeysScreen(store: BondStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state = remember(store, context) { BondScreenState(store, context, resources, scope) }
    state.resources = resources
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        val secure = WindowManager.LayoutParams.FLAG_SECURE
        state.activity?.window?.addFlags(secure)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.activity?.window?.addFlags(secure)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.session.close()
            state.reset()
            state.activity?.window?.clearFlags(secure)
        }
    }
    LaunchedEffect(state) { state.load() }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), state::writeDocument)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), state::readDocument)
    val deviceAuth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) state.confirmed() else state.authenticating = false
    }
    state.createDocument = { create.launch(it) }
    state.openDocument = { open.launch(arrayOf("application/octet-stream", "*/*")) }
    state.authenticateLegacy = { deviceAuth.launch(it) }
    state.Content(onBack)
    state.PassphraseDialog()
    state.ImportPreviewDialog()
    state.ManualEntryDialog()
    state.ReplacementDialog()
    state.DeletionDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BondScreenState.Content(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(expandedHeight = 64.dp * LocalDensity.current.fontScale.coerceAtLeast(1f), title = { Text(stringResource(R.string.pairing_title),
        style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
        IconButton(onClick = { reset(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.pairing_help), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.pairing_count, entries.size), style = MaterialTheme.typography.titleMedium)
            entries.forEach { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).toggleable(entry.mac in selected, enabled = !busy && !authenticating,
                            role = Role.Checkbox, onValueChange = {
                                selected = if (it) selected + entry.mac else selected - entry.mac
                            }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(entry.mac in selected, onCheckedChange = null, enabled = !busy && !authenticating)
                            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Summary(entry) }
                        }
                        IconButton(enabled = !busy && !authenticating, onClick = { deleteMac = entry.mac }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.bond_delete) + ": " + entry.maskedMac)
                        }
                    }
                }
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && !authenticating && selected.isNotEmpty(), onClick = { authenticate() }) { Text(stringResource(R.string.pairing_export)) }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && !authenticating, onClick = { openDocument() }) { Text(stringResource(R.string.pairing_import)) }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && !authenticating, onClick = { reset(); family = BondFamily.XIAOMI; manual = true }) { Text(stringResource(R.string.pairing_manual)) }
            if (busy || authenticating) CircularProgressIndicator()
            message?.let { Text(it) }
        }
    }
}

@Composable private fun BondScreenState.PassphraseDialog() {
    passphraseMode?.let { mode ->
        BondDialog(
            onDismissRequest = { if (!busy) reset() }, title = { Text(stringResource(if (mode == BondTransferMode.EXPORT) R.string.pairing_export else R.string.pairing_import), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (mode == BondTransferMode.EXPORT) Text(stringResource(R.string.pairing_password_help))
                    SecretField(password, { password = it }, R.string.pairing_password)
                    if (mode == BondTransferMode.EXPORT) SecretField(confirm, { confirm = it }, R.string.pairing_confirm_password)
                    dialogError?.let { Text(it) }
                }
            }, confirmButton = {
                TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && password.isNotEmpty(), onClick = {
                    submitPassphrase(mode)
                }) { Text(stringResource(R.string.pairing_save)) }
            }, dismissButton = { TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { reset() }) { Text(stringResource(R.string.cancel)) } })
    }

}

@Composable private fun BondScreenState.ImportPreviewDialog() {
    preview?.let { rows ->
        BondDialog(
            onDismissRequest = { if (!busy) reset() }, title = { Text(stringResource(R.string.bond_preview), style = MaterialTheme.typography.titleLarge) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.bond_skipped, skipped))
                rows.forEach { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Checkbox(entry.mac in importSelected, enabled = !busy, onCheckedChange = {
                            importSelected = if (it) importSelected + entry.mac else importSelected - entry.mac
                        })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Summary(entry)
                            if (entries.any { it.mac == entry.mac }) {
                                Row(Modifier.fillMaxWidth().toggleable(entry.mac in replace,
                                    enabled = !busy && entry.mac in importSelected, role = Role.Checkbox, onValueChange = {
                                    replace = if (it) replace + entry.mac else replace - entry.mac
                                }), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(entry.mac in replace, enabled = !busy && entry.mac in importSelected, onCheckedChange = null)
                                    Text(stringResource(R.string.pairing_replace_existing), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && importSelected.isNotEmpty(), onClick = { savePreview(importSelected, replace) }) { Text(stringResource(R.string.pairing_import)) } },
            dismissButton = { TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { reset() }) { Text(stringResource(R.string.cancel)) } })
    }

}

@Composable private fun BondScreenState.ManualEntryDialog() {
    val macDescription = stringResource(R.string.pairing_mac)
    val labelDescription = stringResource(R.string.bond_label)
    if (manual) {
        BondDialog(
            onDismissRequest = { if (!busy) reset() }, title = { Text(stringResource(R.string.pairing_manual), style = MaterialTheme.typography.titleLarge) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pairing_manual_help))
                BondFamily.entries.forEach { kind -> Row(Modifier.fillMaxWidth().selectable(family == kind,
                    enabled = !busy, role = Role.RadioButton, onClick = { family = kind }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(family == kind, onClick = null, enabled = !busy)
                    Text(stringResource(if (kind == BondFamily.XIAOMI) R.string.bond_xiaomi else R.string.bond_ninebot),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                } }
                Text(stringResource(R.string.pairing_mac), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(mac, { mac = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = macDescription }, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr))
                SecretField(credential, { credential = it }, R.string.pairing_token)
                Text(stringResource(if (family == BondFamily.XIAOMI) R.string.bond_bytes12 else R.string.bond_bytes16))
                Text(stringResource(R.string.bond_label), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(label, { label = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = labelDescription })
                dialogError?.let { Text(it) }
            }
        }, confirmButton = { TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
            submitManualEntry()
        }) { Text(stringResource(R.string.pairing_save)) } },
            dismissButton = { TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { reset() }) { Text(stringResource(R.string.cancel)) } })
    }

}

@Composable private fun BondScreenState.ReplacementDialog() {
    if (manualConflict) BondDialog(
        onDismissRequest = { if (!busy) reset() }, title = { Text(stringResource(R.string.pairing_replace_existing), style = MaterialTheme.typography.titleLarge) },
        text = { Text(stringResource(R.string.bond_replace_question)) },
        confirmButton = { TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
            session.entriesCopy().use { doc -> val address = doc.entries.single().mac; savePreview(setOf(address), setOf(address)) }
        }) { Text(stringResource(R.string.pairing_replace_existing)) } },
        dismissButton = { TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { reset() }) { Text(stringResource(R.string.cancel)) } })

}

@Composable private fun BondScreenState.DeletionDialog() {
    deleteMac?.let { address -> BondDialog(
        onDismissRequest = { deleteMac = null }, title = { Text(stringResource(R.string.bond_delete), style = MaterialTheme.typography.titleLarge) },
        text = { Text(stringResource(R.string.bond_delete_question)) }, confirmButton = {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { deleteMac = null; busy = true; scope.launch {
                try { withContext(Dispatchers.IO) { store.remove(address) }; refresh() }
                catch (_: Exception) { message = resources.getString(R.string.pairing_operation_failed) }
                finally { busy = false }
            } }) { Text(stringResource(R.string.bond_delete)) }
        }, dismissButton = { TextButton(modifier = Modifier.fillMaxWidth(), onClick = { deleteMac = null }) { Text(stringResource(R.string.cancel)) } }) }
}


@Composable private fun Summary(entry: BondSummary) {
    (entry.label?.takeIf { it.isNotBlank() } ?: entry.model)?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(entry.maskedMac, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
    }
    Text(stringResource(if (entry.family == BondFamily.XIAOMI) R.string.bond_xiaomi else R.string.bond_ninebot),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun SecretField(value: String, change: (String) -> Unit, label: Int) {
    val description = stringResource(label)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value, change,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description })
    }
}

/** Keep long translated actions in separate rows, including while the IME is visible. */
@Composable private fun BondDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.safeDrawingPadding().imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                confirmButton()
                dismissButton()
            }
        },
    )
}
