package dev.blazelight.p4oc

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import dev.blazelight.p4oc.core.datastore.SettingsDataStore
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.core.log.CrashRecorder
import dev.blazelight.p4oc.core.notification.NotificationRoute
import dev.blazelight.p4oc.core.notification.NotificationRouteCodec
import dev.blazelight.p4oc.ui.navigation.NavGraph
import dev.blazelight.p4oc.ui.theme.InterfaceScale
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.PocketCodeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val settingsDataStore: SettingsDataStore by inject()
    private val pendingNotificationRoute = MutableStateFlow<NotificationRoute?>(null)

    @Volatile
    private var startDestinationResolved = false

    /** Interface scale baked into this activity instance's resources by [attachBaseContext]. */
    private var appliedInterfaceScale = InterfaceScale.DEFAULT_PERCENT

    /**
     * Interface scale is a densityDpi override on the activity's resources, so every dp and sp
     * scales together: Compose content, Dialog/Popup/ModalBottomSheet windows (each builds its
     * own ComposeView whose LocalDensity is derived from this activity's context), and embedded
     * Android views (terminal, code editor). The override is a delta, so the rest of the
     * configuration (dark mode, font scale, locale) keeps following the system.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appliedInterfaceScale = readInterfaceScale()
        if (appliedInterfaceScale != InterfaceScale.DEFAULT_PERCENT) {
            applyOverrideConfiguration(
                Configuration().apply {
                    densityDpi = InterfaceScale.scaledDensityDpi(
                        baseDensityDpi = newBase.resources.configuration.densityDpi,
                        percent = appliedInterfaceScale,
                    )
                }
            )
        }
    }

    // Resources must be configured before the window exists, so this one read blocks; it is a
    // single small preferences file that later launch-time reads then get from DataStore's cache.
    private fun readInterfaceScale(): Int = try {
        runBlocking { settingsDataStore.interfaceScale.first() }
    } catch (e: IOException) {
        AppLog.w(TAG, "Could not read interface scale; using default", e)
        InterfaceScale.DEFAULT_PERCENT
    }

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
        recreateOnInterfaceScaleChange()
    }

    /**
     * A changed scale needs fresh resources, so the activity is recreated. Navigation back
     * stacks, per-tab saveable state and ViewModels survive recreation (as for any system
     * configuration change), so the user stays where they were. The short settle delay
     * coalesces rapid stepper taps into one recreation.
     */
    private fun recreateOnInterfaceScaleChange() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsDataStore.interfaceScale.collectLatest { percent ->
                    if (percent != appliedInterfaceScale) {
                        delay(INTERFACE_SCALE_SETTLE_MS)
                        recreate()
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

    private companion object {
        const val TAG = "MainActivity"
        const val INTERFACE_SCALE_SETTLE_MS = 400L
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
