package org.kopiaKt.snapshot.fs

import org.kopiaKt.snapshot.policy.FilesPolicy
import java.io.InputStream
import java.nio.file.Files
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
    private val relativePath: String
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
            return null
        }

        return wrapEntry(entry, childPath)
    }

    override suspend fun iterate(): DirectoryIterator {
        return IgnoreDirectoryIterator(
            wrapped.iterate(),
            context,
            relativePath
        )
    }

    override fun supportsMultipleIterations(): Boolean = wrapped.supportsMultipleIterations()

    override fun close() = wrapped.close()

    private fun wrapEntry(entry: Entry, path: String): Entry {
        return when (entry) {
            is Directory -> {
                // Create child context with patterns from .kopiaignore in this directory
                val childContext = context.childContext(entry, path)
                IgnoreFS(entry, childContext, path)
            }
            else -> entry
        }
    }

    companion object {
        /**
         * Wraps a directory with ignore filtering.
         *
         * @param dir The directory to wrap
         * @param policy The files policy containing ignore rules
         * @return A filtered directory view
         */
        fun wrap(dir: Directory, policy: FilesPolicy): Directory {
            // Load patterns from root directory's dotIgnoreFiles
            val rootPatterns = loadIgnoreFiles(dir, policy.dotIgnoreFiles)
            val context = IgnoreContext.create(policy, rootPatterns)
            return IgnoreFS(dir, context, "")
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
            oneFileSystem: Boolean = false
        ): Directory {
            // Load patterns from root directory's dotIgnoreFiles
            val rootPatterns = loadIgnoreFiles(dir, dotIgnoreFiles)
            val combinedMatchers = matchers + rootPatterns

            val context = IgnoreContext(
                matchers = combinedMatchers,
                dotIgnoreFiles = dotIgnoreFiles,
                maxFileSize = maxFileSize,
                oneFileSystem = oneFileSystem,
                rootDevice = if (oneFileSystem) dir.device else DeviceInfo.EMPTY
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
    private val parentPath: String
) : DirectoryIterator {

    override suspend fun next(): Entry? {
        while (true) {
            val entry = wrapped.next() ?: return null
            val entryPath = joinPath(parentPath, entry.name)

            if (context.shouldIgnore(entryPath, entry)) {
                continue
            }

            return when (entry) {
                is Directory -> {
                    val childContext = context.childContext(entry, entryPath)
                    IgnoreFS(entry, childContext, entryPath)
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
    private val noParentDotIgnoreFiles: Boolean = false
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
                        WildcardMatcher.parseAll(patterns)
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
            noParentDotIgnoreFiles = noParentDotIgnoreFiles
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
         */
        fun create(policy: FilesPolicy, additionalMatchers: List<WildcardMatcher> = emptyList()): IgnoreContext {
            val matchers = WildcardMatcher.parseAll(policy.ignoreRules) + additionalMatchers

            return IgnoreContext(
                matchers = matchers,
                dotIgnoreFiles = policy.dotIgnoreFiles,
                maxFileSize = policy.maxFileSize,
                oneFileSystem = policy.oneFileSystem ?: false,
                rootDevice = DeviceInfo.EMPTY,
                noParentIgnoreRules = policy.noParentIgnoreRules,
                noParentDotIgnoreFiles = policy.noParentDotIgnoreFiles
            )
        }
    }
}

private fun joinPath(parent: String, child: String): String {
    return if (parent.isEmpty()) child else "$parent/$child"
}
