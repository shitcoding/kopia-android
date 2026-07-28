package org.kopiaKt.snapshot

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes the long-running operations that write to the repository: backups, retention and
 * maintenance.
 *
 * Not for pack-level isolation — `newDirectWriter` already gives every write session its own
 * `ContentManager`, so one session's `flush()` cannot commit another's half-written packs, and the
 * repository format is designed for many clients writing at once anyway. What this buys is a phone
 * doing one heavy thing at a time: two simultaneous multi-gigabyte uploads on a handset compete for
 * memory, battery and a metered connection, and a source's retention pass should see a settled
 * manifest list rather than one its own sibling backup is still adding to.
 *
 * Short UI writes (saving a policy, deleting a snapshot) deliberately stay outside it — they are
 * already isolated in their own sessions, and blocking *Save* behind a running backup would trade a
 * problem the format does not have for one the user would feel.
 *
 * One real exception, which this lock does not solve and could not: manifest compaction records a
 * deletion by *absence*, so two sessions compacting from views taken before each other's writes can
 * resurrect a deleted manifest. Go has the same race and retries on it, and it happens between the
 * phone and a desktop just as readily as inside one process — so it wants fixing where compaction
 * lives, not here.
 *
 * **Not reentrant.** A section already holding the lock (a backup applying its own retention, say)
 * must not take it again. Entry points take it; the work they call does not.
 */
object RepositoryWriteLock {

    private val mutex = Mutex()

    /** True while some operation holds the lock — for diagnostics, never for control flow. */
    val isHeld: Boolean get() = mutex.isLocked

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
