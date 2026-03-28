package com.m365bleapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun KofiButton(
    modifier: Modifier = Modifier,
    kofiUrl: String = "https://ko-fi.com/liangtinglin"
) {
    val context = LocalContext.current
    val kofiColor = Color(0xFFFF5E5B)

    Button(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(kofiUrl))
            context.startActivity(intent)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = kofiColor,
            contentColor = Color.White
        )
    ) {
        Icon(
            imageVector = Icons.Default.LocalCafe,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Support me on Ko-fi")
    }
}
