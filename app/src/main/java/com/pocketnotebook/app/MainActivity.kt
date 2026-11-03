package com.pocketnotebook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketnotebook.app.ui.theme.PocketNotebookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketNotebookTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "PocketNotebook",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "A local-first research companion. Sources, grounded chat, " +
                "and audio overviews - all generated on-device, all offline.",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider()
        Text(
            text = "Scaffold build. See docs/PLAN.md for the roadmap and " +
                "docs/MODEL_MATRIX.md for the on-device model strategy.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
