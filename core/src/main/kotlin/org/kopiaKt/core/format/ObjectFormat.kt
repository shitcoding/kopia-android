package org.kopiaKt.core.format

import kotlinx.serialization.Serializable

/**
 * Describes the format of objects in a repository.
 *
 * Currently only contains the splitter algorithm used to break
 * objects into pieces of content.
 */
@Serializable
data class ObjectFormat(
    /** Splitter algorithm used to break objects into chunks (e.g., "BUZHASH"). */
    val splitter: String = DEFAULT_SPLITTER,
) {
    companion object {
        /** Default splitter algorithm. */
        const val DEFAULT_SPLITTER = "DYNAMIC-4M-BUZHASH"
    }
}
