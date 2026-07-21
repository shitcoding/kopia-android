package org.kopiaKt.e2e

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Utility class for running Go Kopia CLI commands.
 *
 * Used for E2E testing to verify cross-compatibility between
 * KopiaKt and Go Kopia implementations.
 */
class KopiaCliRunner(
    private val kopiaBinary: Path = defaultKopiaBinary(),
    private val configDir: Path? = null,
    private val environment: Map<String, String> = emptyMap(),
) {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 300L

        fun defaultKopiaBinary(): Path {
            // Check KOPIA_BINARY environment variable first
            val envPath = System.getenv("KOPIA_BINARY")
            if (envPath != null) {
                val file = File(envPath)
                if (file.exists() && file.canExecute()) {
                    return file.toPath()
                }
            }

            // Check for kopia in PATH
            val pathEnv = System.getenv("PATH") ?: ""
            for (dir in pathEnv.split(File.pathSeparator)) {
                val kopia = File(dir, "kopia")
                if (kopia.exists() && kopia.canExecute()) {
                    return kopia.toPath()
                }
            }

            throw IllegalStateException(
                "Kopia binary not found. Set KOPIA_BINARY environment variable or ensure kopia is in PATH.",
            )
        }
    }

    /**
     * Result of running a Kopia command.
     */
    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val success: Boolean get() = exitCode == 0

        fun requireSuccess(): CommandResult {
            if (!success) {
                throw KopiaCommandException(exitCode, stderr.ifEmpty { stdout })
            }
            return this
        }
    }

    /**
     * Exception thrown when a Kopia command fails.
     */
    class KopiaCommandException(
        val exitCode: Int,
        message: String,
    ) : RuntimeException("Kopia command failed with exit code $exitCode: $message")

    /**
     * Runs a Kopia command and returns the result.
     */
    suspend fun run(
        vararg args: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        workingDir: Path? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        val command = mutableListOf(kopiaBinary.toString())
        command.addAll(args)

        // Add config directory if specified
        if (configDir != null) {
            command.add(1, "--config-file=${configDir.resolve("repository.config")}")
        }

        val processBuilder = ProcessBuilder(command)
            .directory(workingDir?.toFile())
            .redirectErrorStream(false)

        // Set environment
        val env = processBuilder.environment()
        environment.forEach { (key, value) -> env[key] = value }

        // Disable interactive prompts
        env["KOPIA_ADVANCED_COMMANDS"] = "enabled"

        val process = processBuilder.start()

        // Read stdout and stderr concurrently to avoid I/O deadlock.
        // If the process fills one pipe buffer while we block reading the other,
        // both sides stall indefinitely. Background threads drain both streams
        // while waitFor handles the timeout.
        var stdoutResult = ""
        var stderrResult = ""

        val stdoutThread = thread(start = true, isDaemon = true) {
            stdoutResult = process.inputStream.bufferedReader().readText()
        }
        val stderrThread = thread(start = true, isDaemon = true) {
            stderrResult = process.errorStream.bufferedReader().readText()
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            // Wait briefly for reader threads to finish after force-kill
            stdoutThread.join(5_000)
            stderrThread.join(5_000)
            throw KopiaCommandException(-1, "Command timed out after ${timeoutSeconds}s")
        }

        // Process exited normally; streams will close and threads will finish
        stdoutThread.join()
        stderrThread.join()

        CommandResult(
            exitCode = process.exitValue(),
            stdout = stdoutResult,
            stderr = stderrResult,
        )
    }

    /**
     * Gets Kopia version information.
     */
    suspend fun version(): String = run("--version").requireSuccess().stdout.trim()

    /**
     * Creates a new repository.
     */
    suspend fun repositoryCreate(
        repoPath: Path,
        password: String,
        blockHashAlgorithm: String = "BLAKE2B-256-128",
        encryptionAlgorithm: String = "AES256-GCM-HMAC-SHA256",
    ): CommandResult = run(
        "repository",
        "create",
        "filesystem",
        "--path=$repoPath",
        "--password=$password",
        "--block-hash=$blockHashAlgorithm",
        "--encryption=$encryptionAlgorithm",
    ).requireSuccess()

    /**
     * Connects to an existing repository.
     */
    suspend fun repositoryConnect(
        repoPath: Path,
        password: String,
    ): CommandResult = run(
        "repository",
        "connect",
        "filesystem",
        "--path=$repoPath",
        "--password=$password",
    ).requireSuccess()

    /**
     * Disconnects from the repository.
     */
    suspend fun repositoryDisconnect(): CommandResult = run("repository", "disconnect")

    /**
     * Gets repository status.
     */
    suspend fun repositoryStatus(): CommandResult = run("repository", "status", "--json")

    /**
     * Creates a snapshot of a source directory.
     */
    suspend fun snapshotCreate(
        sourcePath: Path,
        tags: Map<String, String> = emptyMap(),
    ): SnapshotInfo {
        val args = mutableListOf("snapshot", "create", sourcePath.toString(), "--json")
        tags.forEach { (key, value) ->
            args.add("--tags=$key:$value")
        }

        val result = run(*args.toTypedArray()).requireSuccess()

        // Parse the JSON output to get snapshot ID
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString<SnapshotInfo>(result.stdout)
    }

    /**
     * Lists snapshots.
     */
    suspend fun snapshotList(
        sourcePath: Path? = null,
        all: Boolean = false,
    ): List<SnapshotListEntry> {
        val args = mutableListOf("snapshot", "list", "--json")
        if (sourcePath != null) {
            args.add(sourcePath.toString())
        }
        if (all) {
            args.add("--all")
        }

        val result = run(*args.toTypedArray()).requireSuccess()

        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString<List<SnapshotListEntry>>(result.stdout)
    }

    /**
     * Restores a snapshot.
     */
    suspend fun snapshotRestore(
        snapshotId: String,
        targetPath: Path,
    ): CommandResult = run(
        "snapshot",
        "restore",
        snapshotId,
        targetPath.toString(),
    ).requireSuccess()

    /**
     * Deletes a snapshot.
     */
    suspend fun snapshotDelete(
        snapshotId: String,
        confirm: Boolean = true,
    ): CommandResult {
        val args = mutableListOf("snapshot", "delete", snapshotId)
        if (confirm) {
            args.add("--delete")
        }
        return run(*args.toTypedArray()).requireSuccess()
    }

    /**
     * Lists all content in the repository.
     */
    suspend fun contentList(): CommandResult = run("content", "list")

    /**
     * Verifies repository integrity.
     */
    suspend fun maintenanceRun(full: Boolean = false): CommandResult {
        val args = mutableListOf("maintenance", "run")
        if (full) {
            args.add("--full")
        }
        return run(*args.toTypedArray())
    }

    /**
     * Gets blob stats.
     */
    suspend fun blobStats(): CommandResult = run("blob", "stats")
}

/**
 * Snapshot information returned from snapshot create.
 */
@Serializable
data class SnapshotInfo(
    val id: String? = null,
    val source: SourceInfo? = null,
    val rootEntry: RootEntryInfo? = null,
)

@Serializable
data class SourceInfo(
    val host: String? = null,
    val userName: String? = null,
    val path: String? = null,
)

@Serializable
data class RootEntryInfo(
    val obj: String? = null,
    val summ: SummaryInfo? = null,
)

@Serializable
data class SummaryInfo(
    val size: Long = 0,
    val files: Long = 0,
    val dirs: Long = 0,
)

/**
 * Snapshot list entry.
 */
@Serializable
data class SnapshotListEntry(
    val id: String? = null,
    val source: SourceInfo? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val rootEntry: RootEntryInfo? = null,
)
