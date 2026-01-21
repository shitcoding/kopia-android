package org.kopiaKt.snapshot.policy

import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Computes the effective policy by applying the specified list of policies in order
 * from most specific to most general.
 *
 * Go function: policy.MergePolicies
 *
 * @param policies List of policies ordered from most specific to most general
 * @param si The source info for the target path
 * @return Pair of merged Policy and PolicyDefinition
 */
fun mergePolicies(policies: List<Policy>, si: SourceInfo): Pair<Policy, PolicyDefinition> {
    var merged = Policy(labels = Policy.labelsForSource(si))
    var def = PolicyDefinition()

    for (p in policies) {
        val target = p.target()

        // Merge all sub-policies
        val (mergedRetention, mergedRetentionDef) = merged.retentionPolicy.merge(p.retentionPolicy, def.retentionPolicy, target)
        def.retentionPolicy = mergedRetentionDef

        val (mergedFiles, mergedFilesDef) = merged.filesPolicy.merge(p.filesPolicy, def.filesPolicy, target)
        def.filesPolicy = mergedFilesDef

        val (mergedErrorHandling, mergedErrorHandlingDef) = merged.errorHandlingPolicy.merge(p.errorHandlingPolicy, def.errorHandlingPolicy, target)
        def.errorHandlingPolicy = mergedErrorHandlingDef

        val (mergedScheduling, mergedSchedulingDef) = merged.schedulingPolicy.merge(p.schedulingPolicy, def.schedulingPolicy, target)
        def.schedulingPolicy = mergedSchedulingDef

        val (mergedCompression, mergedCompressionDef) = merged.compressionPolicy.merge(p.compressionPolicy, def.compressionPolicy, target)
        def.compressionPolicy = mergedCompressionDef

        val (mergedMetadataCompression, mergedMetadataCompressionDef) = merged.metadataCompressionPolicy.merge(p.metadataCompressionPolicy, def.metadataCompressionPolicy, target)
        def.metadataCompressionPolicy = mergedMetadataCompressionDef

        val (mergedSplitter, mergedSplitterDef) = merged.splitterPolicy.merge(p.splitterPolicy, def.splitterPolicy, target)
        def.splitterPolicy = mergedSplitterDef

        val (mergedActions, mergedActionsDef) = merged.actionsPolicy.merge(p.actionsPolicy, def.actionsPolicy, target)
        def.actionsPolicy = mergedActionsDef

        val (mergedOSSnapshot, mergedOSSnapshotDef) = merged.osSnapshotPolicy.merge(p.osSnapshotPolicy, def.osSnapshotPolicy, target)
        def.osSnapshotPolicy = mergedOSSnapshotDef

        val (mergedLogging, mergedLoggingDef) = merged.loggingPolicy.merge(p.loggingPolicy, def.loggingPolicy, target)
        def.loggingPolicy = mergedLoggingDef

        val (mergedUpload, mergedUploadDef) = merged.uploadPolicy.merge(p.uploadPolicy, def.uploadPolicy, target)
        def.uploadPolicy = mergedUploadDef

        merged = merged.copy(
            retentionPolicy = mergedRetention,
            filesPolicy = mergedFiles,
            errorHandlingPolicy = mergedErrorHandling,
            schedulingPolicy = mergedScheduling,
            compressionPolicy = mergedCompression,
            metadataCompressionPolicy = mergedMetadataCompression,
            splitterPolicy = mergedSplitter,
            actionsPolicy = mergedActions,
            osSnapshotPolicy = mergedOSSnapshot,
            loggingPolicy = mergedLogging,
            uploadPolicy = mergedUpload
        )

        // If noParent is set, stop merging
        if (p.noParent) {
            return merged to def
        }
    }

    // Merge with defaults
    val globalSource = Policy.GlobalPolicySourceInfo

    val (finalRetention, finalRetentionDef) = merged.retentionPolicy.merge(RetentionPolicy.Default, def.retentionPolicy, globalSource)
    def.retentionPolicy = finalRetentionDef

    val (finalFiles, finalFilesDef) = merged.filesPolicy.merge(FilesPolicy.Default, def.filesPolicy, globalSource)
    def.filesPolicy = finalFilesDef

    val (finalErrorHandling, finalErrorHandlingDef) = merged.errorHandlingPolicy.merge(ErrorHandlingPolicy.Default, def.errorHandlingPolicy, globalSource)
    def.errorHandlingPolicy = finalErrorHandlingDef

    val (finalScheduling, finalSchedulingDef) = merged.schedulingPolicy.merge(SchedulingPolicy.Default, def.schedulingPolicy, globalSource)
    def.schedulingPolicy = finalSchedulingDef

    val (finalCompression, finalCompressionDef) = merged.compressionPolicy.merge(CompressionPolicy.Default, def.compressionPolicy, globalSource)
    def.compressionPolicy = finalCompressionDef

    val (finalMetadataCompression, finalMetadataCompressionDef) = merged.metadataCompressionPolicy.merge(MetadataCompressionPolicy.Default, def.metadataCompressionPolicy, globalSource)
    def.metadataCompressionPolicy = finalMetadataCompressionDef

    val (finalSplitter, finalSplitterDef) = merged.splitterPolicy.merge(SplitterPolicy.Default, def.splitterPolicy, globalSource)
    def.splitterPolicy = finalSplitterDef

    val (finalActions, finalActionsDef) = merged.actionsPolicy.merge(ActionsPolicy.Default, def.actionsPolicy, globalSource)
    def.actionsPolicy = finalActionsDef

    val (finalOSSnapshot, finalOSSnapshotDef) = merged.osSnapshotPolicy.merge(OSSnapshotPolicy.Default, def.osSnapshotPolicy, globalSource)
    def.osSnapshotPolicy = finalOSSnapshotDef

    val (finalLogging, finalLoggingDef) = merged.loggingPolicy.merge(LoggingPolicy.Default, def.loggingPolicy, globalSource)
    def.loggingPolicy = finalLoggingDef

    val (finalUpload, finalUploadDef) = merged.uploadPolicy.merge(UploadPolicy.Default, def.uploadPolicy, globalSource)
    def.uploadPolicy = finalUploadDef

    merged = merged.copy(
        retentionPolicy = finalRetention,
        filesPolicy = finalFiles,
        errorHandlingPolicy = finalErrorHandling,
        schedulingPolicy = finalScheduling,
        compressionPolicy = finalCompression,
        metadataCompressionPolicy = finalMetadataCompression,
        splitterPolicy = finalSplitter,
        actionsPolicy = finalActions,
        osSnapshotPolicy = finalOSSnapshot,
        loggingPolicy = finalLogging,
        uploadPolicy = finalUpload
    )

    // Copy non-inheritable actions from the most specific policy
    if (policies.isNotEmpty()) {
        merged = merged.copy(
            actionsPolicy = merged.actionsPolicy.withNonInheritable(policies[0].actionsPolicy)
        )
    }

    return merged to def
}

/**
 * Default policy with all default values applied.
 */
val DefaultPolicy = Policy(
    labels = Policy.labelsForSource(Policy.GlobalPolicySourceInfo),
    filesPolicy = FilesPolicy.Default,
    retentionPolicy = RetentionPolicy.Default,
    compressionPolicy = CompressionPolicy.Default,
    metadataCompressionPolicy = MetadataCompressionPolicy.Default,
    errorHandlingPolicy = ErrorHandlingPolicy.Default,
    schedulingPolicy = SchedulingPolicy.Default,
    loggingPolicy = LoggingPolicy.Default,
    actionsPolicy = ActionsPolicy.Default,
    osSnapshotPolicy = OSSnapshotPolicy.Default,
    uploadPolicy = UploadPolicy.Default
)

/**
 * Validates a policy for a given source.
 * Returns an error message if invalid, null if valid.
 */
fun validatePolicy(si: SourceInfo, pol: Policy): String? {
    validateSchedulingPolicy(pol.schedulingPolicy)?.let { return "invalid scheduling policy: $it" }
    validateUploadPolicy(si, pol.uploadPolicy)?.let { return "invalid upload policy: $it" }
    return null
}
