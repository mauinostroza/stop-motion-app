package com.stopmotion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stopmotion.app.ui.capture.CaptureScreen
import com.stopmotion.app.ui.export.ExportScreen
import com.stopmotion.app.ui.frames.FramesScreen
import com.stopmotion.app.ui.preview.PreviewScreen
import com.stopmotion.app.ui.theme.StopMotionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StopMotionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavGraph()
                }
            }
        }
    }
}

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "frames") {
        composable("frames") {
            FramesScreen(
                onNavigateToCapture = { navController.navigate("capture") },
                onNavigateToPreview = { navController.navigate("preview") },
                onNavigateToExport = { navController.navigate("export") },
            )
        }
        composable("capture") {
            CaptureScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
        composable("preview") {
            PreviewScreen(
                onBack = { navController.popBackStack() },
                onExport = { navController.navigate("export") },
            )
        }
        composable("export") {
            ExportScreen(
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack("frames", inclusive = false) },
            )
        }
    }
}
