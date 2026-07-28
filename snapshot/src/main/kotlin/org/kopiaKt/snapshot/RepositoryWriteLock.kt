package org.kopiaKt.snapshot

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes the operations that write to the repository: backups, retention and maintenance.
 *
 * They share one `DirectRepositoryImpl` and one `ContentManager`, whose internal mutexes make
 * concurrent use memory-safe but not *correct* — one operation's `flush()` commits whatever another
 * has half-written, packs and manifest state alike. Go avoids the question by isolating every upload
 * in its own `WriteSession`; until Kotlin does the same, they take turns.
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
