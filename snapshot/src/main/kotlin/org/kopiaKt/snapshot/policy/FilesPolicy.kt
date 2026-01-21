package org.kopiaKt.snapshot.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * File selection policy describing which files to ignore when taking snapshots.
 *
 * Go type: policy.FilesPolicy
 */
@Serializable
data class FilesPolicy(
    @SerialName("ignore")
    val ignoreRules: List<String> = emptyList(),

    @SerialName("noParentIgnore")
    val noParentIgnoreRules: Boolean = false,

    @SerialName("ignoreDotFiles")
    val dotIgnoreFiles: List<String> = emptyList(),

    @SerialName("noParentDotFiles")
    val noParentDotIgnoreFiles: Boolean = false,

    @SerialName("ignoreCacheDirs")
    val ignoreCacheDirectories: Boolean? = null,

    val maxFileSize: Long = 0,

    @SerialName("oneFileSystem")
    val oneFileSystem: Boolean? = null
) {
    /**
     * Merges this policy with source policy.
     *
     * @param src The source policy to merge from
     * @param def The definition to track which policy provided which value
     * @param si The source info identifying where the source policy comes from
     * @return A pair of the merged FilesPolicy and updated definition
     */
    fun merge(src: FilesPolicy, def: FilesPolicyDefinition, si: SourceInfo): Pair<FilesPolicy, FilesPolicyDefinition> {
        val newDef = def.copy()

        // Merge ignore rules (append unless noParent is set)
        val mergedIgnoreRules = if (noParentIgnoreRules || src.ignoreRules.isEmpty()) {
            ignoreRules
        } else {
            newDef.ignoreRules = si
            (ignoreRules + src.ignoreRules).distinct()
        }

        // Merge dot ignore files (replace if empty)
        val mergedDotIgnoreFiles = if (dotIgnoreFiles.isEmpty() && src.dotIgnoreFiles.isNotEmpty()) {
            newDef.dotIgnoreFiles = si
            src.dotIgnoreFiles
        } else {
            dotIgnoreFiles
        }

        return FilesPolicy(
            ignoreRules = mergedIgnoreRules,
            noParentIgnoreRules = noParentIgnoreRules || src.noParentIgnoreRules,
            dotIgnoreFiles = mergedDotIgnoreFiles,
            noParentDotIgnoreFiles = noParentDotIgnoreFiles || src.noParentDotIgnoreFiles,
            ignoreCacheDirectories = mergeBool(ignoreCacheDirectories, src.ignoreCacheDirectories) {
                newDef.ignoreCacheDirectories = si
            },
            maxFileSize = mergeLong(maxFileSize, src.maxFileSize) {
                newDef.maxFileSize = si
            },
            oneFileSystem = mergeBool(oneFileSystem, src.oneFileSystem) {
                newDef.oneFileSystem = si
            }
        ) to newDef
    }

    companion object {
        /**
         * Default files policy.
         */
        val Default = FilesPolicy(
            dotIgnoreFiles = listOf(".kopiaignore")
        )
    }
}

/**
 * Specifies which policy definition provided the value of a particular files field.
 *
 * Go type: policy.FilesPolicyDefinition
 */
@Serializable
data class FilesPolicyDefinition(
    @SerialName("ignore")
    var ignoreRules: SourceInfo? = null,

    @SerialName("noParentIgnore")
    var noParentIgnoreRules: SourceInfo? = null,

    @SerialName("ignoreDotFiles")
    var dotIgnoreFiles: SourceInfo? = null,

    @SerialName("noParentDotFiles")
    var noParentDotIgnoreFiles: SourceInfo? = null,

    @SerialName("ignoreCacheDirs")
    var ignoreCacheDirectories: SourceInfo? = null,

    var maxFileSize: SourceInfo? = null,

    @SerialName("oneFileSystem")
    var oneFileSystem: SourceInfo? = null
)

// Helper merge functions for policy merging
private inline fun mergeBool(target: Boolean?, src: Boolean?, onMerge: () -> Unit): Boolean? {
    return if (target == null && src != null) {
        onMerge()
        src
    } else {
        target
    }
}

private inline fun mergeLong(target: Long, src: Long, onMerge: () -> Unit): Long {
    return if (target == 0L && src != 0L) {
        onMerge()
        src
    } else {
        target
    }
}
