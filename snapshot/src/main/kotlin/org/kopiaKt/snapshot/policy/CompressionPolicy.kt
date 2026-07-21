package org.kopiaKt.snapshot.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo
import java.io.File

/**
 * Compression policy specifying compression settings.
 *
 * Go type: policy.CompressionPolicy
 */
@Serializable
data class CompressionPolicy(
    val compressorName: String = "",

    @SerialName("onlyCompress")
    val onlyCompress: List<String> = emptyList(),

    @SerialName("noParentOnlyCompress")
    val noParentOnlyCompress: Boolean = false,

    @SerialName("neverCompress")
    val neverCompress: List<String> = emptyList(),

    @SerialName("noParentNeverCompress")
    val noParentNeverCompress: Boolean = false,

    val minSize: Long = 0,
    val maxSize: Long = 0,
) {
    /**
     * Returns the compression name to be used for compressing a given file.
     *
     * @param fileName The name of the file
     * @param fileSize The size of the file in bytes
     * @return The compressor name to use, or empty string if no compression should be applied
     */
    fun compressorForFile(fileName: String, fileSize: Long): String {
        val ext = File(fileName).extension.let { if (it.isNotEmpty()) ".$it" else "" }

        if (compressorName == "none") {
            return ""
        }

        if (minSize > 0 && fileSize < minSize) {
            return ""
        }

        if (maxSize > 0 && fileSize > maxSize) {
            return ""
        }

        val sortedOnlyCompress = onlyCompress.sorted()
        val sortedNeverCompress = neverCompress.sorted()

        // If onlyCompress is specified, only compress files with those extensions
        if (sortedOnlyCompress.isNotEmpty()) {
            return if (isInSortedList(ext, sortedOnlyCompress)) {
                compressorName
            } else {
                ""
            }
        }

        // Check if extension is in neverCompress list
        if (isInSortedList(ext, sortedNeverCompress)) {
            return ""
        }

        return compressorName
    }

    /**
     * Merges this policy with source policy.
     */
    fun merge(src: CompressionPolicy, def: CompressionPolicyDefinition, si: SourceInfo): Pair<CompressionPolicy, CompressionPolicyDefinition> {
        val newDef = def.copy()

        // Merge only compress list
        val mergedOnlyCompress = if (noParentOnlyCompress) {
            onlyCompress
        } else {
            val merged = (onlyCompress + src.onlyCompress).distinct().sorted()
            if (src.onlyCompress.isNotEmpty()) {
                newDef.onlyCompress = si
            }
            merged
        }

        // Merge never compress list
        val mergedNeverCompress = if (noParentNeverCompress) {
            neverCompress
        } else {
            val merged = (neverCompress + src.neverCompress).distinct().sorted()
            if (src.neverCompress.isNotEmpty()) {
                newDef.neverCompress = si
            }
            merged
        }

        return CompressionPolicy(
            compressorName = mergeString(compressorName, src.compressorName) {
                newDef.compressorName = si
            },
            onlyCompress = mergedOnlyCompress,
            noParentOnlyCompress = noParentOnlyCompress || src.noParentOnlyCompress,
            neverCompress = mergedNeverCompress,
            noParentNeverCompress = noParentNeverCompress || src.noParentNeverCompress,
            minSize = mergeLong(minSize, src.minSize) {
                newDef.minSize = si
            },
            maxSize = mergeLong(maxSize, src.maxSize) {
                newDef.maxSize = si
            },
        ) to newDef
    }

    companion object {
        /**
         * Default compression policy.
         */
        val Default = CompressionPolicy(
            compressorName = "none",
        )
    }
}

/**
 * Metadata compression policy specifying compression for metadata.
 *
 * Go type: policy.MetadataCompressionPolicy
 */
@Serializable
data class MetadataCompressionPolicy(
    val compressorName: String = "",
) {
    /**
     * Returns the metadata compressor name.
     */
    fun metadataCompressor(): String = if (compressorName == "none") "" else compressorName

    /**
     * Merges this policy with source policy.
     */
    fun merge(src: MetadataCompressionPolicy, def: MetadataCompressionPolicyDefinition, si: SourceInfo): Pair<MetadataCompressionPolicy, MetadataCompressionPolicyDefinition> {
        val newDef = def.copy()
        return MetadataCompressionPolicy(
            compressorName = mergeString(compressorName, src.compressorName) {
                newDef.compressorName = si
            },
        ) to newDef
    }

    companion object {
        /**
         * Default metadata compression policy.
         */
        val Default = MetadataCompressionPolicy(
            compressorName = "zstd-fastest",
        )
    }
}

/**
 * Specifies which policy definition provided the value of a particular compression field.
 *
 * Go type: policy.CompressionPolicyDefinition
 */
@Serializable
data class CompressionPolicyDefinition(
    var compressorName: SourceInfo? = null,
    var onlyCompress: SourceInfo? = null,
    var neverCompress: SourceInfo? = null,
    var minSize: SourceInfo? = null,
    var maxSize: SourceInfo? = null,
)

/**
 * Specifies which policy definition provided the value of a metadata compression field.
 *
 * Go type: policy.MetadataCompressionPolicyDefinition
 */
@Serializable
data class MetadataCompressionPolicyDefinition(
    var compressorName: SourceInfo? = null,
)

private fun isInSortedList(s: String, sortedList: List<String>): Boolean {
    val idx = sortedList.binarySearch(s)
    return idx >= 0
}

private inline fun mergeString(target: String, src: String, onMerge: () -> Unit): String = if (target.isEmpty() && src.isNotEmpty()) {
    onMerge()
    src
} else {
    target
}

private inline fun mergeLong(target: Long, src: Long, onMerge: () -> Unit): Long = if (target == 0L && src != 0L) {
    onMerge()
    src
} else {
    target
}
