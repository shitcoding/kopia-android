package org.kopiaKt.app.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.identity.SourceIdentity
import org.kopiaKt.core.repository.Repository
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.policy.PolicyManager
import org.kopiaKt.snapshot.policy.TargetWithPolicy
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * The add-source wizard has always stored source policies under `local@<Build.MODEL>:path`. Once the
 * device gets its own persisted identity those policies are keyed by a host nothing resolves any
 * more, so the ignore rules and compression a user configured would silently stop applying. Nothing
 * can back up yet, so no *snapshots* exist under the old identity — but policies do, and this is the
 * last moment they can be moved for free.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class SourceIdentityMigrationTest {

    private val legacy = SourceIdentity(userName = "local", host = "Pixel 7")
    private val current = SourceIdentity(userName = "local", host = "android-pixel-7-a1b2c3")

    private fun target(userName: String, host: String, path: String) = SourceInfo(host, userName, path)

    @Test
    fun `a legacy source policy moves to the current identity`() {
        val plan = planPolicyMigration(
            listOf(target("local", "Pixel 7", "/sdcard/DCIM")),
            legacy,
            current,
        )

        assertThat(plan).containsExactly(
            target("local", "Pixel 7", "/sdcard/DCIM") to target("local", "android-pixel-7-a1b2c3", "/sdcard/DCIM"),
        )
    }

    @Test
    fun `policies belonging to other hosts are left alone`() {
        val plan = planPolicyMigration(
            listOf(
                target("alice", "laptop", "/home/alice/docs"),
                target("local", "Galaxy S21", "/sdcard/DCIM"),
            ),
            legacy,
            current,
        )

        assertThat(plan).isEmpty()
    }

    @Test
    fun `host and global policies are not source policies`() {
        val plan = planPolicyMigration(
            listOf(
                target("local", "Pixel 7", ""),
                target("", "", ""),
            ),
            legacy,
            current,
        )

        assertThat(plan).isEmpty()
    }

    @Test
    fun `an existing policy at the destination is not overwritten`() {
        val plan = planPolicyMigration(
            listOf(
                target("local", "Pixel 7", "/sdcard/DCIM"),
                target("local", "android-pixel-7-a1b2c3", "/sdcard/DCIM"),
            ),
            legacy,
            current,
        )

        // Losing a policy the user set under the current identity would be worse than leaving a
        // stale one behind.
        assertThat(plan).isEmpty()
    }

    @Test
    fun `nothing moves when the identity has not changed`() {
        val plan = planPolicyMigration(
            listOf(target("local", "Pixel 7", "/sdcard/DCIM")),
            legacy,
            legacy,
        )

        assertThat(plan).isEmpty()
    }

    @Test
    fun `the legacy policy is copied, never taken away`(): Unit = runBlocking {
        mockkObject(PolicyManager)
        try {
            val repository = mockk<Repository>(relaxed = true)
            val from = target("local", "Pixel 7", "/sdcard/DCIM")
            val to = target("local", "android-pixel-7-a1b2c3", "/sdcard/DCIM")
            val policy = Policy()
            coEvery { PolicyManager.listPolicies(repository) } returns
                listOf(TargetWithPolicy(id = "p1", target = from, policy = policy))
            coEvery { PolicyManager.getPolicy(repository, from) } returns policy
            coEvery { PolicyManager.setPolicy(any(), any(), any()) } returns Unit

            migrateLegacySourcePolicies(repository, legacy, current)

            coVerify(exactly = 1) { PolicyManager.setPolicy(repository, to, policy) }
            // Two phones of the same model resolve the same legacy key; deleting it would take the
            // policy away from whichever one connects second.
            coVerify(exactly = 0) { PolicyManager.deletePolicy(any(), any()) }
        } finally {
            unmockkObject(PolicyManager)
        }
    }

    @Test
    fun `a repository that cannot be read does not stop the user connecting`(): Unit = runBlocking {
        mockkObject(PolicyManager)
        try {
            val repository = mockk<Repository>(relaxed = true)
            coEvery { PolicyManager.listPolicies(repository) } throws IllegalStateException("read-only")

            // Must not throw.
            migrateLegacySourcePolicies(repository, legacy, current)
        } finally {
            unmockkObject(PolicyManager)
        }
    }
}
