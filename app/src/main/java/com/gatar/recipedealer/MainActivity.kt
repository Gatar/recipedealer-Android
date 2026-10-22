package com.gatar.recipedealer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.IconButton
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gatar.recipedealer.ui.CaptureDetailScreen
import com.gatar.recipedealer.ui.CaptureListScreen
import com.gatar.recipedealer.ui.DecisionScreen
import com.gatar.recipedealer.ui.InboxScreen
import com.gatar.recipedealer.ui.ManualEntryScreen
import com.gatar.recipedealer.ui.RecipeDetailScreen
import com.gatar.recipedealer.ui.RecipeEditScreen
import com.gatar.recipedealer.ui.RevisionsScreen
import com.gatar.recipedealer.ui.RecipeListScreen
import com.gatar.recipedealer.ui.SettingsScreen
import com.gatar.recipedealer.ui.theme.RecipeDealerTheme

class MainActivity : ComponentActivity() {
    /** Route to open (from a notification or a foreground share); consumed by the nav host. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingRoute = target(intent)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            RecipeDealerTheme {
                val nav = rememberNavController()
                LaunchedEffect(pendingRoute) {
                    pendingRoute?.let { route ->
                        nav.navigate(route) { launchSingleTop = true }
                        pendingRoute = null
                    }
                }
                AppNavHost(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        target(intent)?.let { pendingRoute = it }
    }

    private fun target(intent: Intent): String? = when {
        intent.getBooleanExtra(EXTRA_SETTINGS, false) -> Routes.SETTINGS
        intent.getStringExtra(EXTRA_DECISION_ID) != null -> Routes.decision(intent.getStringExtra(EXTRA_DECISION_ID)!!)
        else -> intent.getStringExtra(EXTRA_CAPTURE_ID)?.let(Routes::capture)
    }

    companion object {
        private const val EXTRA_CAPTURE_ID = "captureId"
        private const val EXTRA_DECISION_ID = "decisionCaptureId"
        private const val EXTRA_SETTINGS = "openSettings"

        fun openSettingsIntent(context: Context) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SETTINGS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun openCaptureIntent(context: Context, captureId: String) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_CAPTURE_ID, captureId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun openDecisionIntent(context: Context, captureId: String) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_DECISION_ID, captureId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

object Routes {
    const val RECIPES = "recipes"
    const val RECIPE = "recipe/{id}"
    const val EDIT = "recipe/{id}/edit?revision={revision}"
    const val REVISIONS = "recipe/{id}/revisions"
    const val LIST = "list"
    const val INBOX = "inbox"
    const val CAPTURE = "capture/{id}"
    const val DECISION = "decision/{id}"
    const val SETTINGS = "settings"
    const val MANUAL = "manual"
    fun capture(id: String) = "capture/$id"
    fun decision(id: String) = "decision/$id"
    fun recipe(id: String) = "recipe/$id"
    fun edit(id: String, revision: String? = null) = "recipe/$id/edit" + (revision?.let { "?revision=$it" } ?: "")
    fun revisions(id: String) = "recipe/$id/revisions"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.RECIPES, R.string.list_title, Icons.Default.Restaurant),
    Tab(Routes.INBOX, R.string.tab_inbox, Icons.Default.Inbox),
    Tab(Routes.LIST, R.string.tab_captures, Icons.Default.Share),
)

@Composable
private fun AppNavHost(nav: NavHostController) {
    val container = LocalContext.current.container
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val inbox by container.inbox.items.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        container.inbox.refresh()
        container.recipeSync.sync()
    }
    Scaffold(
        bottomBar = {
            if (TABS.any { it.route == route }) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == Routes.INBOX && inbox.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text(inbox.size.toString()) } }) { Icon(tab.icon, null) }
                                } else Icon(tab.icon, null)
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.RECIPES, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Routes.RECIPES) {
                RecipeListScreen(
                    onOpen = { nav.navigate(Routes.recipe(it)) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onManual = { nav.navigate(Routes.MANUAL) },
                )
            }
            composable(Routes.RECIPE) { back ->
                val id = back.arguments?.getString("id").orEmpty()
                RecipeDetailScreen(
                    recipeId = id,
                    onBack = { nav.popBackStack() },
                    onOpenRecipe = { nav.navigate(Routes.recipe(it)) { popUpTo(Routes.RECIPE) { inclusive = true } } },
                    actions = {
                        if (container.settings.current.hasBackend) {
                            IconButton(onClick = { nav.navigate(Routes.revisions(id)) }) {
                                Icon(Icons.Default.History, stringResource(R.string.revisions_title))
                            }
                            IconButton(onClick = { nav.navigate(Routes.edit(id)) }) {
                                Icon(Icons.Default.Edit, stringResource(R.string.recipe_edit))
                            }
                        }
                    },
                )
            }
            composable(
                Routes.EDIT,
                arguments = listOf(navArgument("revision") { type = NavType.StringType; nullable = true }),
            ) {
                RecipeEditScreen(
                    onClose = { nav.popBackStack() },
                    onDeleted = { nav.popBackStack(Routes.RECIPES, inclusive = false) },
                )
            }
            composable(Routes.REVISIONS) { back ->
                val id = back.arguments?.getString("id").orEmpty()
                RevisionsScreen(id, onBack = { nav.popBackStack() }, onApply = { rev ->
                    nav.navigate(Routes.edit(id, rev)) { popUpTo(Routes.RECIPE) }
                })
            }
            composable(Routes.LIST) {
                CaptureListScreen(
                    onOpen = { nav.navigate(Routes.capture(it)) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onManual = { nav.navigate(Routes.MANUAL) },
                )
            }
            composable(Routes.INBOX) { InboxScreen(onOpen = { nav.navigate(Routes.decision(it)) }) }
            composable(Routes.CAPTURE) { back ->
                CaptureDetailScreen(
                    id = back.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onDecide = { nav.navigate(Routes.decision(it)) },
                )
            }
            composable(Routes.DECISION) { back ->
                DecisionScreen(
                    captureId = back.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenRecipe = { nav.navigate(Routes.recipe(it)) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MANUAL) {
                ManualEntryScreen(onBack = { nav.popBackStack() }, onSaved = { id ->
                    nav.navigate(Routes.capture(id)) { popUpTo(nav.graph.findStartDestination().id) }
                })
            }
        }
    }
}
