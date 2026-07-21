package org.kopiaKt.snapshot.policy

import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Default values for retention policy.
 */
object RetentionDefaults {
    const val KEEP_LATEST = 10
    const val KEEP_HOURLY = 48
    const val KEEP_DAILY = 7
    const val KEEP_WEEKLY = 4
    const val KEEP_MONTHLY = 24
    const val KEEP_ANNUAL = 3
    const val IGNORE_IDENTICAL_SNAPSHOTS = false
}

/**
 * Retention policy describing snapshot retention.
 *
 * Go type: policy.RetentionPolicy
 */
@Serializable
data class RetentionPolicy(
    val keepLatest: Int? = null,
    val keepHourly: Int? = null,
    val keepDaily: Int? = null,
    val keepWeekly: Int? = null,
    val keepMonthly: Int? = null,
    val keepAnnual: Int? = null,
    val ignoreIdenticalSnapshots: Boolean? = null,
) {
    /**
     * Returns the effective number of "latest" snapshots to keep.
     * If all retention values are set to 0 then returns Int.MAX_VALUE.
     */
    fun effectiveKeepLatest(): Int? {
        val sum = (keepLatest ?: 0) +
            (keepHourly ?: 0) +
            (keepDaily ?: 0) +
            (keepWeekly ?: 0) +
            (keepMonthly ?: 0) +
            (keepAnnual ?: 0)

        return if (sum == 0) {
            Int.MAX_VALUE
        } else {
            keepLatest
        }
    }

    /**
     * Merges this policy with source policy, applying values from source
     * where this policy has null values.
     *
     * @param src The source policy to merge from
     * @param def The definition to track which policy provided which value
     * @param si The source info identifying where the source policy comes from
     * @return A new merged RetentionPolicy
     */
    fun merge(src: RetentionPolicy, def: RetentionPolicyDefinition, si: SourceInfo): Pair<RetentionPolicy, RetentionPolicyDefinition> {
        val newDef = def.copy()
        return RetentionPolicy(
            keepLatest = mergeInt(keepLatest, src.keepLatest) { newDef.keepLatest = si },
            keepHourly = mergeInt(keepHourly, src.keepHourly) { newDef.keepHourly = si },
            keepDaily = mergeInt(keepDaily, src.keepDaily) { newDef.keepDaily = si },
            keepWeekly = mergeInt(keepWeekly, src.keepWeekly) { newDef.keepWeekly = si },
            keepMonthly = mergeInt(keepMonthly, src.keepMonthly) { newDef.keepMonthly = si },
            keepAnnual = mergeInt(keepAnnual, src.keepAnnual) { newDef.keepAnnual = si },
            ignoreIdenticalSnapshots = mergeBool(ignoreIdenticalSnapshots, src.ignoreIdenticalSnapshots) {
                newDef.ignoreIdenticalSnapshots = si
            },
        ) to newDef
    }

    companion object {
        /**
         * Default retention policy.
         */
        val Default = RetentionPolicy(
            keepLatest = RetentionDefaults.KEEP_LATEST,
            keepHourly = RetentionDefaults.KEEP_HOURLY,
            keepDaily = RetentionDefaults.KEEP_DAILY,
            keepWeekly = RetentionDefaults.KEEP_WEEKLY,
            keepMonthly = RetentionDefaults.KEEP_MONTHLY,
            keepAnnual = RetentionDefaults.KEEP_ANNUAL,
            ignoreIdenticalSnapshots = RetentionDefaults.IGNORE_IDENTICAL_SNAPSHOTS,
        )
    }
}

/**
 * Specifies which policy definition provided the value of a particular retention field.
 *
 * Go type: policy.RetentionPolicyDefinition
 */
@Serializable
data class RetentionPolicyDefinition(
    var keepLatest: SourceInfo? = null,
    var keepHourly: SourceInfo? = null,
    var keepDaily: SourceInfo? = null,
    var keepWeekly: SourceInfo? = null,
    var keepMonthly: SourceInfo? = null,
    var keepAnnual: SourceInfo? = null,
    var ignoreIdenticalSnapshots: SourceInfo? = null,
)

/**
 * Sort retention tags in canonical order.
 *
 * Go function: policy.SortRetentionTags
 */
fun sortRetentionTags(tags: List<String>): List<String> {
    val prefixSortValue = mapOf(
        "latest" to 1,
        "hourly" to 2,
        "daily" to 3,
        "weekly" to 4,
        "monthly" to 5,
        "annual" to 6,
    )

    return tags.sortedWith { a, b ->
        val (p1, s1) = prefixSuffix(a)
        val (p2, s2) = prefixSuffix(b)

        val v1 = prefixSortValue[p1] ?: 100
        val v2 = prefixSortValue[p2] ?: 100

        when {
            v1 != v2 -> v1.compareTo(v2)
            p1 != p2 -> p1.compareTo(p2)
            else -> s1.compareTo(s2)
        }
    }
}

/**
 * Compacts retention reasons into a more readable format.
 * E.g., ["daily-1", "daily-2", "daily-3"] becomes ["daily-1..3"]
 *
 * Go function: policy.CompactRetentionReasons
 */
fun compactRetentionReasons(reasons: List<String>): List<String> {
    val reasonsByPrefix = mutableMapOf<String, MutableList<Int>>()
    val result = mutableListOf<String>()

    for (r in reasons) {
        val (prefix, suffix) = prefixSuffix(r)
        val n = suffix.toIntOrNull()
        if (n == null) {
            result.add(r)
        } else {
            reasonsByPrefix.getOrPut(prefix) { mutableListOf() }.add(n)
        }
    }

    for ((prefix, numbers) in reasonsByPrefix) {
        result.addAll(appendRLE(prefix, numbers.sorted()))
    }

    return sortRetentionTags(result)
}

/**
 * Compacts pins by removing duplicates and sorting.
 *
 * Go function: policy.CompactPins
 */
fun compactPins(pins: List<String>): List<String> = pins.toSet().sorted()

private fun prefixSuffix(s: String): Pair<String, String> {
    val p = s.lastIndexOf('-')
    return if (p < 0) {
        s to ""
    } else {
        s.substring(0, p) to s.substring(p + 1)
    }
}

private fun appendRLE(prefix: String, numbers: List<Int>): List<String> {
    if (numbers.isEmpty()) return emptyList()

    val result = mutableListOf<String>()
    var runStart = numbers[0]
    var runEnd = numbers[0]

    fun appendRun() {
        if (runStart == runEnd) {
            result.add("$prefix-$runStart")
        } else {
            result.add("$prefix-$runStart..$runEnd")
        }
    }

    for (num in numbers.drop(1)) {
        if (num == runEnd + 1) {
            runEnd = num
        } else {
            appendRun()
            runStart = num
            runEnd = num
        }
    }

    appendRun()
    return result
}

// Helper merge functions for policy merging
private inline fun mergeInt(target: Int?, src: Int?, onMerge: () -> Unit): Int? = if (target == null && src != null) {
    onMerge()
    src
} else {
    target
}

private inline fun mergeBool(target: Boolean?, src: Boolean?, onMerge: () -> Unit): Boolean? = if (target == null && src != null) {
    onMerge()
    src
} else {
    target
}
