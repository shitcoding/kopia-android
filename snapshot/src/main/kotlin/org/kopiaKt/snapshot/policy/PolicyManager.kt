package org.kopiaKt.snapshot.policy

import kotlinx.serialization.serializer
import org.kopiaKt.core.manifest.EntryMetadata
import org.kopiaKt.core.repository.Repository
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Manages CRUD and hierarchical resolution for backup policies.
 *
 * Policies are stored as manifests in the repository with label-based querying.
 * The hierarchy for effective policy resolution is:
 *   source (path) > user > host > global > built-in defaults
 *
 * Go equivalent: policy package functions (GetPolicy, SetPolicy, etc.)
 */
object PolicyManager {

    /**
     * Stores a policy for the given source, replacing any existing policy for
     * the same source.
     *
     * @param repo Repository to store the policy in
     * @param sourceInfo The backup source this policy targets
     * @param policy The policy to store
     */
    suspend fun setPolicy(repo: Repository, sourceInfo: SourceInfo, policy: Policy) {
        val writer = repo.newWriter()
        try {
            val labels = Policy.labelsForSource(sourceInfo)
            writer.replaceManifests(labels, policy, serializer<Policy>())
            writer.flush()
        } finally {
            writer.close()
        }
        // Refresh the repo so it sees the newly written manifests
        repo.refresh()
    }

    /**
     * Retrieves the policy for a specific source, or null if no policy is set.
     *
     * @param repo Repository to query
     * @param sourceInfo The backup source to look up
     * @return The policy for this source, or null if none exists
     */
    suspend fun getPolicy(repo: Repository, sourceInfo: SourceInfo): Policy? {
        val labels = Policy.labelsForSource(sourceInfo)
        val manifests = repo.findManifests(labels)
        if (manifests.isEmpty()) return null

        val latest = pickLatestMetadata(manifests)
        val (policy, _) = repo.getManifest(latest.id, serializer<Policy>())
        return policy
    }

    /**
     * Deletes the policy for the given source. If no policy exists, this is a no-op.
     *
     * @param repo Repository to delete from
     * @param sourceInfo The backup source whose policy to delete
     */
    suspend fun deletePolicy(repo: Repository, sourceInfo: SourceInfo) {
        val labels = Policy.labelsForSource(sourceInfo)
        val manifests = repo.findManifests(labels)
        if (manifests.isEmpty()) return

        val writer = repo.newWriter()
        try {
            for (meta in manifests) {
                writer.deleteManifest(meta.id)
            }
            writer.flush()
        } finally {
            writer.close()
        }
        // Refresh the repo so it sees the deletion
        repo.refresh()
    }

    /**
     * Lists all policies in the repository.
     *
     * @param repo Repository to query
     * @return List of TargetWithPolicy entries for all stored policies
     */
    suspend fun listPolicies(repo: Repository): List<TargetWithPolicy> {
        val labels = mapOf("type" to Policy.POLICY_TYPE)
        val manifests = repo.findManifests(labels)

        return manifests.map { meta ->
            val (policy, _) = repo.getManifest(meta.id, serializer<Policy>())
            val policyWithLabels = policy.copy(labels = meta.labels)
            TargetWithPolicy(
                id = meta.id.value,
                target = policyWithLabels.target(),
                policy = policyWithLabels,
            )
        }
    }

    /**
     * Computes the effective policy for the given source by resolving the
     * hierarchy: source > user > host > global > built-in defaults.
     *
     * Non-null fields from more-specific policies override less-specific ones.
     *
     * @param repo Repository to query
     * @param sourceInfo The source to resolve the effective policy for
     * @return The merged effective policy
     */
    suspend fun getEffectivePolicy(repo: Repository, sourceInfo: SourceInfo): Policy {
        val applicablePolicies = mutableListOf<Policy>()

        // 1. Source-level (most specific)
        if (sourceInfo.path.isNotEmpty()) {
            getPolicy(repo, sourceInfo)?.let { applicablePolicies.add(it) }
        }

        // 2. User-level
        if (sourceInfo.userName.isNotEmpty()) {
            val userSource = SourceInfo(
                host = sourceInfo.host,
                userName = sourceInfo.userName,
                path = "",
            )
            getPolicy(repo, userSource)?.let { applicablePolicies.add(it) }
        }

        // 3. Host-level
        if (sourceInfo.host.isNotEmpty()) {
            val hostSource = SourceInfo(
                host = sourceInfo.host,
                userName = "",
                path = "",
            )
            getPolicy(repo, hostSource)?.let { applicablePolicies.add(it) }
        }

        // 4. Global
        val globalSource = SourceInfo(host = "", userName = "", path = "")
        getPolicy(repo, globalSource)?.let { applicablePolicies.add(it) }

        // Merge all applicable policies (most-specific first) with built-in defaults
        val (merged, _) = mergePolicies(applicablePolicies, sourceInfo)
        return merged
    }

    /**
     * Selects the latest manifest entry from a list, using modification time
     * with ID as tiebreaker.
     */
    private fun pickLatestMetadata(entries: List<EntryMetadata>): EntryMetadata = entries.maxWith(compareBy({ it.modTime }, { it.id.value }))
}
