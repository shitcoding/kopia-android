package org.kopiaKt.snapshot.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Complete policy for a backup source.
 *
 * Policies control retention, file selection, compression, and scheduling.
 * The structure must match the Go implementation for cross-compatibility.
 *
 * Go type: policy.Policy
 */
@Serializable
data class Policy(
    /**
     * Labels are not serialized in JSON but used for policy identification.
     * Go field: Labels map[string]string `json:"-"`
     */
    @kotlinx.serialization.Transient
    val labels: Map<String, String> = emptyMap(),

    @SerialName("retention")
    val retentionPolicy: RetentionPolicy = RetentionPolicy(),

    @SerialName("files")
    val filesPolicy: FilesPolicy = FilesPolicy(),

    @SerialName("errorHandling")
    val errorHandlingPolicy: ErrorHandlingPolicy = ErrorHandlingPolicy(),

    @SerialName("scheduling")
    val schedulingPolicy: SchedulingPolicy = SchedulingPolicy(),

    @SerialName("compression")
    val compressionPolicy: CompressionPolicy = CompressionPolicy(),

    @SerialName("metadataCompression")
    val metadataCompressionPolicy: MetadataCompressionPolicy = MetadataCompressionPolicy(),

    @SerialName("splitter")
    val splitterPolicy: SplitterPolicy = SplitterPolicy(),

    @SerialName("actions")
    val actionsPolicy: ActionsPolicy = ActionsPolicy(),

    @SerialName("osSnapshots")
    val osSnapshotPolicy: OSSnapshotPolicy = OSSnapshotPolicy(),

    @SerialName("logging")
    val loggingPolicy: LoggingPolicy = LoggingPolicy(),

    @SerialName("upload")
    val uploadPolicy: UploadPolicy = UploadPolicy(),

    @SerialName("noParent")
    val noParent: Boolean = false
) {
    /**
     * Returns the globally unique identifier of the policy.
     */
    fun id(): String? = labels["id"]

    /**
     * Returns the SourceInfo describing username, host and path targeted by the policy.
     */
    fun target(): SourceInfo = SourceInfo(
        host = labels["hostname"] ?: "",
        userName = labels["username"] ?: "",
        path = labels["path"] ?: ""
    )

    companion object {
        /**
         * Policy type label value.
         */
        const val POLICY_TYPE = "policy"

        /**
         * Global policy source info (empty source).
         */
        val GlobalPolicySourceInfo = SourceInfo(host = "", userName = "", path = "")

        /**
         * Creates labels for a policy targeting the given source.
         */
        fun labelsForSource(source: SourceInfo): Map<String, String> {
            val labels = mutableMapOf(
                "type" to POLICY_TYPE
            )

            if (source.host.isEmpty() && source.userName.isEmpty() && source.path.isEmpty()) {
                labels["policyType"] = "global"
            } else if (source.path.isEmpty()) {
                labels["policyType"] = if (source.userName.isEmpty()) "host" else "user"
                labels["hostname"] = source.host
                if (source.userName.isNotEmpty()) {
                    labels["username"] = source.userName
                }
            } else {
                labels["policyType"] = "path"
                labels["hostname"] = source.host
                labels["username"] = source.userName
                labels["path"] = source.path
            }

            return labels
        }
    }
}

/**
 * Definition corresponds 1:1 to Policy and each field specifies the SourceInfo
 * where a particular policy field was specified.
 *
 * Go type: policy.Definition
 */
@Serializable
data class PolicyDefinition(
    @SerialName("retention")
    var retentionPolicy: RetentionPolicyDefinition = RetentionPolicyDefinition(),

    @SerialName("files")
    var filesPolicy: FilesPolicyDefinition = FilesPolicyDefinition(),

    @SerialName("errorHandling")
    var errorHandlingPolicy: ErrorHandlingPolicyDefinition = ErrorHandlingPolicyDefinition(),

    @SerialName("scheduling")
    var schedulingPolicy: SchedulingPolicyDefinition = SchedulingPolicyDefinition(),

    @SerialName("compression")
    var compressionPolicy: CompressionPolicyDefinition = CompressionPolicyDefinition(),

    @SerialName("metadataCompression")
    var metadataCompressionPolicy: MetadataCompressionPolicyDefinition = MetadataCompressionPolicyDefinition(),

    @SerialName("splitter")
    var splitterPolicy: SplitterPolicyDefinition = SplitterPolicyDefinition(),

    @SerialName("actions")
    var actionsPolicy: ActionsPolicyDefinition = ActionsPolicyDefinition(),

    @SerialName("osSnapshots")
    var osSnapshotPolicy: OSSnapshotPolicyDefinition = OSSnapshotPolicyDefinition(),

    @SerialName("logging")
    var loggingPolicy: LoggingPolicyDefinition = LoggingPolicyDefinition(),

    @SerialName("upload")
    var uploadPolicy: UploadPolicyDefinition = UploadPolicyDefinition()
)

/**
 * Wraps a policy with its target and ID.
 *
 * Go type: policy.TargetWithPolicy
 */
@Serializable
data class TargetWithPolicy(
    val id: String,
    val target: SourceInfo,
    val policy: Policy
)
