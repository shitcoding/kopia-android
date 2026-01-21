package org.kopiaKt.snapshot.policy

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.kopiaKt.snapshot.model.SourceInfo
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Time of day represented as hour and minute using 24-hour format.
 *
 * Go type: policy.TimeOfDay
 */
@Serializable
data class TimeOfDay(
    val hour: Int,
    @SerialName("min")
    val minute: Int = 0
) : Comparable<TimeOfDay> {
    init {
        require(hour in 0..23) { "Hour must be between 0 and 23, got $hour" }
        require(minute in 0..59) { "Minute must be between 0 and 59, got $minute" }
    }

    override fun toString(): String = "$hour:${minute.toString().padStart(2, '0')}"

    override fun compareTo(other: TimeOfDay): Int {
        val hourCompare = hour.compareTo(other.hour)
        return if (hourCompare != 0) hourCompare else minute.compareTo(other.minute)
    }

    companion object {
        /**
         * Parses a time of day string in "HH:MM" format.
         */
        fun parse(s: String): TimeOfDay {
            val parts = s.split(":")
            require(parts.size == 2) { "Invalid time of day format, must be HH:MM" }
            val hour = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid hour in time of day")
            val minute = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid minute in time of day")
            return TimeOfDay(hour, minute)
        }
    }
}

/**
 * Sorts and deduplicates a list of times of day.
 */
fun sortAndDedupeTimesOfDay(times: List<TimeOfDay>): List<TimeOfDay> {
    return times.sortedWith(compareBy({ it.hour }, { it.minute })).distinct()
}

/**
 * Scheduling policy describing when to schedule snapshots.
 *
 * Go type: policy.SchedulingPolicy
 */
@Serializable
data class SchedulingPolicy(
    val intervalSeconds: Long = 0,

    @SerialName("timeOfDay")
    val timesOfDay: List<TimeOfDay> = emptyList(),

    @SerialName("noParentTimeOfDay")
    val noParentTimesOfDay: Boolean = false,

    val manual: Boolean = false,

    val cron: List<String> = emptyList(),

    val runMissed: Boolean? = null
) {
    /**
     * Returns the snapshot interval as a Duration.
     */
    fun interval(): Duration = Duration.ofSeconds(intervalSeconds)

    /**
     * Sets the snapshot interval.
     */
    fun withInterval(d: Duration): SchedulingPolicy = copy(intervalSeconds = d.seconds)

    /**
     * Computes the next snapshot time given previous snapshot time and current time.
     *
     * @param previousSnapshotTime The time of the last snapshot
     * @param now The current time
     * @return Pair of (next snapshot time, whether a time was computed)
     */
    fun nextSnapshotTime(previousSnapshotTime: LocalDateTime, now: LocalDateTime): Pair<LocalDateTime?, Boolean> {
        if (manual) {
            return null to false
        }

        var nextSnapshotTime: LocalDateTime? = null
        var ok = false

        // Compute next snapshot time based on interval
        if (intervalSeconds != 0L) {
            val intervalDuration = Duration.ofSeconds(intervalSeconds)
            var nt = previousSnapshotTime.plus(intervalDuration)
            // Truncate to interval boundary
            val epochSecond = nt.atZone(ZoneId.systemDefault()).toEpochSecond()
            val truncatedEpoch = (epochSecond / intervalSeconds) * intervalSeconds
            nt = LocalDateTime.ofEpochSecond(truncatedEpoch, 0, ZoneId.systemDefault().rules.getOffset(nt))

            nextSnapshotTime = nt
            ok = true

            if (nextSnapshotTime.isBefore(now)) {
                nextSnapshotTime = now
            }
        }

        // Check time of day snapshots
        val (todSnapshot, todOk) = getNextTimeOfDaySnapshot(now)
        if (todOk && (nextSnapshotTime == null || todSnapshot!!.isBefore(nextSnapshotTime))) {
            nextSnapshotTime = todSnapshot
            ok = true
        }

        // Check cron snapshots (simplified - just check for next time of day equivalent)
        // Full cron parsing would require a cron library

        // Check if we missed a snapshot and should run now
        if (ok && checkMissedSnapshot(now, previousSnapshotTime, nextSnapshotTime!!)) {
            nextSnapshotTime = now
        }

        return nextSnapshotTime to ok
    }

    private fun getNextTimeOfDaySnapshot(now: LocalDateTime): Pair<LocalDateTime?, Boolean> {
        if (timesOfDay.isEmpty()) {
            return null to false
        }

        var nextSnapshotTime: LocalDateTime? = null
        val nowLocal = now

        for (tod in timesOfDay) {
            var localSnapshotTime = LocalDateTime.of(
                nowLocal.toLocalDate(),
                LocalTime.of(tod.hour, tod.minute)
            )

            if (nowLocal.isAfter(localSnapshotTime)) {
                localSnapshotTime = localSnapshotTime.plusDays(1)
            }

            if (nextSnapshotTime == null || localSnapshotTime.isBefore(nextSnapshotTime)) {
                nextSnapshotTime = localSnapshotTime
            }
        }

        return nextSnapshotTime to true
    }

    private fun checkMissedSnapshot(now: LocalDateTime, previousSnapshotTime: LocalDateTime, nextSnapshotTime: LocalDateTime): Boolean {
        if (runMissed != true) {
            return false
        }

        val halfHour = Duration.ofMinutes(30)
        val momentAfterSnapshot = previousSnapshotTime.plusSeconds(1)

        val (todSnapshot, todOk) = getNextTimeOfDaySnapshot(momentAfterSnapshot)
        if (!todOk) {
            return false
        }

        var nextSnapshot = nextSnapshotTime
        if (todOk && todSnapshot != null && todSnapshot.isBefore(nextSnapshot)) {
            nextSnapshot = todSnapshot
        }

        return nextSnapshot.isBefore(now) && nextSnapshotTime.isAfter(now.plus(halfHour))
    }

    /**
     * Merges this policy with source policy.
     */
    fun merge(src: SchedulingPolicy, def: SchedulingPolicyDefinition, si: SourceInfo): Pair<SchedulingPolicy, SchedulingPolicyDefinition> {
        val newDef = def.copy()

        // Merge times of day
        val mergedTimesOfDay = if (noParentTimesOfDay || src.timesOfDay.isEmpty()) {
            timesOfDay
        } else {
            newDef.timesOfDay = si
            sortAndDedupeTimesOfDay(src.timesOfDay + timesOfDay)
        }

        // Merge cron (replace if empty)
        val mergedCron = if (cron.isEmpty() && src.cron.isNotEmpty()) {
            newDef.cron = si
            src.cron
        } else {
            cron
        }

        return SchedulingPolicy(
            intervalSeconds = mergeLong(intervalSeconds, src.intervalSeconds) {
                newDef.intervalSeconds = si
            },
            timesOfDay = mergedTimesOfDay,
            noParentTimesOfDay = noParentTimesOfDay || src.noParentTimesOfDay,
            manual = mergeBool(manual, src.manual) {
                newDef.manual = si
            },
            cron = mergedCron,
            runMissed = mergeOptionalBool(runMissed, src.runMissed) {
                newDef.runMissed = si
            }
        ) to newDef
    }

    companion object {
        /**
         * Default scheduling policy.
         */
        val Default = SchedulingPolicy(
            runMissed = true
        )
    }
}

/**
 * Specifies which policy definition provided the value of a particular scheduling field.
 *
 * Go type: policy.SchedulingPolicyDefinition
 */
@Serializable
data class SchedulingPolicyDefinition(
    var intervalSeconds: SourceInfo? = null,
    var timesOfDay: SourceInfo? = null,
    var cron: SourceInfo? = null,
    var manual: SourceInfo? = null,
    var runMissed: SourceInfo? = null
)

/**
 * Validates that a scheduling policy is valid.
 * Returns an error message if invalid, null if valid.
 */
fun validateSchedulingPolicy(p: SchedulingPolicy): String? {
    // Manual cannot be combined with other scheduling policies
    if (p.manual) {
        val hasOtherScheduling = p.intervalSeconds != 0L ||
            p.timesOfDay.isNotEmpty() ||
            p.cron.isNotEmpty() ||
            p.runMissed != null
        if (hasOtherScheduling) {
            return "manual cannot be combined with other scheduling policies"
        }
    }

    // Validate cron expressions (basic validation)
    for (expr in p.cron) {
        val stripped = stripCronComment(expr)
        if (stripped.isNotEmpty()) {
            // Basic validation: should have 5 or 6 space-separated fields
            val fields = stripped.split(Regex("\\s+"))
            if (fields.size !in 5..6) {
                return "invalid cron expression \"$expr\""
            }
        }
    }

    return null
}

/**
 * Strips comments from a cron expression.
 */
fun stripCronComment(s: String): String {
    return s.split("#", limit = 2)[0].trim()
}

// Helper merge functions
private inline fun mergeLong(target: Long, src: Long, onMerge: () -> Unit): Long {
    return if (target == 0L && src != 0L) {
        onMerge()
        src
    } else {
        target
    }
}

private inline fun mergeBool(target: Boolean, src: Boolean, onMerge: () -> Unit): Boolean {
    return if (!target && src) {
        onMerge()
        src
    } else {
        target
    }
}

private inline fun mergeOptionalBool(target: Boolean?, src: Boolean?, onMerge: () -> Unit): Boolean? {
    return if (target == null && src != null) {
        onMerge()
        src
    } else {
        target
    }
}
