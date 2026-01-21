package org.kopiaKt.snapshot.policy

import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Upload policy describing settings to apply when uploading snapshots.
 *
 * Go type: policy.UploadPolicy
 */
@Serializable
data class UploadPolicy(
    val maxParallelSnapshots: Int? = null,
    val maxParallelFileReads: Int? = null,
    val parallelUploadAboveSize: Long? = null
) {
    /**
     * Merges this policy with source policy.
     */
    fun merge(src: UploadPolicy, def: UploadPolicyDefinition, si: SourceInfo): Pair<UploadPolicy, UploadPolicyDefinition> {
        val newDef = def.copy()
        return UploadPolicy(
            maxParallelSnapshots = mergeOptionalInt(maxParallelSnapshots, src.maxParallelSnapshots) {
                newDef.maxParallelSnapshots = si
            },
            maxParallelFileReads = mergeOptionalInt(maxParallelFileReads, src.maxParallelFileReads) {
                newDef.maxParallelFileReads = si
            },
            parallelUploadAboveSize = mergeOptionalLong(parallelUploadAboveSize, src.parallelUploadAboveSize) {
                newDef.parallelUploadAboveSize = si
            }
        ) to newDef
    }

    companion object {
        /**
         * Default upload policy.
         */
        val Default = UploadPolicy(
            maxParallelSnapshots = 1,
            maxParallelFileReads = null, // Defaults to runtime.NumCPUs() equivalent
            parallelUploadAboveSize = 2L shl 30 // 2 GiB
        )
    }
}

/**
 * Specifies which policy definition provided the value of a particular upload field.
 *
 * Go type: policy.UploadPolicyDefinition
 */
@Serializable
data class UploadPolicyDefinition(
    var maxParallelSnapshots: SourceInfo? = null,
    var maxParallelFileReads: SourceInfo? = null,
    var parallelUploadAboveSize: SourceInfo? = null
)

/**
 * Validates that an upload policy is valid.
 * Returns an error message if invalid, null if valid.
 *
 * @param si The source info for the policy
 * @param p The upload policy to validate
 */
fun validateUploadPolicy(si: SourceInfo, p: UploadPolicy): String? {
    // Max parallel snapshots cannot be specified for paths, only global, username@hostname or @hostname
    if (si.path.isNotEmpty() && p.maxParallelSnapshots != null) {
        return "max parallel snapshots cannot be specified for paths, only global, username@hostname or @hostname"
    }
    return null
}

// Helper merge functions
private inline fun mergeOptionalInt(target: Int?, src: Int?, onMerge: () -> Unit): Int? {
    return if (target == null && src != null) {
        onMerge()
        src
    } else {
        target
    }
}

private inline fun mergeOptionalLong(target: Long?, src: Long?, onMerge: () -> Unit): Long? {
    return if (target == null && src != null) {
        onMerge()
        src
    } else {
        target
    }
}
