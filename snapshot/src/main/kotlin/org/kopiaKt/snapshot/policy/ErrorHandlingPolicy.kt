package org.kopiaKt.snapshot.policy

import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Error handling policy controlling behavior when errors occur during snapshots.
 *
 * Go type: policy.ErrorHandlingPolicy
 */
@Serializable
data class ErrorHandlingPolicy(
    /**
     * Controls whether snapshot operation should fail when a file throws an error on being read.
     */
    val ignoreFileErrors: Boolean? = null,

    /**
     * Controls whether snapshot operation should fail when a directory throws an error
     * on being read or opened.
     */
    val ignoreDirectoryErrors: Boolean? = null,

    /**
     * Controls whether snapshot operation should fail when it encounters a directory entry
     * of an unknown type.
     */
    val ignoreUnknownTypes: Boolean? = null,
) {
    /**
     * Merges this policy with source policy.
     */
    fun merge(src: ErrorHandlingPolicy, def: ErrorHandlingPolicyDefinition, si: SourceInfo): Pair<ErrorHandlingPolicy, ErrorHandlingPolicyDefinition> {
        val newDef = def.copy()
        return ErrorHandlingPolicy(
            ignoreFileErrors = mergeOptionalBool(ignoreFileErrors, src.ignoreFileErrors) {
                newDef.ignoreFileErrors = si
            },
            ignoreDirectoryErrors = mergeOptionalBool(ignoreDirectoryErrors, src.ignoreDirectoryErrors) {
                newDef.ignoreDirectoryErrors = si
            },
            ignoreUnknownTypes = mergeOptionalBool(ignoreUnknownTypes, src.ignoreUnknownTypes) {
                newDef.ignoreUnknownTypes = si
            },
        ) to newDef
    }

    companion object {
        /**
         * Default error handling policy.
         */
        val Default = ErrorHandlingPolicy(
            ignoreFileErrors = false,
            ignoreDirectoryErrors = false,
            ignoreUnknownTypes = true,
        )
    }
}

/**
 * Specifies which policy definition provided the value of a particular error handling field.
 *
 * Go type: policy.ErrorHandlingPolicyDefinition
 */
@Serializable
data class ErrorHandlingPolicyDefinition(
    var ignoreFileErrors: SourceInfo? = null,
    var ignoreDirectoryErrors: SourceInfo? = null,
    var ignoreUnknownTypes: SourceInfo? = null,
)

// Helper merge function
private inline fun mergeOptionalBool(target: Boolean?, src: Boolean?, onMerge: () -> Unit): Boolean? = if (target == null && src != null) {
    onMerge()
    src
} else {
    target
}
