package dev.mindmax.v4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mindmax.v4.ui.nav.NavGraph
import dev.mindmax.v4.ui.theme.MindMaxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MindMaxTheme {
                NavGraph()
            }
        }
    }
}
