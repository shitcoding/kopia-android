package org.kopiaKt.snapshot.policy

import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Supported log detail levels.
 */
object LogDetailLevels {
    const val NONE = 0
    const val NORMAL = 5
    const val MAX = 10
}

/**
 * Log detail level.
 *
 * Go type: policy.LogDetail
 */
typealias LogDetail = Int

/**
 * Returns the log detail value or the default if null.
 */
fun LogDetail?.orDefault(default: LogDetail): LogDetail = this ?: default

/**
 * Directory logging policy for logging directory information during snapshots.
 *
 * Go type: policy.DirLoggingPolicy
 */
@Serializable
data class DirLoggingPolicy(
    val snapshotted: LogDetail? = null,
    val ignored: LogDetail? = null,
) {
    /**
     * Merges this policy with source policy.
     */
    fun merge(src: DirLoggingPolicy, def: DirLoggingPolicyDefinition, si: SourceInfo): Pair<DirLoggingPolicy, DirLoggingPolicyDefinition> {
        val newDef = def.copy()
        return DirLoggingPolicy(
            snapshotted = mergeLogDetail(snapshotted, src.snapshotted) {
                newDef.snapshotted = si
            },
            ignored = mergeLogDetail(ignored, src.ignored) {
                newDef.ignored = si
            },
        ) to newDef
    }
}

/**
 * Specifies which policy definition provided the value of a particular dir logging field.
 *
 * Go type: policy.DirLoggingPolicyDefinition
 */
@Serializable
data class DirLoggingPolicyDefinition(
    var snapshotted: SourceInfo? = null,
    var ignored: SourceInfo? = null,
)

/**
 * Entry logging policy for logging entry information during snapshots.
 *
 * Go type: policy.EntryLoggingPolicy
 */
@Serializable
data class EntryLoggingPolicy(
    val snapshotted: LogDetail? = null,
    val ignored: LogDetail? = null,
    val cacheHit: LogDetail? = null,
    val cacheMiss: LogDetail? = null,
) {
    /**
     * Merges this policy with source policy.
     */
    fun merge(src: EntryLoggingPolicy, def: EntryLoggingPolicyDefinition, si: SourceInfo): Pair<EntryLoggingPolicy, EntryLoggingPolicyDefinition> {
        val newDef = def.copy()
        return EntryLoggingPolicy(
            snapshotted = mergeLogDetail(snapshotted, src.snapshotted) {
                newDef.snapshotted = si
            },
            ignored = mergeLogDetail(ignored, src.ignored) {
                newDef.ignored = si
            },
            cacheHit = mergeLogDetail(cacheHit, src.cacheHit) {
                newDef.cacheHit = si
            },
            cacheMiss = mergeLogDetail(cacheMiss, src.cacheMiss) {
                newDef.cacheMiss = si
            },
        ) to newDef
    }
}

/**
 * Specifies which policy definition provided the value of a particular entry logging field.
 *
 * Go type: policy.EntryLoggingPolicyDefinition
 */
@Serializable
data class EntryLoggingPolicyDefinition(
    var snapshotted: SourceInfo? = null,
    var ignored: SourceInfo? = null,
    var cacheHit: SourceInfo? = null,
    var cacheMiss: SourceInfo? = null,
)

/**
 * Logging policy for emitting logs during snapshots.
 *
 * Go type: policy.LoggingPolicy
 */
@Serializable
data class LoggingPolicy(
    val directories: DirLoggingPolicy = DirLoggingPolicy(),
    val entries: EntryLoggingPolicy = EntryLoggingPolicy(),
) {
    /**
     * Merges this policy with source policy.
     */
    fun merge(src: LoggingPolicy, def: LoggingPolicyDefinition, si: SourceInfo): Pair<LoggingPolicy, LoggingPolicyDefinition> {
        val newDef = def.copy()
        val (mergedDirs, mergedDirsDef) = directories.merge(src.directories, newDef.directories, si)
        newDef.directories = mergedDirsDef
        val (mergedEntries, mergedEntriesDef) = entries.merge(src.entries, newDef.entries, si)
        newDef.entries = mergedEntriesDef
        return LoggingPolicy(
            directories = mergedDirs,
            entries = mergedEntries,
        ) to newDef
    }

    companion object {
        /**
         * Default logging policy.
         */
        val Default = LoggingPolicy(
            directories = DirLoggingPolicy(
                snapshotted = LogDetailLevels.NORMAL,
                ignored = LogDetailLevels.NORMAL,
            ),
            entries = EntryLoggingPolicy(
                snapshotted = LogDetailLevels.NONE,
                ignored = LogDetailLevels.NORMAL,
                cacheHit = LogDetailLevels.NONE,
                cacheMiss = LogDetailLevels.NONE,
            ),
        )
    }
}

/**
 * Specifies which policy definition provided the value of a particular logging field.
 *
 * Go type: policy.LoggingPolicyDefinition
 */
@Serializable
data class LoggingPolicyDefinition(
    var directories: DirLoggingPolicyDefinition = DirLoggingPolicyDefinition(),
    var entries: EntryLoggingPolicyDefinition = EntryLoggingPolicyDefinition(),
)

// Helper merge function
private inline fun mergeLogDetail(target: LogDetail?, src: LogDetail?, onMerge: () -> Unit): LogDetail? = if (target == null && src != null) {
    onMerge()
    src
} else {
    target
}
