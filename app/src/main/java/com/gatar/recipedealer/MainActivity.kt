package com.gatar.recipedealer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gatar.recipedealer.ui.CaptureDetailScreen
import com.gatar.recipedealer.ui.CaptureListScreen
import com.gatar.recipedealer.ui.theme.RecipeDealerTheme

class MainActivity : ComponentActivity() {
    /** Capture to open (from a notification or a foreground share); consumed by the nav host. */
    private var pendingCapture by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingCapture = intent.getStringExtra(EXTRA_CAPTURE_ID)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            RecipeDealerTheme {
                val nav = rememberNavController()
                LaunchedEffect(pendingCapture) {
                    pendingCapture?.let { id ->
                        nav.navigate(Routes.capture(id)) { launchSingleTop = true }
                        pendingCapture = null
                    }
                }
                AppNavHost(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CAPTURE_ID)?.let { pendingCapture = it }
    }

    companion object {
        private const val EXTRA_CAPTURE_ID = "captureId"

        fun openCaptureIntent(context: Context, captureId: String) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_CAPTURE_ID, captureId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

object Routes {
    const val LIST = "list"
    const val CAPTURE = "capture/{id}"
    fun capture(id: String) = "capture/$id"
}

@Composable
private fun AppNavHost(nav: NavHostController) {
    NavHost(nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            CaptureListScreen(onOpen = { nav.navigate(Routes.capture(it)) })
        }
        composable(Routes.CAPTURE) { back ->
            CaptureDetailScreen(id = back.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
