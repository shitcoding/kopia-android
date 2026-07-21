package org.kopiaKt.snapshot.upload

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Estimation parameters for upload size estimation.
 *
 * Go type: upload.EstimationParameters
 */
data class EstimationParameters(
    val type: EstimationType = EstimationType.CLASSIC,
    val adaptiveThreshold: Long = ADAPTIVE_ESTIMATION_THRESHOLD,
) {
    companion object {
        const val ADAPTIVE_ESTIMATION_THRESHOLD = 300_000L
    }
}

/**
 * Type of estimation to use for upload size.
 */
enum class EstimationType {
    /**
     * Old way of estimation, which assumes iterating over all files.
     */
    CLASSIC,

    /**
     * New way of estimation, which looks into filesystem stats to get amount of data.
     */
    ROUGH,

    /**
     * Combination of new and old approaches. If the estimated file count is high,
     * it will use a rough estimation. If the count is low, it will switch to classic.
     */
    ADAPTIVE,
}

/**
 * Progress callback interface for upload operations.
 *
 * Implementations receive notifications about upload progress, allowing
 * them to display progress to the user or perform logging.
 *
 * Go type: upload.Progress
 */
interface UploadProgress {
    /**
     * Returns true when progress is enabled, false otherwise.
     */
    fun enabled(): Boolean

    /**
     * Emitted once at the start of an upload.
     */
    fun uploadStarted()

    /**
     * Emitted once at the end of an upload.
     */
    fun uploadFinished()

    /**
     * Emitted whenever uploader reuses previously uploaded entry without hashing the file.
     *
     * @param path The file path
     * @param size The file size in bytes
     */
    fun cachedFile(path: String, size: Long)

    /**
     * Emitted at the beginning of hashing of a given file.
     *
     * @param filename The name of the file being hashed
     */
    fun hashingFile(filename: String)

    /**
     * Emitted when a file is excluded.
     *
     * @param filename The name of the excluded file
     * @param size The file size in bytes
     */
    fun excludedFile(filename: String, size: Long)

    /**
     * Emitted when a directory is excluded.
     *
     * @param dirname The name of the excluded directory
     */
    fun excludedDir(dirname: String)

    /**
     * Emitted at the end of hashing of a given file.
     *
     * @param filename The name of the file
     * @param numBytes The number of bytes hashed
     */
    fun finishedHashingFile(filename: String, numBytes: Long)

    /**
     * Emitted when the uploader is done with a file, regardless of if it was hashed or cached.
     * If an error was encountered it reports that too.
     *
     * @param filename The name of the file
     * @param error The error encountered, or null if successful
     */
    fun finishedFile(filename: String, error: Throwable?)

    /**
     * Emitted while hashing any blocks of bytes.
     *
     * @param numBytes The number of bytes hashed
     */
    fun hashedBytes(numBytes: Long)

    /**
     * Emitted when an error is encountered.
     *
     * @param path The path where error occurred
     * @param error The error that occurred
     * @param isIgnored Whether the error was ignored
     */
    fun error(path: String, error: Throwable, isIgnored: Boolean)

    /**
     * Emitted whenever bytes are written to the blob storage.
     *
     * @param numBytes The number of bytes uploaded
     */
    fun uploadedBytes(numBytes: Long)

    /**
     * Emitted whenever a directory starts being uploaded.
     *
     * @param dirname The directory name/path
     */
    fun startedDirectory(dirname: String)

    /**
     * Emitted whenever a directory is finished uploading.
     *
     * @param dirname The directory name/path
     */
    fun finishedDirectory(dirname: String)

    /**
     * Returns settings to be used for estimation.
     */
    fun estimationParameters(): EstimationParameters

    /**
     * Emitted whenever the size of upload is estimated.
     *
     * @param fileCount The estimated file count
     * @param totalBytes The estimated total bytes
     */
    fun estimatedDataSize(fileCount: Long, totalBytes: Long)
}

/**
 * Null implementation of UploadProgress that does not produce any output.
 *
 * Go type: upload.NullUploadProgress
 */
open class NullUploadProgress : UploadProgress {
    override fun enabled(): Boolean = false
    override fun uploadStarted() {}
    override fun uploadFinished() {}
    override fun cachedFile(path: String, size: Long) {}
    override fun hashingFile(filename: String) {}
    override fun excludedFile(filename: String, size: Long) {}
    override fun excludedDir(dirname: String) {}
    override fun finishedHashingFile(filename: String, numBytes: Long) {}
    override fun finishedFile(filename: String, error: Throwable?) {}
    override fun hashedBytes(numBytes: Long) {}
    override fun error(path: String, error: Throwable, isIgnored: Boolean) {}
    override fun uploadedBytes(numBytes: Long) {}
    override fun startedDirectory(dirname: String) {}
    override fun finishedDirectory(dirname: String) {}
    override fun estimationParameters(): EstimationParameters = EstimationParameters(EstimationType.CLASSIC)
    override fun estimatedDataSize(fileCount: Long, totalBytes: Long) {}
}

/**
 * Snapshot of upload counters.
 *
 * Go type: upload.Counters
 */
data class UploadCounters(
    val totalCachedBytes: Long = 0,
    val totalHashedBytes: Long = 0,
    val totalUploadedBytes: Long = 0,
    val estimatedBytes: Long = 0,
    val totalCachedFiles: Int = 0,
    val totalHashedFiles: Int = 0,
    val totalExcludedFiles: Int = 0,
    val totalExcludedDirs: Int = 0,
    val fatalErrorCount: Int = 0,
    val ignoredErrorCount: Int = 0,
    val estimatedFiles: Long = 0,
    val currentDirectory: String = "",
    val lastErrorPath: String = "",
    val lastError: String = "",
)

/**
 * Implementation of UploadProgress that accumulates counters.
 *
 * Thread-safe using atomic operations.
 *
 * Go type: upload.CountingUploadProgress
 */
open class CountingUploadProgress : UploadProgress {

    private val totalCachedBytes = AtomicLong(0)
    private val totalHashedBytes = AtomicLong(0)
    private val totalUploadedBytes = AtomicLong(0)
    private val estimatedBytes = AtomicLong(0)

    private val totalCachedFiles = AtomicInteger(0)
    private val totalHashedFiles = AtomicInteger(0)
    private val totalExcludedFiles = AtomicInteger(0)
    private val totalExcludedDirs = AtomicInteger(0)
    private val fatalErrorCount = AtomicInteger(0)
    private val ignoredErrorCount = AtomicInteger(0)
    private val estimatedFiles = AtomicLong(0)

    private val currentDirectory = AtomicReference("")
    private val lastErrorPath = AtomicReference("")
    private val lastError = AtomicReference("")

    override fun enabled(): Boolean = true

    override fun uploadStarted() {
        // Reset counters to all-zero values
        totalCachedBytes.set(0)
        totalHashedBytes.set(0)
        totalUploadedBytes.set(0)
        estimatedBytes.set(0)
        totalCachedFiles.set(0)
        totalHashedFiles.set(0)
        totalExcludedFiles.set(0)
        totalExcludedDirs.set(0)
        fatalErrorCount.set(0)
        ignoredErrorCount.set(0)
        estimatedFiles.set(0)
        currentDirectory.set("")
        lastErrorPath.set("")
        lastError.set("")
    }

    override fun uploadFinished() {}

    override fun cachedFile(path: String, size: Long) {
        totalCachedFiles.incrementAndGet()
        totalCachedBytes.addAndGet(size)
    }

    override fun hashingFile(filename: String) {}

    override fun excludedFile(filename: String, size: Long) {
        totalExcludedFiles.incrementAndGet()
    }

    override fun excludedDir(dirname: String) {
        totalExcludedDirs.incrementAndGet()
    }

    override fun finishedHashingFile(filename: String, numBytes: Long) {
        totalHashedFiles.incrementAndGet()
    }

    override fun finishedFile(filename: String, error: Throwable?) {}

    override fun hashedBytes(numBytes: Long) {
        totalHashedBytes.addAndGet(numBytes)
    }

    override fun error(path: String, error: Throwable, isIgnored: Boolean) {
        if (isIgnored) {
            ignoredErrorCount.incrementAndGet()
        } else {
            fatalErrorCount.incrementAndGet()
        }
        lastErrorPath.set(path)
        lastError.set(error.message ?: error.toString())
    }

    override fun uploadedBytes(numBytes: Long) {
        totalUploadedBytes.addAndGet(numBytes)
    }

    override fun startedDirectory(dirname: String) {
        currentDirectory.set(dirname)
    }

    override fun finishedDirectory(dirname: String) {}

    override fun estimationParameters(): EstimationParameters = EstimationParameters(EstimationType.CLASSIC)

    override fun estimatedDataSize(fileCount: Long, totalBytes: Long) {
        estimatedBytes.set(totalBytes)
        estimatedFiles.set(fileCount)
    }

    /**
     * Captures current snapshot of the upload counters.
     */
    fun snapshot(): UploadCounters = UploadCounters(
        totalCachedBytes = totalCachedBytes.get(),
        totalHashedBytes = totalHashedBytes.get(),
        totalUploadedBytes = totalUploadedBytes.get(),
        estimatedBytes = estimatedBytes.get(),
        totalCachedFiles = totalCachedFiles.get(),
        totalHashedFiles = totalHashedFiles.get(),
        totalExcludedFiles = totalExcludedFiles.get(),
        totalExcludedDirs = totalExcludedDirs.get(),
        fatalErrorCount = fatalErrorCount.get(),
        ignoredErrorCount = ignoredErrorCount.get(),
        estimatedFiles = estimatedFiles.get(),
        currentDirectory = currentDirectory.get(),
        lastErrorPath = lastErrorPath.get(),
        lastError = lastError.get(),
    )
}

/**
 * Implementation of UploadProgress that delegates to a lambda callback.
 *
 * Useful for simple progress reporting.
 */
class CallbackUploadProgress(
    private val onProgress: (UploadCounters) -> Unit,
) : CountingUploadProgress() {

    override fun hashedBytes(numBytes: Long) {
        super.hashedBytes(numBytes)
        onProgress(snapshot())
    }

    override fun uploadedBytes(numBytes: Long) {
        super.uploadedBytes(numBytes)
        onProgress(snapshot())
    }

    override fun finishedFile(filename: String, error: Throwable?) {
        super.finishedFile(filename, error)
        onProgress(snapshot())
    }

    override fun finishedDirectory(dirname: String) {
        super.finishedDirectory(dirname)
        onProgress(snapshot())
    }
}
