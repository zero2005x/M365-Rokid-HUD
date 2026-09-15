package com.m365bleapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.m365bleapp.pairing.validSerial

@Composable
fun PairingSerialDialog(deviceName: String, onSubmit: (String) -> Boolean, onCancel: () -> Unit) {
    var serial by remember(deviceName) { mutableStateOf("") }
    var mismatch by remember(deviceName) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("輸入車身序號") },
        text = {
            Column {
                Text(deviceName)
                Text("請依車身標示輸入 14 位英文字母、數字或 /。序號須與目前連線車輛一致。")
                Text("Xiaomi 格式：12345/12345678；Ninebot 為 14 位英文字母與數字。")
                OutlinedTextField(
                    value = serial,
                    onValueChange = { serial = it; mismatch = false },
                    label = { Text("車身序號") },
                    singleLine = true,
                    isError = mismatch,
                )
                if (mismatch) Text("序號不符，請核對車身標示及目前連線的車輛。")
            }
        },
        confirmButton = {
            TextButton(enabled = validSerial(serial), onClick = { mismatch = !onSubmit(serial) }) { Text("確認序號") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消配對") } },
    )
}
