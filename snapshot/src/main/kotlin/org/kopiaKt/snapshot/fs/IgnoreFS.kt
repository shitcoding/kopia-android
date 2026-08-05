package org.kopiaKt.snapshot.fs

import org.kopiaKt.snapshot.policy.FilesPolicy
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Filesystem wrapper that applies ignore rules from policy and .kopiaignore files.
 *
 * Go type: ignorefs.ignoreDirectory
 */
class IgnoreFS internal constructor(
    private val wrapped: Directory,
    private val context: IgnoreContext,
    private val relativePath: String,
    /**
     * Told about every entry these rules filter out, or null to filter silently.
     *
     * Go's `ignorefs` takes the same callback and its uploader passes `reportIgnoreStats=false` for
     * the estimator's wrapper — because the estimator walks the same tree at the same time
     * (task-30.20), and one shared reporting wrapper would count every excluded entry twice.
     *
     * **Reports more than Go does, deliberately.** Go fires its callback only for name-pattern
     * matches; a file dropped for exceeding `maxFileSize`, or for sitting on another filesystem
     * under `oneFileSystem`, is skipped silently. Those are still files the user asked to back up
     * and did not get, and "Excluded Files: 0" next to a missing 5 GB video is the kind of quiet
     * that makes a backup tool untrustworthy. The count is per ENTRY, so an excluded directory is
     * one exclusion and its contents are never visited — that part matches Go.
     */
    private val onIgnored: ((Entry, String) -> Unit)? = null,
) : Directory {

    override val name: String = wrapped.name
    override val type: EntryType = wrapped.type
    override val size: Long = wrapped.size
    override val modTime: Instant = wrapped.modTime
    override val mode: Int = wrapped.mode
    override val owner: OwnerInfo = wrapped.owner
    override val device: DeviceInfo = wrapped.device
    override val localFilesystemPath: String = wrapped.localFilesystemPath

    override suspend fun child(name: String): Entry? {
        val childPath = joinPath(relativePath, name)
        val entry = wrapped.child(name) ?: return null

        // Check if should be ignored
        if (context.shouldIgnore(childPath, entry)) {
            onIgnored?.invoke(entry, childPath)
            return null
        }

        return wrapEntry(entry, childPath)
    }

    override suspend fun iterate(): DirectoryIterator = IgnoreDirectoryIterator(
        wrapped.iterate(),
        context,
        relativePath,
        onIgnored,
    )

    override fun supportsMultipleIterations(): Boolean = wrapped.supportsMultipleIterations()

    override fun close() = wrapped.close()

    private fun wrapEntry(entry: Entry, path: String): Entry = when (entry) {
        is Directory -> {
            // Create child context with patterns from .kopiaignore in this directory
            val childContext = context.childContext(entry, path)
            IgnoreFS(entry, childContext, path, onIgnored)
        }
        else -> entry
    }

    companion object {
        /**
         * Wraps a directory with ignore filtering.
         *
         * @param dir The directory to wrap
         * @param policy The files policy containing ignore rules
         * @return A filtered directory view
         */
        fun wrap(
            dir: Directory,
            policy: FilesPolicy,
            onIgnored: ((Entry, String) -> Unit)? = null,
        ): Directory {
            // Load patterns from root directory's dotIgnoreFiles
            val rootPatterns = loadIgnoreFiles(dir, policy.dotIgnoreFiles)
            val context = IgnoreContext.create(policy, rootPatterns, dir.device)
            return IgnoreFS(dir, context, "", onIgnored)
        }

        /**
         * Wraps a directory with custom matchers.
         *
         * @param dir The directory to wrap
         * @param matchers The ignore pattern matchers
         * @param dotIgnoreFiles Files to load additional patterns from
         * @param maxFileSize Maximum file size to include (0 = unlimited)
         * @param oneFileSystem Whether to stay on the same filesystem
         * @return A filtered directory view
         */
        fun wrap(
            dir: Directory,
            matchers: List<WildcardMatcher> = emptyList(),
            dotIgnoreFiles: List<String> = listOf(".kopiaignore"),
            maxFileSize: Long = 0,
            oneFileSystem: Boolean = false,
        ): Directory {
            // Load patterns from root directory's dotIgnoreFiles
            val rootPatterns = loadIgnoreFiles(dir, dotIgnoreFiles)
            val combinedMatchers = matchers + rootPatterns

            val context = IgnoreContext(
                matchers = combinedMatchers,
                dotIgnoreFiles = dotIgnoreFiles,
                maxFileSize = maxFileSize,
                oneFileSystem = oneFileSystem,
                rootDevice = if (oneFileSystem) dir.device else DeviceInfo.EMPTY,
            )
            return IgnoreFS(dir, context, "")
        }

        private fun loadIgnoreFiles(dir: Directory, dotIgnoreFiles: List<String>): List<WildcardMatcher> {
            val matchers = mutableListOf<WildcardMatcher>()
            val localPath = dir.localFilesystemPath
            if (localPath.isEmpty()) {
                return emptyList()
            }

            for (fileName in dotIgnoreFiles) {
                val ignoreFilePath = Path.of(localPath, fileName)
                if (ignoreFilePath.exists()) {
                    try {
                        val patterns = ignoreFilePath.readLines()
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                        matchers.addAll(WildcardMatcher.parseAll(patterns))
                    } catch (e: Exception) {
                        // Ignore errors reading ignore files
                    }
                }
            }
            return matchers
        }
    }
}

/**
 * Iterator that filters out ignored entries.
 */
private class IgnoreDirectoryIterator(
    private val wrapped: DirectoryIterator,
    private val context: IgnoreContext,
    private val parentPath: String,
    private val onIgnored: ((Entry, String) -> Unit)? = null,
) : DirectoryIterator {

    override suspend fun next(): Entry? {
        while (true) {
            val entry = wrapped.next() ?: return null
            val entryPath = joinPath(parentPath, entry.name)

            if (context.shouldIgnore(entryPath, entry)) {
                onIgnored?.invoke(entry, entryPath)
                continue
            }

            return when (entry) {
                is Directory -> {
                    val childContext = context.childContext(entry, entryPath)
                    IgnoreFS(entry, childContext, entryPath, onIgnored)
                }
                else -> entry
            }
        }
    }

    override fun close() = wrapped.close()
}

/**
 * Context for ignore pattern matching.
 * Maintains state as we descend into directories.
 */
class IgnoreContext(
    private val matchers: List<WildcardMatcher>,
    private val dotIgnoreFiles: List<String>,
    private val maxFileSize: Long,
    private val oneFileSystem: Boolean,
    private val rootDevice: DeviceInfo,
    private val noParentIgnoreRules: Boolean = false,
    private val noParentDotIgnoreFiles: Boolean = false,
) {

    /**
     * Checks if an entry should be ignored.
     */
    fun shouldIgnore(path: String, entry: Entry): Boolean {
        // Check filesystem boundary
        if (oneFileSystem && entry.device.dev != rootDevice.dev && rootDevice.dev != 0L) {
            return true
        }

        // Check file size limit
        if (maxFileSize > 0 && entry.isFile() && entry.size > maxFileSize) {
            return true
        }

        // Check cache directory marker (CACHEDIR.TAG)
        if (entry.isDirectory()) {
            // Could check for CACHEDIR.TAG file here
        }

        // Check pattern matchers
        return shouldIgnore(path, entry.isDirectory(), matchers)
    }

    /**
     * Creates a child context for a subdirectory.
     * Loads additional patterns from .kopiaignore files if present.
     */
    fun childContext(dir: Directory, dirPath: String): IgnoreContext {
        val additionalMatchers = mutableListOf<WildcardMatcher>()

        // Load patterns from dot ignore files
        if (!noParentDotIgnoreFiles) {
            for (ignoreFileName in dotIgnoreFiles) {
                val patterns = loadIgnoreFile(dir, ignoreFileName)
                if (patterns.isNotEmpty()) {
                    additionalMatchers.addAll(
                        // Anchor this file's path-anchored rules to the directory that declared it.
                        WildcardMatcher.parseAll(patterns, WildcardMatcher.Options(baseDir = dirPath)),
                    )
                }
            }
        }

        // Combine parent matchers with new ones
        val combinedMatchers = if (noParentIgnoreRules) {
            additionalMatchers
        } else {
            matchers + additionalMatchers
        }

        return IgnoreContext(
            matchers = combinedMatchers,
            dotIgnoreFiles = dotIgnoreFiles,
            maxFileSize = maxFileSize,
            oneFileSystem = oneFileSystem,
            rootDevice = rootDevice,
            noParentIgnoreRules = noParentIgnoreRules,
            noParentDotIgnoreFiles = noParentDotIgnoreFiles,
        )
    }

    private fun loadIgnoreFile(dir: Directory, fileName: String): List<String> {
        val localPath = dir.localFilesystemPath
        if (localPath.isEmpty()) {
            return emptyList()
        }

        val ignoreFilePath = Path.of(localPath, fileName)
        if (!ignoreFilePath.exists()) {
            return emptyList()
        }

        return try {
            ignoreFilePath.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        /**
         * Creates an ignore context from a files policy.
         *
         * @param policy The files policy containing ignore rules
         * @param additionalMatchers Additional matchers (e.g., from root dotIgnoreFiles)
         * @param rootDevice Device of the snapshot root; required for [FilesPolicy.oneFileSystem]
         *                   to detect a filesystem boundary at all
         */
        fun create(
            policy: FilesPolicy,
            additionalMatchers: List<WildcardMatcher> = emptyList(),
            rootDevice: DeviceInfo = DeviceInfo.EMPTY,
        ): IgnoreContext {
            val matchers = WildcardMatcher.parseAll(policy.ignoreRules) + additionalMatchers
            val oneFileSystem = policy.oneFileSystem ?: false

            return IgnoreContext(
                matchers = matchers,
                dotIgnoreFiles = policy.dotIgnoreFiles,
                maxFileSize = policy.maxFileSize,
                oneFileSystem = oneFileSystem,
                rootDevice = if (oneFileSystem) rootDevice else DeviceInfo.EMPTY,
                noParentIgnoreRules = policy.noParentIgnoreRules,
                noParentDotIgnoreFiles = policy.noParentDotIgnoreFiles,
            )
        }
    }
}

private fun joinPath(parent: String, child: String): String = if (parent.isEmpty()) child else "$parent/$child"
