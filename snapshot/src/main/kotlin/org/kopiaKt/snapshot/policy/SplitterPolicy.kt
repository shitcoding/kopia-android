package org.kopiaKt.snapshot.policy

import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Splitter policy specifying content chunking algorithm.
 *
 * Go type: policy.SplitterPolicy
 */
@Serializable
data class SplitterPolicy(
    val algorithm: String = ""
) {
    /**
     * Returns the splitter algorithm to use for a file.
     */
    fun splitterForFile(): String = algorithm

    /**
     * Merges this policy with source policy.
     */
    fun merge(src: SplitterPolicy, def: SplitterPolicyDefinition, si: SourceInfo): Pair<SplitterPolicy, SplitterPolicyDefinition> {
        val newDef = def.copy()
        return SplitterPolicy(
            algorithm = mergeString(algorithm, src.algorithm) {
                newDef.algorithm = si
            }
        ) to newDef
    }

    companion object {
        /**
         * Default splitter policy.
         */
        val Default = SplitterPolicy()
    }
}

/**
 * Specifies which policy definition provided the value of a particular splitter field.
 *
 * Go type: policy.SplitterPolicyDefinition
 */
@Serializable
data class SplitterPolicyDefinition(
    var algorithm: SourceInfo? = null
)

private inline fun mergeString(target: String, src: String, onMerge: () -> Unit): String {
    return if (target.isEmpty() && src.isNotEmpty()) {
        onMerge()
        src
    } else {
        target
    }
}
