package org.kopiaKt.snapshot.policy

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * OS-level snapshot modes.
 *
 * Go type: policy.OSSnapshotMode
 */
@Serializable(with = OSSnapshotModeSerializer::class)
enum class OSSnapshotMode {
    /**
     * Disable OS-level snapshots.
     */
    NEVER,

    /**
     * Fail if an OS-level snapshot cannot be created.
     */
    ALWAYS,

    /**
     * Fall back to regular file access on error.
     */
    WHEN_AVAILABLE,

    ;

    override fun toString(): String = when (this) {
        NEVER -> "never"
        ALWAYS -> "always"
        WHEN_AVAILABLE -> "when-available"
    }

    companion object {
        fun fromString(s: String): OSSnapshotMode = when (s.lowercase()) {
            "never" -> NEVER
            "always" -> ALWAYS
            "when-available" -> WHEN_AVAILABLE
            else -> NEVER
        }
    }
}

/**
 * Serializer for OSSnapshotMode.
 *
 * Go's `OSSnapshotMode` is a bare `byte` with no custom JSON marshalling (`os_snapshot_policy.go`),
 * so it crosses the wire as a NUMBER: 0 never, 1 always, 2 when-available. Encoding it as the
 * human-readable string made every policy manifest Go had written -- including the global policy
 * `kopia repository create` writes -- fail to decode, which took every backup down with it.
 */
object OSSnapshotModeSerializer : KSerializer<OSSnapshotMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OSSnapshotMode", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: OSSnapshotMode) {
        encoder.encodeInt(value.ordinal)
    }

    override fun deserialize(decoder: Decoder): OSSnapshotMode {
        val ordinal = decoder.decodeInt()
        return OSSnapshotMode.entries.getOrElse(ordinal) { OSSnapshotMode.NEVER }
    }
}

/**
 * Returns the OS snapshot mode or the default if null.
 */
fun OSSnapshotMode?.orDefault(default: OSSnapshotMode): OSSnapshotMode = this ?: default

/**
 * Volume Shadow Copy policy for Windows.
 *
 * Go type: policy.VolumeShadowCopyPolicy
 */
@Serializable
data class VolumeShadowCopyPolicy(
    val enable: OSSnapshotMode? = null,
) {
    /**
     * Merges this policy with source policy.
     */
    fun merge(src: VolumeShadowCopyPolicy, def: VolumeShadowCopyPolicyDefinition, si: SourceInfo): Pair<VolumeShadowCopyPolicy, VolumeShadowCopyPolicyDefinition> {
        val newDef = def.copy()
        return VolumeShadowCopyPolicy(
            enable = mergeOSSnapshotMode(enable, src.enable) {
                newDef.enable = si
            },
        ) to newDef
    }
}

/**
 * Specifies which policy definition provided the VSS policy value.
 *
 * Go type: policy.VolumeShadowCopyPolicyDefinition
 */
@Serializable
data class VolumeShadowCopyPolicyDefinition(
    var enable: SourceInfo? = null,
)

/**
 * OS snapshot policy for OS-level snapshots (e.g., Windows VSS).
 *
 * Go type: policy.OSSnapshotPolicy
 */
@Serializable
data class OSSnapshotPolicy(
    val volumeShadowCopy: VolumeShadowCopyPolicy = VolumeShadowCopyPolicy(),
) {
    /**
     * Merges this policy with source policy.
     */
    fun merge(src: OSSnapshotPolicy, def: OSSnapshotPolicyDefinition, si: SourceInfo): Pair<OSSnapshotPolicy, OSSnapshotPolicyDefinition> {
        val newDef = def.copy()
        val (mergedVSC, mergedVSCDef) = volumeShadowCopy.merge(src.volumeShadowCopy, newDef.volumeShadowCopy, si)
        newDef.volumeShadowCopy = mergedVSCDef
        return OSSnapshotPolicy(
            volumeShadowCopy = mergedVSC,
        ) to newDef
    }

    companion object {
        /**
         * Default OS snapshot policy.
         */
        val Default = OSSnapshotPolicy(
            volumeShadowCopy = VolumeShadowCopyPolicy(
                enable = OSSnapshotMode.NEVER,
            ),
        )
    }
}

/**
 * Specifies which policy definition provided the OS snapshot policy value.
 *
 * Go type: policy.OSSnapshotPolicyDefinition
 */
@Serializable
data class OSSnapshotPolicyDefinition(
    var volumeShadowCopy: VolumeShadowCopyPolicyDefinition = VolumeShadowCopyPolicyDefinition(),
)

// Helper merge function
private inline fun mergeOSSnapshotMode(target: OSSnapshotMode?, src: OSSnapshotMode?, onMerge: () -> Unit): OSSnapshotMode? = if (target == null && src != null) {
    onMerge()
    src
} else {
    target
}
