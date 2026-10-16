package app.sbh.reader.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.sbh.reader.PermissionsRationaleActivity
import app.sbh.reader.R
import app.sbh.reader.ui.components.SbhIcon
import app.sbh.reader.ui.config.ConfigScreen
import app.sbh.reader.ui.config.ConfigViewModel
import app.sbh.reader.ui.config.GuideViewModel
import app.sbh.reader.ui.config.LogViewModel
import app.sbh.reader.ui.config.PowerGuideScreen
import app.sbh.reader.ui.data.DataScreen
import app.sbh.reader.ui.data.DataViewModel
import app.sbh.reader.ui.data.InventoryScreen
import app.sbh.reader.ui.data.InventoryViewModel
import app.sbh.reader.ui.link.LinkScreen
import app.sbh.reader.ui.link.LinkViewModel
import app.sbh.reader.ui.log.LogScreen
import app.sbh.reader.ui.theme.LocalReduceMotion
import app.sbh.reader.ui.theme.Motion
import app.sbh.reader.ui.theme.SbhTheme
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

// Typed routes (PROJECT_GUIDE §3.1). The four top-level destinations are the bottom navigation.
@Serializable data object LinkRoute
@Serializable data object DataRoute
@Serializable data object LogRoute
@Serializable data object ConfigRoute
@Serializable data object InventoryRoute
@Serializable data object PowerGuideRoute

/**
 * READ_EXERCISE_ROUTES cannot be requested with the permission dialog: open the per-app Health Connect permission
 * screen (framework action on Android 14+, the HC app's action before), else the general HC settings.
 */
private fun openRoutePermission(context: android.content.Context) {
    val attempts = listOf(
        Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName),
        Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName),
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return
        } catch (_: RuntimeException) {
            // ActivityNotFoundException / SecurityException: try the next, less specific screen
        }
    }
}

private data class TopLevel(val route: Any, val labelRes: Int, val iconRes: Int, val isCurrent: (androidx.navigation.NavDestination) -> Boolean)

private val topLevels = listOf(
    TopLevel(LinkRoute, R.string.nav_link, R.drawable.ic_link) { it.hasRoute<LinkRoute>() },
    TopLevel(DataRoute, R.string.nav_data, R.drawable.ic_database) { it.hasRoute<DataRoute>() },
    TopLevel(LogRoute, R.string.nav_log, R.drawable.ic_scroll) { it.hasRoute<LogRoute>() },
    TopLevel(ConfigRoute, R.string.nav_config, R.drawable.ic_gear_six) { it.hasRoute<ConfigRoute>() },
)

@Composable
fun AppRoot(main: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val c = SbhTheme.colors
    val reduce = LocalReduceMotion.current
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination

    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        main.onPermissionsResult()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { main.refreshAccess() }

    LaunchedEffect(Unit) {
        main.events.collect { event ->
            when (event) {
                is UiEvent.RequestPermissions -> permissionLauncher.launch(event.permissions)
                UiEvent.RequestNotificationPermission ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                UiEvent.OpenHealthConnectSettings -> try {
                    context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    // no HC settings screen available
                }
                UiEvent.OpenRoutePermission -> openRoutePermission(context)
                UiEvent.OpenHealthConnectStore -> {
                    val market = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"),
                    ).setPackage("com.android.vending")
                    try {
                        context.startActivity(market)
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")),
                        )
                    }
                }
            }
        }
    }

    val showBar = destination != null && topLevels.any { it.isCurrent(destination) }
    val gridColor = c.accentGlow.copy(alpha = 0.04f)

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .drawBehind {
                // very faint HUD grid (<= 4% opacity, §8.3); static, so it is fine with reduced motion
                val step = 32.dp.toPx()
                var x = 0f
                while (x < size.width) { drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                var y = 0f
                while (y < size.height) { drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            },
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                if (showBar) {
                    NavigationBar(containerColor = c.surface, contentColor = c.fg) {
                        topLevels.forEach { item ->
                            val selected = destination?.let(item.isCurrent) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(item.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { SbhIcon(item.iconRes, null) },
                                label = { Text(stringResource(item.labelRes).uppercase(), style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = c.accentGlow,
                                    selectedTextColor = c.accentGlow,
                                    unselectedIconColor = c.fgMuted,
                                    unselectedTextColor = c.fgMuted,
                                    indicatorColor = c.surfaceMuted,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = LinkRoute,
                enterTransition = { if (reduce) EnterTransition.None else fadeIn(tween(Motion.STATE_MS)) },
                exitTransition = { if (reduce) ExitTransition.None else fadeOut(tween(Motion.EXIT_MS)) },
                popEnterTransition = { if (reduce) EnterTransition.None else fadeIn(tween(Motion.STATE_MS)) },
                popExitTransition = { if (reduce) ExitTransition.None else fadeOut(tween(Motion.EXIT_MS)) },
            ) {
                composable<LinkRoute> {
                    LinkScreen(
                        vm = koinViewModel<LinkViewModel>(), main = main, padding = padding,
                        onOpenConfig = { nav.navigate(ConfigRoute) { launchSingleTop = true } },
                        onOpenGuide = { nav.navigate(PowerGuideRoute) },
                    )
                }
                composable<DataRoute> {
                    DataScreen(koinViewModel<DataViewModel>(), main, padding, onOpenInventory = { nav.navigate(InventoryRoute) })
                }
                composable<LogRoute> { LogScreen(koinViewModel<LogViewModel>(), padding) }
                composable<ConfigRoute> {
                    ConfigScreen(
                        vm = koinViewModel<ConfigViewModel>(), main = main, padding = padding,
                        onOpenGuide = { nav.navigate(PowerGuideRoute) },
                        onOpenRationale = { context.startActivity(Intent(context, PermissionsRationaleActivity::class.java)) },
                    )
                }
                composable<InventoryRoute> {
                    InventoryScreen(koinViewModel<InventoryViewModel>(), main, padding, onBack = { nav.popBackStack() })
                }
                composable<PowerGuideRoute> {
                    PowerGuideScreen(koinViewModel<GuideViewModel>(), padding, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
