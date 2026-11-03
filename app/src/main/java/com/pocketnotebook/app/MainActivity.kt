package com.pocketnotebook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.pocketnotebook.app.ui.AppRoot
import com.pocketnotebook.app.ui.theme.PocketNotebookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as App).container
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
