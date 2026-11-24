package dev.blazelight.p4oc.ui.navigation

import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Server : Screen("server")
    data object ServerManagement : Screen("server/manage")
    data object Home : Screen("home")
    data object Sessions : Screen("sessions")

    data object Chat : Screen("chat/{sessionId}?focusInput={focusInput}") {
        fun createRoute(sessionId: String, focusInput: Boolean = false): String {
            val route = "chat/${sessionId.routeEncode()}"
            return if (focusInput) "$route?focusInput=true" else route
        }
        const val ARG_SESSION_ID = "sessionId"
        const val ARG_FOCUS_INPUT = "focusInput"
    }

    data object Terminal : Screen("terminal/{ptyId}") {
        fun createRoute(ptyId: String) = "terminal/${ptyId.routeEncode()}"
        const val ARG_PTY_ID = "ptyId"
    }

    data object Files : Screen("files")

    data object FileViewer : Screen("files/view?path={path}") {
        fun createRoute(path: String) = "files/view?path=${Uri.encode(path)}"
        const val ARG_PATH = "path"
    }

    data object DiffViewer : Screen("diff?content={content}&fileName={fileName}") {
        fun createRoute(diffContent: String, fileName: String? = null) =
            "diff?content=${Uri.encode(diffContent)}&fileName=${Uri.encode(fileName ?: "")}"
        const val ARG_CONTENT = "content"
        const val ARG_FILE_NAME = "fileName"
    }

    data object SessionDiff : Screen("session_diff/{sessionId}") {
        const val ARG_SESSION_ID = "sessionId"
        fun createRoute(sessionId: String) = "session_diff/${sessionId.routeEncode()}"
    }

    data object ProviderConfig : Screen("settings/providers")

    data object Git : Screen("git?projectId={projectId}") {
        fun createRoute(projectId: String? = null) =
            if (projectId != null) "git?projectId=${projectId.routeEncode()}" else "git"
        const val ARG_PROJECT_ID = "projectId"
    }

    data object SessionsFiltered : Screen("sessions?projectId={projectId}") {
        fun createRoute(projectId: String) = "sessions?projectId=${Uri.encode(projectId)}"
        const val ARG_PROJECT_ID = "projectId"
    }

    data object Settings : Screen("settings")

    data object VisualSettings : Screen("settings/visual")

    data object ChatSettings : Screen("settings/chat")

    data object ModelControls : Screen("settings/models")

    data object AgentsConfig : Screen("settings/agents")

    data object Skills : Screen("settings/skills")

    data object NotificationSettings : Screen("settings/notifications")
    data object VoiceSettings : Screen("settings/voice")

    data object ConnectionSettings : Screen("settings/connection")

    data object Licenses : Screen("settings/licenses")

    data object Projects : Screen("projects")
}

private fun String.routeEncode(): String = URLEncoder
    .encode(this, StandardCharsets.UTF_8.name())
    .replace("+", "%20")
