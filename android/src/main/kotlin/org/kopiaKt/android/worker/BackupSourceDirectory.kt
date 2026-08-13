package org.kopiaKt.android.worker

import android.content.Context
import android.net.Uri
import org.kopiaKt.android.identity.SourceIdentityStore
import org.kopiaKt.android.storage.SafFilesystem
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.LocalFilesystem
import java.io.File
import org.kopiaKt.snapshot.model.SourceInfo as SnapshotSourceInfo

/**
 * Opens a configured source path as a directory to walk.
 *
 * A source is either a SAF tree the user picked (a `content://` URI, readable only through the
 * ContentResolver and only while the persisted grant holds) or a plain path under shared storage.
 * Shared between the backup itself and the estimate that precedes it, so an estimate can never walk
 * a different tree than the backup it is estimating.
 */
fun openBackupSource(context: Context?, sourcePath: String): Directory {
    if (sourcePath.startsWith("content://")) {
        val ctx = context
            ?: error("Context is required for SAF URI backup. Pass context to BackupSession constructor.")
        return openSafSource(ctx, sourcePath)
    }

    val sourceFile = File(sourcePath)
    val problem = when {
        !sourceFile.exists() ->
            "Could not open this folder: $sourcePath. Check it still exists and that KopiaKt can " +
                "read it."
        !sourceFile.isDirectory -> "This is not a folder: $sourcePath"
        else -> null
    }
    if (problem != null) {
        throw SourceUnavailableException(problem)
    }
    return LocalFilesystem.directory(sourceFile.toPath())
}

/**
 * Opens a SAF tree, translating the two ways a picked folder stops being readable.
 *
 * Both are [SourceUnavailableException] because both need the user, not a retry: an invalid or
 * non-directory tree URI means the folder is gone or was replaced, and a SecurityException means the
 * persisted grant was withdrawn.
 *
 * The two cannot actually be told apart here, which is why the message hedges. `DocumentFile`
 * swallows whatever the provider throws and reports `isDirectory == false`, so a cloud provider that
 * is briefly unreachable — being updated, or crashed — arrives looking exactly like a folder that is
 * gone, and lands in the IllegalArgumentException branch rather than the SecurityException one.
 * Ending the run is still the better answer for all of them: the alternative is three attempts over
 * an exponential backoff during which `ExistingWorkPolicy.KEEP` swallows every "Back Up Now" the
 * user taps, and a terminal failure leaves them free to tap again immediately.
 */
private fun openSafSource(context: Context, sourcePath: String): Directory = try {
    SafFilesystem.directory(context, Uri.parse(sourcePath))
} catch (e: IllegalArgumentException) {
    throw SourceUnavailableException(
        "Could not open this folder. It may have been moved or deleted, or access to it withdrawn " +
            "— add it again to keep backing it up.",
        e,
    )
} catch (e: SecurityException) {
    throw SourceUnavailableException(
        "Access to this folder was withdrawn. Add it again to keep backing it up.",
        e,
    )
}

/**
 * The source cannot be opened, and running the backup again will not change that: the folder is
 * gone, is not a folder any more, or the SAF grant that made it readable has been withdrawn.
 *
 * A distinct type because it decides something: [org.kopiaKt.android.worker.BackupWorker] ends a run
 * that meets this instead of retrying it. The generic failure path would retry three times over an
 * exponential backoff, re-discovering a folder that is still not there, while the user's awaited
 * task spins and `ExistingWorkPolicy.KEEP` swallows every further "Back Up Now" they tap (task-59).
 * What has to change is outside the backup: they restore the folder, or add it again.
 *
 * The [message] is shown to the user and persisted on the source (task-39), so it is written for
 * them rather than for a log.
 */
class SourceUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The identity a source's snapshots and policy live under.
 *
 * `sourceId` already carries `user@host:path` and is the authority; the persisted device identity is
 * only the fallback for a bare id, and the path always comes from [sourcePath] so the two cannot
 * disagree about what was backed up.
 *
 * Shared with the estimate for the same reason [openBackupSource] is: the two must agree. They can
 * genuinely drift -- `kopia_backup_sources.xml` travels through an Android cloud restore while the
 * device identity is deliberately excluded from it -- and a restored source would then keep its old
 * host in the id while a freshly-derived identity resolved a policy that does not exist. The backup
 * would apply the user's exclusions and the estimate would not, silently overstating.
 */
fun backupSourceIdentity(context: Context?, sourceId: String, sourcePath: String): SnapshotSourceInfo {
    val parsed = SnapshotSourceInfo.parse(sourceId)
    val fallback = context?.let { SourceIdentityStore.get(it) }
    return SnapshotSourceInfo(
        host = parsed?.host?.takeIf { it.isNotEmpty() } ?: fallback?.host ?: "unknown",
        userName = parsed?.userName?.takeIf { it.isNotEmpty() } ?: fallback?.userName ?: "local",
        path = sourcePath,
    )
}
