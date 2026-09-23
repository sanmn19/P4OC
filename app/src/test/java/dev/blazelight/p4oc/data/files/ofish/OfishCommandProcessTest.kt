package dev.blazelight.p4oc.data.files.ofish

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

class OfishCommandProcessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val capabilities = OfishCapabilities(
        hasBase64 = true,
        base64DecodeFlag = "-d",
        hashCommand = HashCommand.SHA256SUM,
        hasMv = true,
        hasMkdir = true,
        hasRm = true,
        hasAwk = true,
        hasMktemp = true,
        hasChmod = true,
        modeCommand = ModeCommand.STAT_GNU,
    )
    private val builder = OfishCommandBuilder()

    @Test
    fun `write creates parent directory and file`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()

        val output = runShell(builder.write("a/b/file.txt", "hello", null, capabilities), root)

        assertTrue(OfishMutationParser.parse(output, "#OFISH_WRITE") is OfishMutationStatus.Ok)
        assertEquals("hello", File(root, "a/b/file.txt").readText())
    }

    @Test
    fun `write conflict preserves existing file`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "file.txt")
        target.writeText("old")

        val output = runShell(builder.write("file.txt", "new", "wrong", capabilities), root)

        assertTrue(OfishMutationParser.parse(output, "#OFISH_WRITE") is OfishMutationStatus.Conflict)
        assertEquals("old", target.readText())
    }

    @Test
    fun `write matching expected hash replaces file`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "file.txt")
        target.writeText("old")

        val output = runShell(builder.write("file.txt", "new", sha256("old".toByteArray()), capabilities), root)

        assertTrue(OfishMutationParser.parse(output, "#OFISH_WRITE") is OfishMutationStatus.Ok)
        assertEquals("new", target.readText())
    }

    @Test
    fun `write preserves executable destination mode`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "script.sh").apply {
            writeText("old")
        }
        Files.setPosixFilePermissions(target.toPath(), MODE_0755)

        val output = builder.write("script.sh", "new", null, capabilities).runIn(root)

        assertTrue(OfishMutationParser.parse(output, "#OFISH_WRITE") is OfishMutationStatus.Ok)
        assertEquals("new", target.readText())
        assertEquals(MODE_0755, Files.getPosixFilePermissions(target.toPath()))
    }

    @Test
    fun `write new file retains safe non executable default`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "new.txt")

        val output = builder.write("new.txt", "new", null, capabilities).runIn(root)

        assertTrue(OfishMutationParser.parse(output, "#OFISH_WRITE") is OfishMutationStatus.Ok)
        assertEquals(MODE_0600, Files.getPosixFilePermissions(target.toPath()))
    }

    @Test
    fun `write rejects directory without moving temp into it`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "target").apply { mkdir() }
        val existing = File(target, "existing.txt").apply { writeText("preserved") }

        val output = runShell(builder.write("target", "new", null, capabilities), root)

        assertEquals(
            OfishMutationStatus.PreconditionFailed("directory"),
            OfishMutationParser.parse(output, "#OFISH_WRITE")
        )
        assertTrue(target.isDirectory)
        assertEquals("preserved", existing.readText())
        assertEquals(listOf("existing.txt"), target.list()?.toList())
    }

    @Test
    fun `write with expected hash rejects directory as directory`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "target").apply { mkdir() }

        val output = runShell(builder.write("target", "new", sha256(byteArrayOf()), capabilities), root)

        assertEquals(
            OfishMutationStatus.PreconditionFailed("directory"),
            OfishMutationParser.parse(output, "#OFISH_WRITE")
        )
        assertTrue(target.isDirectory)
        assertTrue(target.list()?.isEmpty() == true)
    }

    @Test
    fun `write rejects destination symlink without changing its target`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val external = File(root, "external.txt").apply { writeText("preserved") }
        val link = File(root, "file.txt")
        Files.createSymbolicLink(link.toPath(), external.toPath())

        val output = builder.write("file.txt", "new", null, capabilities).runIn(root)

        assertEquals(
            OfishMutationStatus.PreconditionFailed("symlink"),
            OfishMutationParser.parse(output, "#OFISH_WRITE"),
        )
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("preserved", external.readText())
    }

    @Test
    fun `write rechecks destination symlink immediately before replacement`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "file.txt").apply { writeText("old") }
        val external = File(root, "external.txt").apply { writeText("preserved") }
        val tools = File(root, "tools").apply { mkdir() }
        File(tools, "chmod").apply {
            writeText(
                "#!/bin/sh\n$realChmod \"\$@\" || exit \$?\n" +
                    "rm -f file.txt && ln -s external.txt file.txt\n",
            )
            setExecutable(true)
        }

        val output = runShell(
            builder.write("file.txt", "new", null, capabilities),
            root,
            environment = mapOf("PATH" to "${tools.absolutePath}:${System.getenv("PATH")}"),
        )

        assertEquals(
            OfishMutationStatus.PreconditionFailed("symlink"),
            OfishMutationParser.parse(output, "#OFISH_WRITE"),
        )
        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertEquals("preserved", external.readText())
        assertTrue(root.listFiles()?.none { it.name.startsWith(".ofish.") } == true)
    }

    @Test
    fun `delete removes file and rejects directory`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "file.txt")
        target.writeText("content")

        val deleteOutput = runShell(builder.delete("file.txt"), root)

        assertEquals(OfishMutationStatus.Deleted, OfishMutationParser.parse(deleteOutput, "#OFISH_DELETE"))
        assertFalse(target.exists())

        File(root, "dir").mkdir()
        val directoryOutput = runShell(builder.delete("dir"), root)

        assertEquals(
            OfishMutationStatus.PreconditionFailed("directory"),
            OfishMutationParser.parse(directoryOutput, "#OFISH_DELETE")
        )
        assertTrue(File(root, "dir").isDirectory)
    }

    @Test
    fun `delete and mkdir reject symlinks`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val external = File(root, "external.txt").apply { writeText("preserved") }
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), external.toPath())

        val deleteOutput = builder.delete("link").runIn(root)
        val mkdirOutput = builder.mkdir("link").runIn(root)

        assertEquals(
            OfishMutationStatus.PreconditionFailed("symlink"),
            OfishMutationParser.parse(deleteOutput, "#OFISH_DELETE"),
        )
        assertEquals(
            OfishMutationStatus.PreconditionFailed("symlink"),
            OfishMutationParser.parse(mkdirOutput, "#OFISH_MKDIR"),
        )
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("preserved", external.readText())
    }

    @Test
    fun `rename rejects symlink source and destination`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val external = File(root, "external.txt").apply { writeText("preserved") }
        Files.createSymbolicLink(File(root, "source-link").toPath(), external.toPath())

        val sourceOutput = builder.rename("source-link", "renamed").runIn(root)
        File(root, "source.txt").writeText("source")
        Files.createSymbolicLink(File(root, "destination-link").toPath(), external.toPath())
        val destinationOutput = builder.rename("source.txt", "destination-link").runIn(root)

        assertEquals(
            OfishMutationStatus.PreconditionFailed("symlink"),
            OfishMutationParser.parse(sourceOutput, "#OFISH_RENAME"),
        )
        assertEquals(
            OfishMutationStatus.PreconditionFailed("symlink"),
            OfishMutationParser.parse(destinationOutput, "#OFISH_RENAME"),
        )
        assertEquals("source", File(root, "source.txt").readText())
        assertEquals("preserved", external.readText())
    }

    @Test
    fun `capability probe command runs under zsh invoking sh wrapper`() {
        assumeShellAvailable()
        assumeZshAvailable()
        val root = temporaryFolder.newFolder()

        val output = runShell(OfishCapabilityProbeCommand.build(), root, shell = "/bin/zsh")

        assertTrue(output, OfishCapabilityParser.parse(output) is OfishProbeResult.Available)
    }

    @Test
    fun `upload init chunks and finish reconstruct bytes`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

        val init = OfishMutationParser.parse(
            builder.uploadInit("out/file.bin", null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_INIT"
        )
        assertTrue(init is OfishMutationStatus.Ok)
        val token = (init as OfishMutationStatus.Ok).uploadToken ?: error("missing token")

        bytes.toList().chunked(4).forEach { chunk ->
            val status = OfishMutationParser.parse(
                builder.uploadChunk(token, chunk.toByteArray(), capabilities).runIn(root),
                "#OFISH_UPLOAD_CHUNK"
            )
            assertTrue(status is OfishMutationStatus.Ok)
        }

        val finish = OfishMutationParser.parse(
            builder.uploadFinish("out/file.bin", token, null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_FINISH"
        )

        assertTrue(finish is OfishMutationStatus.Ok)
        assertArrayEquals(bytes, File(root, "out/file.bin").readBytes())
    }

    @Test
    fun `create-only upload never replaces a file created during transfer`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val init = OfishMutationParser.parse(
            builder.uploadInit("file.txt", null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_INIT"
        ) as OfishMutationStatus.Ok
        val token = requireNotNull(init.uploadToken)
        builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root)
        val destination = File(root, "file.txt").apply { writeText("keep me") }

        val finish = OfishMutationParser.parse(
            builder.uploadFinish("file.txt", token, null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_FINISH"
        )
        assertTrue(finish is OfishMutationStatus.Conflict)
        assertEquals("keep me", destination.readText())
        val nextInit = OfishMutationParser.parse(
            builder.uploadInit("file.txt", null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_INIT"
        )
        assertTrue(nextInit is OfishMutationStatus.Conflict)
        builder.uploadAbort(token).runIn(root)
        assertFalse(File(root, token).exists())
    }

    @Test
    fun `create-only finish rejects directory without nesting the temp file`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val init = OfishMutationParser.parse(
            builder.uploadInit("target", null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_INIT"
        ) as OfishMutationStatus.Ok
        val token = requireNotNull(init.uploadToken)
        builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root)
        val target = File(root, "target").apply { mkdir() }

        val finish = OfishMutationParser.parse(
            builder.uploadFinish("target", token, null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_FINISH"
        )

        assertTrue(finish is OfishMutationStatus.Conflict)
        assertTrue(target.isDirectory)
        assertTrue(target.list()?.isEmpty() == true)
        assertTrue(File(root, token).exists())
        builder.uploadAbort(token).runIn(root)
    }

    @Test
    fun `create-only finish cleans up nested link when a directory appears mid-finish`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val init = OfishMutationParser.parse(
            builder.uploadInit("target", null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_INIT"
        ) as OfishMutationStatus.Ok
        val token = requireNotNull(init.uploadToken)
        builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root)
        val tools = File(root, "tools").apply { mkdir() }
        File(tools, "ln").apply {
            writeText(
                "#!/bin/sh\n" +
                    "mkdir target\n" +
                    "printf 'keep' > target/keep.txt\n" +
                    "exec $realLn \"\$@\"\n"
            )
            setExecutable(true)
        }

        val finish = OfishMutationParser.parse(
            runShell(
                builder.uploadFinish("target", token, null, capabilities, createOnly = true),
                root,
                environment = mapOf("PATH" to "${tools.absolutePath}:${System.getenv("PATH")}"),
            ),
            "#OFISH_UPLOAD_FINISH"
        )

        assertTrue(finish is OfishMutationStatus.Conflict)
        val target = File(root, "target")
        assertTrue(target.isDirectory)
        assertEquals(listOf("keep.txt"), target.list()?.toList())
        assertEquals("keep", File(target, "keep.txt").readText())
        assertTrue(File(root, token).exists())
        builder.uploadAbort(token).runIn(root)
    }

    @Test
    fun `create-only finish preserves a foreign file that replaced the nested link`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val init = OfishMutationParser.parse(
            builder.uploadInit("target", null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_INIT"
        ) as OfishMutationStatus.Ok
        val token = requireNotNull(init.uploadToken)
        builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root)
        val nestedName = File(token).name
        val tools = File(root, "tools").apply { mkdir() }
        File(tools, "ln").apply {
            writeText(
                "#!/bin/sh\n" +
                    "mkdir target\n" +
                    "$realLn \"\$@\"\n" +
                    "rm -f 'target/$nestedName'\n" +
                    "printf 'foreign' > 'target/$nestedName'\n"
            )
            setExecutable(true)
        }

        val finish = OfishMutationParser.parse(
            runShell(
                builder.uploadFinish("target", token, null, capabilities, createOnly = true),
                root,
                environment = mapOf("PATH" to "${tools.absolutePath}:${System.getenv("PATH")}"),
            ),
            "#OFISH_UPLOAD_FINISH"
        )

        assertTrue(finish is OfishMutationStatus.Conflict)
        assertEquals("foreign", File(root, "target/$nestedName").readText())
        assertTrue(File(root, token).exists())
        builder.uploadAbort(token).runIn(root)
    }

    @Test
    fun `create-only finish works with a strict POSIX ln`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val tools = File(root, "tools").apply { mkdir() }
        File(tools, "ln").apply {
            writeText(
                "#!/bin/sh\n" +
                    "for arg in \"\$@\"; do\n" +
                    "  case \"\$arg\" in -*) exit 2;; esac\n" +
                    "done\n" +
                    "exec $realLn \"\$@\"\n"
            )
            setExecutable(true)
        }
        val environment = mapOf("PATH" to "${tools.absolutePath}:${System.getenv("PATH")}")
        val init = OfishMutationParser.parse(
            runShell(
                builder.uploadInit("file.txt", null, capabilities, createOnly = true),
                root,
                environment = environment,
            ),
            "#OFISH_UPLOAD_INIT"
        ) as OfishMutationStatus.Ok
        val token = requireNotNull(init.uploadToken)
        builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root)

        val finish = OfishMutationParser.parse(
            runShell(
                builder.uploadFinish("file.txt", token, null, capabilities, createOnly = true),
                root,
                environment = environment,
            ),
            "#OFISH_UPLOAD_FINISH"
        )

        assertTrue(finish is OfishMutationStatus.Ok)
        assertEquals("new", File(root, "file.txt").readText())
        assertFalse(File(root, token).exists())
    }

    @Test
    fun `create-only upload writes a dash-leading name inside a dash-leading parent`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val init = OfishMutationParser.parse(
            builder.uploadInit("-dash/-name.txt", null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_INIT"
        ) as OfishMutationStatus.Ok
        val token = requireNotNull(init.uploadToken)
        builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root)

        val finish = OfishMutationParser.parse(
            builder.uploadFinish("-dash/-name.txt", token, null, capabilities, createOnly = true).runIn(root),
            "#OFISH_UPLOAD_FINISH",
        )

        assertTrue(finish.toString(), finish is OfishMutationStatus.Ok)
        assertEquals("new", File(root, "-dash/-name.txt").readText())
        assertFalse(File(root, token).exists())
    }

    @Test
    fun `write handles dash-leading names and preserves executable mode`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "-dash/-script.sh").apply {
            parentFile?.mkdirs()
            writeText("old")
        }
        Files.setPosixFilePermissions(target.toPath(), MODE_0755)

        val output = builder.write("-dash/-script.sh", "new", null, capabilities).runIn(root)

        assertTrue(output, OfishMutationParser.parse(output, "#OFISH_WRITE") is OfishMutationStatus.Ok)
        assertEquals("new", target.readText())
        assertEquals(MODE_0755, Files.getPosixFilePermissions(target.toPath()))
    }

    @Test
    fun `chunked overwrite rejects a directory destination without nesting the spool file`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val init = OfishMutationParser.parse(
            builder.uploadInit("target", null, capabilities).runIn(root),
            "#OFISH_UPLOAD_INIT",
        ) as OfishMutationStatus.Ok
        val token = requireNotNull(init.uploadToken)
        builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root)
        val target = File(root, "target").apply { mkdir() }
        File(target, "keep.txt").writeText("keep")

        val finish = OfishMutationParser.parse(
            builder.uploadFinish("target", token, null, capabilities).runIn(root),
            "#OFISH_UPLOAD_FINISH",
        )

        assertEquals(
            OfishMutationStatus.PreconditionFailed("directory"),
            finish,
        )
        assertEquals(listOf("keep.txt"), target.list()?.toList())
        assertTrue(File(root, token).exists())
        builder.uploadAbort(token).runIn(root)
    }

    @Test
    fun `chunk upload preserves executable destination mode`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val target = File(root, "script.sh").apply {
            writeText("old")
        }
        Files.setPosixFilePermissions(target.toPath(), MODE_0755)
        val init = OfishMutationParser.parse(
            builder.uploadInit("script.sh", null, capabilities).runIn(root),
            "#OFISH_UPLOAD_INIT",
        ) as OfishMutationStatus.Ok
        val token = init.uploadToken ?: error("missing token")

        assertTrue(
            OfishMutationParser.parse(
                builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root),
                "#OFISH_UPLOAD_CHUNK",
            ) is OfishMutationStatus.Ok,
        )
        val finish = builder.uploadFinish("script.sh", token, null, capabilities).runIn(root)

        assertTrue(finish, OfishMutationParser.parse(finish, "#OFISH_UPLOAD_FINISH") is OfishMutationStatus.Ok)
        assertEquals("new", target.readText())
        assertEquals(MODE_0755, Files.getPosixFilePermissions(target.toPath()))
    }

    @Test
    fun `upload init and finish reject destination symlink`() {
        assumeShellAvailable()
        val root = temporaryFolder.newFolder()
        val external = File(root, "external.txt").apply { writeText("preserved") }
        val link = File(root, "file.txt")
        Files.createSymbolicLink(link.toPath(), external.toPath())

        val initOutput = builder.uploadInit("file.txt", null, capabilities).runIn(root)

        assertEquals(
            OfishMutationStatus.PreconditionFailed("symlink"),
            OfishMutationParser.parse(initOutput, "#OFISH_UPLOAD_INIT"),
        )

        Files.delete(link.toPath())
        val init = OfishMutationParser.parse(
            builder.uploadInit("file.txt", null, capabilities).runIn(root),
            "#OFISH_UPLOAD_INIT",
        ) as OfishMutationStatus.Ok
        val token = init.uploadToken ?: error("missing token")
        builder.uploadChunk(token, "new".toByteArray(), capabilities).runIn(root)
        Files.createSymbolicLink(link.toPath(), external.toPath())

        val finishOutput = builder.uploadFinish("file.txt", token, null, capabilities).runIn(root)

        assertEquals(
            OfishMutationStatus.PreconditionFailed("symlink"),
            OfishMutationParser.parse(finishOutput, "#OFISH_UPLOAD_FINISH"),
        )
        assertTrue(File(root, token).exists())
        assertEquals("preserved", external.readText())
    }

    private fun String.runIn(root: File): String = runShell(this, root)

    private fun runShell(
        command: String,
        cwd: File,
        shell: String = "/bin/sh",
        environment: Map<String, String> = emptyMap(),
    ): String {
        val processBuilder = ProcessBuilder(shell, "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
        processBuilder.environment().putAll(environment)
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return output
    }

    private fun assumeShellAvailable() {
        assumeTrue(File("/bin/sh").exists())
        assumeTrue(ProcessBuilder("/bin/sh", "-c", "command -v sha256sum >/dev/null 2>&1").start().waitFor() == 0)
    }

    /**
     * The wrapper script re-executes the real host `chmod` before planting the destination
     * symlink. Resolve that executable by scanning the inherited PATH for an executable `chmod`,
     * so the test neither assumes `/usr/bin/chmod` nor resolves the wrapper itself (the scan
     * happens before the wrapper directory is prepended to PATH).
     */
    private val realChmod: String by lazy {
        val resolved = System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { dir -> File(dir, "chmod") }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.let { file -> file.toPath().toAbsolutePath().normalize().toString() }
            ?: error("Could not resolve 'chmod' on the host PATH")
        require("'" !in resolved) { "Resolved chmod path must not contain shell metacharacters: $resolved" }
        "'$resolved'"
    }

    /**
     * Same PATH scan as [realChmod], for the `ln` wrapper scripts that plant a directory or
     * reject option arguments before delegating to the real host `ln`.
     */
    private val realLn: String by lazy {
        val resolved = System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { dir -> File(dir, "ln") }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.let { file -> file.toPath().toAbsolutePath().normalize().toString() }
            ?: error("Could not resolve 'ln' on the host PATH")
        require("'" !in resolved) { "Resolved ln path must not contain shell metacharacters: $resolved" }
        "'$resolved'"
    }

    private fun assumeZshAvailable() {
        assumeTrue(File("/bin/zsh").exists())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        val MODE_0755 = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        val MODE_0600 = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
