package org.kopiaKt.core.splitter

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Rolling a byte in must equal recomputing the window from scratch.
 *
 * That is the defining contract of a rolling hash, and here it is also the only way to reach one
 * line of [Buzhash32.roll]. The `h0` term rotates the departing byte's hash by `nRotate`, which is
 * `window.size % 32` — and every splitter in this codebase uses a **64-byte** window
 * (`SPLITTER_SLIDING_WINDOW_SIZE`), so `nRotate` is 0, the rotation collapses to `h0 or h0`, and the
 * term is a no-op no matter what it does. Flipping its `ushr` to `shr` therefore passes all 17
 * splitter tests: a mutation that cannot fire, because the fixture cannot reach the state it guards.
 *
 * These windows are deliberately **not** multiples of 32, which makes the rotation live and the
 * mutation fatal. Raised in review of the `UInt` → `Int` conversion (task-66): before this, that line
 * was guarded by nothing but its resemblance to the Go original.
 *
 * No external oracle is needed. `write()` recomputes the hash over the whole window and `roll()`
 * updates it incrementally; they are two paths to one number, so they check each other.
 */
@DisplayName("Buzhash roll/write agreement")
class Buzhash32RollInvariantTest {

    @Test
    fun `rolling agrees with recomputing, for windows that make the rotation live`() {
        // 32 and 64 would leave nRotate == 0. These do not: 40 -> 8, 33 -> 1, 63 -> 31.
        for (windowSize in intArrayOf(33, 40, 63)) {
            val initial = ByteArray(windowSize) { ((it * 37) + 11).toByte() }
            val incoming = ByteArray(48) { ((it * 53) + 7).toByte() }

            val rolled = Buzhash32.new()
            rolled.write(initial)

            // The window as it would be after each roll: drop the oldest byte, append the new one.
            var expectedWindow = initial
            for (c in incoming) {
                rolled.roll(c)
                expectedWindow = expectedWindow.copyOfRange(1, expectedWindow.size) + c

                val recomputed = Buzhash32.new()
                recomputed.write(expectedWindow)

                assertThat(rolled.sum32()).isEqualTo(recomputed.sum32())
            }
        }
    }
}
