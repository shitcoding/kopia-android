package org.kopiaKt.android.identity

import android.content.Context
import android.os.Build
import java.security.SecureRandom

/**
 * The `user@host` half of every source key this device writes — to `ClientOptions`, to the source's
 * policy, and to the snapshot manifests themselves.
 */
data class SourceIdentity(
    val userName: String,
    val host: String,
)

/**
 * Generates the device identity once and remembers it for the life of the install.
 *
 * The host is `android-<sanitized-model>-<short-random>`. It is deliberately **not** bare
 * `Build.MODEL`: Android's default paths are identical across devices, so two phones of the same
 * model would produce the same `user@host:path` and their snapshots would merge into one source with
 * interleaved retention. The random suffix makes that impossible; the model keeps the id readable in
 * `kopia snapshot list` on a desktop.
 *
 * Backed by SharedPreferences rather than DataStore on purpose: this is read synchronously from the
 * `@JavascriptInterface` bridge and from the backup session, and DataStore's suspend-only API would
 * put a `runBlocking` file read on the main thread. It is one small record written exactly once.
 *
 * Excluded from Android Auto Backup (see the app's `dataExtractionRules`/`fullBackupContent`): a
 * transplanted identity would let a replacement device — or a second device restored from the same
 * cloud backup — write into the original device's source. New device, new source; the old snapshots
 * stay browsable and restorable under the old identity.
 */
object SourceIdentityStore {

    /** Bounds the model segment so a pathological `Build.MODEL` can't dominate the key. */
    const val MAX_MODEL_LENGTH = 32

    /**
     * The user name half. Kept as `local` — Android has no OS user name, the host already identifies
     * the device, and this is what the add-source wizard has always written, so only the host half
     * needs migrating.
     */
    const val USER_NAME = "local"

    /**
     * Public because the app's Auto Backup rules exclude this file by name — see
     * `res/xml/backup_rules.xml`. Renaming it silently stops the exclusion matching.
     */
    const val PREFS_NAME = "kopia_source_identity"
    private const val KEY_HOST = "host"
    private const val HOST_PREFIX = "android-"
    private const val SUFFIX_BYTES = 3
    private const val FALLBACK_MODEL = "unknown"

    @Volatile
    private var cached: SourceIdentity? = null

    /** The device identity, generating and persisting it on first use. */
    fun get(context: Context): SourceIdentity {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val host = prefs.getString(KEY_HOST, null) ?: persistNewHost(prefs)
            return SourceIdentity(userName = USER_NAME, host = host).also { cached = it }
        }
    }

    /**
     * The identity this device used before [get] existed: the add-source wizard stored source
     * policies under it, and they have to be re-keyed or they become invisible.
     */
    fun legacyIdentity(): SourceIdentity = SourceIdentity(
        userName = USER_NAME,
        host = Build.MODEL ?: FALLBACK_MODEL,
    )

    /**
     * Reduces a model name to something safe to use as a key: lowercase, and only characters that
     * neither Go's `ParseSourceInfo` nor the UI's `parseSourceId` split on.
     */
    internal fun sanitizeModel(raw: String): String {
        val reduced = raw.lowercase()
            .map { if (it.isKeySafe()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(MAX_MODEL_LENGTH)
            .trim('-', '.', '_')

        return reduced.ifEmpty { FALLBACK_MODEL }
    }

    /**
     * commit(), not apply(): the identity must be durable BEFORE anything is keyed by it, and a
     * failure has to be loud. Handing back an identity that did not reach disk would key this
     * session's sources, policies and snapshots to a host the next process never generates again.
     */
    private fun persistNewHost(prefs: android.content.SharedPreferences): String {
        val host = generateHost()
        check(prefs.edit().putString(KEY_HOST, host).commit()) {
            "could not persist the device's source identity"
        }
        return host
    }

    private fun Char.isKeySafe(): Boolean = this in 'a'..'z' || this in '0'..'9' || this == '.' || this == '_'

    private fun generateHost(): String {
        val suffix = ByteArray(SUFFIX_BYTES).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        return "$HOST_PREFIX${sanitizeModel(Build.MODEL ?: FALLBACK_MODEL)}-$suffix"
    }

    /** Test seam: forget the cached value so a test can observe a fresh generation. */
    internal fun resetForTest() {
        cached = null
    }
}
