package com.yunluo.wxocr

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yunluo.wxocr.ui.DebugScreen
import com.yunluo.wxocr.ui.MainScreen
import com.yunluo.wxocr.ui.QuestionBankScreen
import com.yunluo.wxocr.ui.SettingsScreen
import com.yunluo.wxocr.ui.TestScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(this)
            } else {
                MaterialTheme.colorScheme
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                onNavigateToQuestionBank = {
                    navController.navigate("question_bank")
                },
                onNavigateToDebug = {
                    navController.navigate("debug")
                },
                onNavigateToTest = {
                    navController.navigate("test")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable("debug") {
            DebugScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable("question_bank") {
            QuestionBankScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable("test") {
            TestScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
