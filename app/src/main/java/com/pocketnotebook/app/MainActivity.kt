package com.pocketnotebook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.pocketnotebook.app.ui.AppRoot
import com.pocketnotebook.app.ui.theme.PocketNotebookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        val bootError = app.bootError
        setContent {
            if (bootError != null) {
                // Startup failed before the DI container existed — show the
                // reason instead of crash-looping. Copy it to us for a fix.
                PocketNotebookTheme(themeMode = "dark") {
                    BootErrorScreen(bootError)
                }
            } else {
                val container = app.container
                var theme by remember { mutableStateOf(container.prefs.themeMode) }
                LaunchedEffect(container) {
                    container.prefs.themeFlow.collect { theme = it }
                }
                PocketNotebookTheme(themeMode = theme) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun BootErrorScreen(error: String) {
    val clipboard = LocalClipboardManager.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PocketNotebook couldn't start", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Something failed during startup. This screen replaces a crash — " +
                    "please copy the report below and send it back so it can be fixed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { clipboard.setText(AnnotatedString(error)) },
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text("Copy error report")
            }
        }
    }
}
