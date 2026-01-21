package org.kopiaKt.snapshot.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Actions policy describing actions to be invoked when taking snapshots.
 *
 * Go type: policy.ActionsPolicy
 */
@Serializable
data class ActionsPolicy(
    /**
     * Command to run before processing a folder (not inherited).
     */
    val beforeFolder: ActionCommand? = null,

    /**
     * Command to run after processing a folder (not inherited).
     */
    val afterFolder: ActionCommand? = null,

    /**
     * Command to run before each snapshot root (can be inherited).
     */
    val beforeSnapshotRoot: ActionCommand? = null,

    /**
     * Command to run after each snapshot root (can be inherited).
     */
    val afterSnapshotRoot: ActionCommand? = null
) {
    /**
     * Merges this policy with source policy (only inheritable properties).
     */
    fun merge(src: ActionsPolicy, def: ActionsPolicyDefinition, si: SourceInfo): Pair<ActionsPolicy, ActionsPolicyDefinition> {
        val newDef = def.copy()
        return ActionsPolicy(
            beforeFolder = beforeFolder, // Not inherited
            afterFolder = afterFolder, // Not inherited
            beforeSnapshotRoot = mergeActionCommand(beforeSnapshotRoot, src.beforeSnapshotRoot) {
                newDef.beforeSnapshotRoot = si
            },
            afterSnapshotRoot = mergeActionCommand(afterSnapshotRoot, src.afterSnapshotRoot) {
                newDef.afterSnapshotRoot = si
            }
        ) to newDef
    }

    /**
     * Copies non-inheritable properties from source policy.
     */
    fun withNonInheritable(src: ActionsPolicy): ActionsPolicy {
        return copy(
            beforeFolder = src.beforeFolder,
            afterFolder = src.afterFolder
        )
    }

    companion object {
        /**
         * Default actions policy.
         */
        val Default = ActionsPolicy()
    }
}

/**
 * Specifies which policy definition provided the value of a particular actions field.
 *
 * Go type: policy.ActionsPolicyDefinition
 */
@Serializable
data class ActionsPolicyDefinition(
    var beforeSnapshotRoot: SourceInfo? = null,
    var afterSnapshotRoot: SourceInfo? = null
)

/**
 * Command to execute as a hook action.
 *
 * Go type: policy.ActionCommand
 */
@Serializable
data class ActionCommand(
    /**
     * Command + args to run.
     */
    @SerialName("path")
    val command: String = "",

    @SerialName("args")
    val arguments: List<String> = emptyList(),

    /**
     * Alternatively inline script to run using either Unix shell or cmd.exe on Windows.
     */
    val script: String = "",

    @SerialName("timeout")
    val timeoutSeconds: Int = 0,

    /**
     * Mode: essential, optional, or async.
     */
    val mode: String = ""
) {
    companion object {
        // Action modes
        const val MODE_ESSENTIAL = "essential"
        const val MODE_OPTIONAL = "optional"
        const val MODE_ASYNC = "async"
    }
}

// Helper merge function
private inline fun mergeActionCommand(target: ActionCommand?, src: ActionCommand?, onMerge: () -> Unit): ActionCommand? {
    return if (target == null && src != null) {
        onMerge()
        src
    } else {
        target
    }
}
