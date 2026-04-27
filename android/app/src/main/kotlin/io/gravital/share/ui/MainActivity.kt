package io.gravital.share.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.gravital.share.ui.screens.DiagnosticScreen
import io.gravital.share.ui.screens.HomeScreen
import io.gravital.share.ui.screens.SettingsScreen
import io.gravital.share.ui.theme.GravitalTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GravitalTheme {
                GravitalShareNavGraph()
            }
        }
    }
}

@Composable
fun GravitalShareNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenDiagnostic = { navController.navigate("diagnostic") },
                onOpenSettings   = { navController.navigate("settings") }
            )
        }
        composable("diagnostic") {
            DiagnosticScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
