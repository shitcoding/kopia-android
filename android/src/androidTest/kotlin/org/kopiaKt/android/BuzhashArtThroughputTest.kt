package org.kopiaKt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.kopiaKt.core.splitter.Buzhash32Splitter
import org.kopiaKt.core.splitter.SplitterAlgorithms

/**
 * Buzhash splitter throughput **on ART**, which is the only runtime whose answer means anything here.
 *
 * `simpleperf` on a real 1.4 GB backup on a Nothing Phone (2) put this loop at ~24 % of all CPU, with
 * `kotlin.UInt.constructor-impl` alone at **8.8 %** — `UInt` is an inline class over `Int`, so on a
 * desktop JVM it costs nothing and a HotSpot A/B of the same change measures **zero** (534 vs
 * 519 MB/s, i.e. noise). ART's JIT evidently was not inlining it. That is the whole reason this test
 * is an instrumented one rather than a plain JVM benchmark: the desktop cannot see the thing being
 * fixed.
 *
 * Prints rather than asserts. A throughput threshold would be a flake on any shared machine, and the
 * value of this test is the A/B a human runs across a change, not a gate.
 */
@RunWith(AndroidJUnit4::class)
class BuzhashArtThroughputTest {

    @Test
    fun reportSplitThroughput() {
        val data = ByteArray(SIZE_MIB * MIB) { (it * 31 + (it shr 8) * 17).toByte() }

        repeat(2) { splitAll(data) } // let the JIT compile the loop before it is timed

        var chunks = 0
        val start = System.nanoTime()
        repeat(PASSES) { chunks += splitAll(data) }
        val seconds = (System.nanoTime() - start) / 1e9

        val megabytesPerSecond = (SIZE_MIB.toDouble() * PASSES) / seconds
        println(
            "BUZHASH_ART: %d MiB x %d passes in %.2f s -> %.0f MB/s (%d chunks)"
                .format(SIZE_MIB, PASSES, seconds, megabytesPerSecond, chunks),
        )
    }

    private fun splitAll(data: ByteArray): Int {
        val splitter = Buzhash32Splitter(SplitterAlgorithms.SIZE_1M)
        var chunks = 0
        var offset = 0
        while (offset < data.size) {
            val next = splitter.nextSplitPoint(data.copyOfRange(offset, data.size))
            if (next < 0) break
            chunks++
            offset += next
        }
        return chunks
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val SIZE_MIB = 16
        const val PASSES = 4
    }
}
