package org.kopiaKt.app.data.repository

import android.util.Log
import org.kopiaKt.android.identity.SourceIdentity
import org.kopiaKt.core.repository.Repository
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.PolicyManager

private const val TAG = "SourceIdentityMigration"

/**
 * Works out which source policies are stranded under the device's previous identity.
 *
 * The add-source wizard stored source policies under `local@<Build.MODEL>:path`; once the device
 * has its own persisted identity, nothing resolves that key any more and the user's ignore rules and
 * compression silently stop applying. Only *source* policies move — a host- or global-level policy
 * (empty path) belongs to whoever set it, and a policy already present at the destination is left
 * alone, because losing one the user set under the current identity is worse than leaving a stale
 * one behind.
 *
 * @return the moves to perform, as `from to to`.
 */
internal fun planPolicyMigration(
    targets: List<SourceInfo>,
    legacy: SourceIdentity,
    current: SourceIdentity,
): List<Pair<SourceInfo, SourceInfo>> {
    if (legacy == current) {
        return emptyList()
    }
    val existing = targets.toSet()
    return targets
        .filter { it.userName == legacy.userName && it.host == legacy.host && it.path.isNotEmpty() }
        .map { it to SourceInfo(host = current.host, userName = current.userName, path = it.path) }
        .filterNot { (_, destination) -> destination in existing }
}

/**
 * Copies stranded source policies onto the device's current identity.
 *
 * **Copy, not move.** The legacy key `local@<Build.MODEL>:path` is genuinely ambiguous — it is the
 * collision the per-install suffix exists to prevent — so two phones of the same model sharing a
 * repository both resolve it. Deleting it would let whichever phone connects first take the policy
 * away from the other, which would silently revert that phone's ignore rules and compression to
 * defaults with no way back. The cost of keeping it is one stale row in `kopia policy list`.
 *
 * Best-effort by design: a repository the user cannot write to (or a transient failure) must not
 * stop them connecting.
 */
internal suspend fun migrateLegacySourcePolicies(
    repository: Repository,
    legacy: SourceIdentity,
    current: SourceIdentity,
) {
    try {
        val targets = PolicyManager.listPolicies(repository).map { it.target }
        for ((from, to) in planPolicyMigration(targets, legacy, current)) {
            val policy = PolicyManager.getPolicy(repository, from) ?: continue
            PolicyManager.setPolicy(repository, to, policy)
            Log.i(TAG, "copied a source policy from $from to $to")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not migrate legacy source policies", e)
    }
}
