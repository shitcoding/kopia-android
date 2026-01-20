package org.kopiaKt.core.content

/**
 * Represents an object ID, which identifies a repository object.
 *
 * Repository objects can be stored:
 * 1. In a single content block (most common for small objects)
 * 2. In a series of content blocks with indirect blocks pointing at them
 *    (used for larger files). Object IDs using indirect blocks start with "I"
 *
 * Format (matching Go implementation):
 * - Zero or more 'I' characters for indirection level (one 'I' per level)
 * - Optional 'Z' for compression (mutually exclusive with indirection)
 * - Optional 'D' for legacy direct (no-op, parsed but not emitted)
 * - Followed by ContentId string
 *
 * Examples:
 * - "abcd" -> direct, no indirection, no compression
 * - "Iabcd" -> 1 level of indirection
 * - "IIabcd" -> 2 levels of indirection
 * - "Zabcd" -> compressed direct content
 */
class ObjectId private constructor(
    /**
     * The underlying content ID.
     */
    val contentId: ContentId,
    /**
     * The indirection level (0 = direct, 1+ = indirect).
     */
    val indirection: Int,
    /**
     * Whether the content is compressed.
     */
    val isCompressed: Boolean
) {
    init {
        require(indirection >= 0) { "indirection must be non-negative" }
        require(indirection <= MAX_INDIRECTION) { "indirection too large: $indirection" }
        require(!(indirection > 0 && isCompressed)) {
            "compression and indirection are mutually exclusive"
        }
    }

    /**
     * Returns the string representation matching Go's format.
     */
    override fun toString(): String {
        if (this === Empty || (contentId == ContentId.Empty && indirection == 0 && !isCompressed)) {
            return ""
        }

        val sb = StringBuilder()

        // Add indirection prefix
        repeat(indirection) {
            sb.append('I')
        }

        // Add compression prefix
        if (isCompressed) {
            sb.append('Z')
        }

        // Add content ID
        sb.append(contentId.toString())

        return sb.toString()
    }

    /**
     * Returns the index object ID by decrementing the indirection level.
     *
     * @return A pair of (ObjectId, success). If indirection is 0, returns (this, false).
     */
    fun indexObjectId(): Pair<ObjectId, Boolean> {
        if (indirection > 0) {
            return ObjectId(contentId, indirection - 1, isCompressed) to true
        }
        return this to false
    }

    /**
     * Returns the underlying content ID if this is a direct object.
     *
     * @return A triple of (ContentId, isCompressed, success).
     *         If indirection > 0, returns (Empty, false, false).
     */
    fun getContentId(): Triple<ContentId, Boolean, Boolean> {
        if (indirection > 0) {
            return Triple(ContentId.Empty, false, false)
        }
        return Triple(contentId, isCompressed, true)
    }

    /**
     * Returns a new ObjectId with indirection level incremented by 1.
     */
    fun incrementIndirection(): ObjectId {
        require(indirection < MAX_INDIRECTION) { "cannot increment indirection beyond $MAX_INDIRECTION" }
        return ObjectId(contentId, indirection + 1, false) // compression is cleared
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ObjectId) return false
        return contentId == other.contentId &&
            indirection == other.indirection &&
            isCompressed == other.isCompressed
    }

    override fun hashCode(): Int {
        var result = contentId.hashCode()
        result = 31 * result + indirection
        result = 31 * result + isCompressed.hashCode()
        return result
    }

    companion object {
        /**
         * Maximum indirection level (fits in a byte).
         */
        const val MAX_INDIRECTION = 255

        /**
         * Empty object ID equivalent to empty string.
         */
        val Empty: ObjectId = ObjectId(ContentId.Empty, 0, false)

        /**
         * Parses an object ID string.
         *
         * @param s The string to parse
         * @return The parsed ObjectId
         * @throws IllegalArgumentException if the string is invalid
         */
        fun parse(s: String): ObjectId {
            if (s.isEmpty()) {
                return Empty
            }

            var remaining = s
            var indirection = 0
            var isCompressed = false

            // Count leading 'I' characters for indirection
            while (remaining.isNotEmpty() && remaining[0] == 'I') {
                indirection++
                remaining = remaining.substring(1)

                if (indirection > MAX_INDIRECTION) {
                    throw IllegalArgumentException("too many indirection levels: $indirection")
                }
            }

            // Check for 'Z' compression flag
            if (remaining.isNotEmpty() && remaining[0] == 'Z') {
                isCompressed = true
                remaining = remaining.substring(1)
            }

            // Check for legacy 'D' prefix (no-op)
            if (remaining.isNotEmpty() && remaining[0] == 'D') {
                remaining = remaining.substring(1)
            }

            // Validate mutual exclusivity
            if (indirection > 0 && isCompressed) {
                throw IllegalArgumentException(
                    "malformed object ID - compression and indirection are mutually exclusive"
                )
            }

            // Parse the content ID
            val contentId = ContentId.parse(remaining)

            return ObjectId(contentId, indirection, isCompressed)
        }

        /**
         * Creates a direct object ID from a content ID.
         *
         * @param contentId The content ID
         * @return A direct ObjectId (no indirection, no compression)
         */
        fun direct(contentId: ContentId): ObjectId {
            return ObjectId(contentId, 0, false)
        }

        /**
         * Creates a compressed object ID from a content ID.
         *
         * @param contentId The content ID
         * @return A compressed ObjectId (no indirection)
         */
        fun compressed(contentId: ContentId): ObjectId {
            return ObjectId(contentId, 0, true)
        }

        /**
         * Creates an indirect object ID from a content ID.
         *
         * @param contentId The content ID
         * @param indirection The indirection level (must be > 0)
         * @return An indirect ObjectId (no compression)
         */
        fun indirect(contentId: ContentId, indirection: Int): ObjectId {
            require(indirection > 0) { "indirection must be positive for indirect object" }
            return ObjectId(contentId, indirection, false)
        }
    }
}
