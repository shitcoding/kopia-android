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
        return SafFilesystem.directory(ctx, Uri.parse(sourcePath))
    }

    val sourceFile = File(sourcePath)
    require(sourceFile.exists()) { "Source path does not exist: $sourcePath" }
    require(sourceFile.isDirectory) { "Source path is not a directory: $sourcePath" }
    return LocalFilesystem.directory(sourceFile.toPath())
}

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
