package dev.blazelight.p4oc.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.blazelight.p4oc.core.network.ServerConnectionRegistry
import dev.blazelight.p4oc.ui.screens.server.ServerScreenConfig
import dev.blazelight.p4oc.ui.screens.server.serverScreen
import dev.blazelight.p4oc.ui.screens.settings.SettingsConnectionContext
import dev.blazelight.p4oc.ui.screens.settings.SettingsScreen
import dev.blazelight.p4oc.ui.screens.settings.SettingsViewModel
import dev.blazelight.p4oc.ui.screens.settings.VisualSettingsScreen
import dev.blazelight.p4oc.ui.screens.setup.SetupScreen
import dev.blazelight.p4oc.ui.tabs.MainTabScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private const val ANIMATION_DURATION = 300

/**
 * Root navigation graph.
 * Handles initial setup/server screens, then hands off to MainTabScreen
 * which manages its own per-tab navigation.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod")
fun NavGraph(
    navController: NavHostController,
    startDestination: String,
    pendingIntents: PendingIntents,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(ANIMATION_DURATION)
            ) + fadeIn(animationSpec = tween(ANIMATION_DURATION))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = tween(ANIMATION_DURATION)
            ) + fadeOut(animationSpec = tween(ANIMATION_DURATION))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(ANIMATION_DURATION)
            ) + fadeIn(animationSpec = tween(ANIMATION_DURATION))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(ANIMATION_DURATION)
            ) + fadeOut(animationSpec = tween(ANIMATION_DURATION))
        }
    ) {
        composable(Screen.Setup.route) {
            SetupScreen(
                onConnected = {
                    navController.navigate(Screen.Sessions.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Server.route) {
            serverScreen(
                onNavigateToSessions = {
                    navController.navigate(Screen.Sessions.route) {
                        popUpTo(Screen.Server.route) { inclusive = true }
                    }
                },
                onSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.ServerManagement.route) {
            val serverConnectionRegistry: ServerConnectionRegistry = koinInject()
            serverScreen(
                onNavigateToSessions = { navController.popBackStack() },
                onSettings = { navController.navigate(Screen.Settings.route) },
                config = ServerScreenConfig(
                    autoReconnect = false,
                    showManualFormInitially = true,
                    onConnectSavedServer = { saved ->
                        serverConnectionRegistry.connect(saved)
                        navController.popBackStack()
                    },
                    onNavigateBack = { navController.popBackStack() },
                ),
            )
        }

        // Main tab container - this is where the tab-based UI lives
        composable(Screen.Sessions.route) {
            MainTabScreen(
                pendingIntents = pendingIntents,
                onDisconnect = {
                    navController.navigate(Screen.ServerManagement.route)
                },
                onSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        // Settings accessible from Server screen (before connecting)
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = koinViewModel<SettingsViewModel> {
                    parametersOf(SettingsConnectionContext.Global)
                },
                onNavigateBack = { navController.popBackStack() },
                onDisconnect = {
                    navController.navigate(Screen.Server.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onVisualSettings = {
                    navController.navigate(Screen.VisualSettings.route)
                },
                onChatSettings = {
                    navController.navigate(Screen.ChatSettings.route)
                },
                onAgentsConfig = {},
                onSkills = {},
                onNotificationSettings = {
                    navController.navigate(Screen.NotificationSettings.route)
                },
                onConnectionSettings = {
                    navController.navigate(Screen.ConnectionSettings.route)
                },
                onLicenses = {
                    navController.navigate(Screen.Licenses.route)
                }
            )
        }

        composable(Screen.Licenses.route) {
            dev.blazelight.p4oc.ui.screens.licenses.LicensesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.VisualSettings.route) {
            VisualSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ChatSettings.route) {
            dev.blazelight.p4oc.ui.screens.settings.ChatSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.NotificationSettings.route) {
            dev.blazelight.p4oc.ui.screens.settings.NotificationSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ConnectionSettings.route) {
            dev.blazelight.p4oc.ui.screens.settings.ConnectionSettingsScreen(
                viewModel = koinViewModel<SettingsViewModel> {
                    parametersOf(SettingsConnectionContext.Global)
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
