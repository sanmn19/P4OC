package dev.blazelight.p4oc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.log.CrashRecorder
import dev.blazelight.p4oc.core.notification.NotificationRoute
import dev.blazelight.p4oc.core.notification.NotificationRouteCodec
import dev.blazelight.p4oc.ui.navigation.NavGraph
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.PocketCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val settingsDataStore: SettingsDataStore by inject()
    private val pendingNotificationRoute = MutableStateFlow<NotificationRoute?>(null)

    @Volatile
    private var startDestinationResolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !startDestinationResolved }
        super.onCreate(savedInstanceState)
        pendingNotificationRoute.value = NotificationRouteCodec.read(intent)

        // Debug builds record uncaught crashes and surface the trace once on the next launch
        // so a failing device can relay the stack trace without a logcat connection.
        val storedCrash = CrashRecorder.pendingCrash(this)
        CrashRecorder.consume(this)

        setContent {
            val themeMode by settingsDataStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val themeName by settingsDataStore.themeName.collectAsStateWithLifecycle(
                initialValue = SettingsDataStore.DEFAULT_THEME_NAME
            )
            val oledBlack by settingsDataStore.oledBlack.collectAsStateWithLifecycle(initialValue = false)
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            PocketCodeTheme(themeName = themeName, darkTheme = darkTheme, oledBlack = oledBlack) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LocalOpenCodeTheme.current.background
                ) {
                    if (storedCrash != null) {
                        CrashReportDialog(trace = storedCrash, onDismiss = { finish() })
                    }
                    // First launch (onboarding not completed) shows the first-run Setup
                    // screen; returning users with any saved server land on the Server connect
                    // screen even offline. Resolved from persisted state before the NavGraph
                    // composes, so the start destination is stable for the navigation back stack.
                    val startDestination by produceState<String?>(initialValue = null) {
                        try {
                            value = loadLaunchDestination(settingsDataStore)
                        } finally {
                            startDestinationResolved = true
                        }
                    }
                    startDestination?.let { destination ->
                        // Use NavGraph for initial Server/Setup screens,
                        // then MainTabScreen takes over after connection
                        val navController = rememberNavController()
                        NavGraph(
                            navController = navController,
                            startDestination = destination,
                            pendingNotificationRoute = pendingNotificationRoute,
                            onNotificationRouteConsumed = { route ->
                                if (pendingNotificationRoute.compareAndSet(route, null)) {
                                    NotificationRouteCodec.clear(intent)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationRouteCodec.read(intent)?.let { pendingNotificationRoute.value = it }
    }
}

/** Scrollable, copyable view of a stored debug-build crash trace. */
@Composable
@Suppress("FunctionNaming")
private fun CrashReportDialog(trace: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Last crash") },
        text = {
            Box(Modifier.heightIn(max = 420.dp)) {
                Text(
                    text = trace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(trace))
                    onDismiss()
                },
            ) { Text("Copy trace and close") }
        },
    )
}
