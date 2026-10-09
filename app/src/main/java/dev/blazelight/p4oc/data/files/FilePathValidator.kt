package dev.blazelight.p4oc.data.files

internal object FilePathValidator {
    fun normalizeForReadOrList(path: String): Result<String> = normalize(path, allowRoot = true)

    fun normalizeForMutation(path: String): Result<String> = normalize(path, allowRoot = false)

    /**
     * Workspace-folder creation variant: validates an explicitly absolute server path and keeps
     * it verbatim for the server-side shell. The relative-mutations validator stays strict
     * (no absolute escape); this one inverts that rule with its own traversal/scheme guards,
     * mirroring the picker's draft validation (also enforced client-side at the typed input).
     */
    fun normalizeForAbsoluteMutation(path: String): Result<String> {
        if (path.indexOf('\u0000') >= 0) {
            return invalid("NUL characters are not allowed in file paths")
        }
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            return invalid("Root file path is not allowed for mutations")
        }
        if (trimmed != path) {
            return invalid("Leading or trailing whitespace is not allowed in file paths")
        }
        if (trimmed.startsWith("~")) {
            return invalid("Home-relative file paths are not allowed")
        }
        if (trimmed.contains('\\')) {
            return invalid("Backslash path separators are not allowed")
        }
        if (WINDOWS_DRIVE_PATTERN.matches(trimmed)) {
            return invalid("Windows drive file paths are not allowed")
        }
        if (URI_SCHEME_PATTERN.matches(trimmed)) {
            return invalid("URI file paths are not allowed")
        }
        if (!trimmed.startsWith("/")) {
            return invalid("Workspace creation requires an absolute path")
        }
        val segments = trimmed.split('/').drop(1)
        if (segments.none()) {
            return invalid("Root file path is not allowed for mutations")
        }
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return invalid("Every path component must be an explicit child name")
        }
        if (trimmed.length > MAX_ABSOLUTE_PATH_CHARS || segments.any { it.length > MAX_PATH_COMPONENT_CHARS }) {
            return invalid("File path is too long for mutations")
        }
        return Result.success(trimmed)
    }

    private fun normalize(path: String, allowRoot: Boolean): Result<String> {
        if (path.indexOf('\u0000') >= 0) {
            return invalid("NUL characters are not allowed in file paths")
        }

        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed.all { it == '/' }) {
            return if (allowRoot) Result.success("") else invalid("Root file path is not allowed for mutations")
        }

        if (!allowRoot && path != trimmed) {
            return invalid("Leading or trailing whitespace is not allowed in file paths")
        }

        if (trimmed.startsWith("~")) {
            return invalid("Home-relative file paths are not allowed")
        }

        if (trimmed.contains('\\')) {
            return invalid("Backslash path separators are not allowed")
        }

        if (WINDOWS_DRIVE_PATTERN.matches(trimmed)) {
            return invalid("Windows drive file paths are not allowed")
        }

        if (URI_SCHEME_PATTERN.matches(trimmed)) {
            return invalid("URI file paths are not allowed")
        }

        if (trimmed.startsWith("/")) {
            return invalid("Absolute file paths are not allowed")
        }

        val segments = mutableListOf<String>()
        trimmed.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> return invalid("Parent path segments are not allowed")
                else -> segments += segment
            }
        }

        val normalized = segments.joinToString("/")
        if (normalized.isEmpty() && !allowRoot) {
            return invalid("Root file path is not allowed for mutations")
        }

        return Result.success(normalized)
    }

    private fun invalid(message: String): Result<String> = Result.failure(InvalidFilePathException(message))

    private val WINDOWS_DRIVE_PATTERN = Regex("^[A-Za-z]:.*")
    private val URI_SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*:/+.*")
    private const val MAX_ABSOLUTE_PATH_CHARS = 1024
    private const val MAX_PATH_COMPONENT_CHARS = 255
}

internal class InvalidFilePathException(message: String) : IllegalArgumentException(message)
