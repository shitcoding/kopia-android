package org.kopiaKt.core.splitter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.testvectors.TestVectorLoader

class SplitterTest {
    private val testVectors = TestVectorLoader.load()

    // Helper function to create a dynamic splitter for a given size
    private fun createBuzhash32Splitter(avgSize: Int): Splitter = Buzhash32Splitter(avgSize)
    private fun createRabinKarp64Splitter(avgSize: Int): Splitter = RabinKarp64Splitter(avgSize)

    // Size constants for test vectors
    private val SIZE_8K = 8 * 1024
    private val SIZE_128K = 128 * 1024

    @Test
    fun `FixedSplitter splits at exact boundaries`() {
        val splitter = FixedSplitter(100)
        val data = ByteArray(350) { it.toByte() }

        val boundaries = splitter.findAllBoundaries(data)

        assertEquals(listOf(100, 200, 300, 350), boundaries)
    }

    @Test
    fun `FixedSplitter handles exact multiple`() {
        val splitter = FixedSplitter(100)
        val data = ByteArray(300) { it.toByte() }

        val boundaries = splitter.findAllBoundaries(data)

        assertEquals(listOf(100, 200, 300), boundaries)
    }

    @Test
    fun `FixedSplitter handles data smaller than chunk size`() {
        val splitter = FixedSplitter(100)
        val data = ByteArray(50) { it.toByte() }

        val boundaries = splitter.findAllBoundaries(data)

        assertEquals(listOf(50), boundaries)
    }

    @Test
    fun `FixedSplitter handles empty data`() {
        val splitter = FixedSplitter(100)
        val data = ByteArray(0)

        val boundaries = splitter.findAllBoundaries(data)

        assertTrue(boundaries.isEmpty())
    }

    @Test
    fun `Buzhash32Splitter produces correct boundaries for test vector - random data 128K`() {
        val testCase = testVectors.splitter.buzhash32.find { it.name == "random_data_128k" }!!
        val splitter = createBuzhash32Splitter(SIZE_128K)
        val input = testCase.input

        val boundaries = splitter.findAllBoundaries(input)

        assertEquals(testCase.boundaries, boundaries, "Boundaries should match Go implementation")
    }

    @Test
    fun `Buzhash32Splitter produces correct boundaries for test vector - repeated text 8K`() {
        val testCase = testVectors.splitter.buzhash32.find { it.name == "repeated_text_8k" }!!
        val splitter = createBuzhash32Splitter(SIZE_8K)
        val input = testCase.input

        val boundaries = splitter.findAllBoundaries(input)

        assertEquals(testCase.boundaries, boundaries, "Boundaries should match Go implementation")
    }

    @Test
    fun `RabinKarp64Splitter produces correct boundaries for test vector - random data 128K`() {
        val testCase = testVectors.splitter.rabinkarp64.find { it.name == "random_data_128k" }!!
        val splitter = createRabinKarp64Splitter(SIZE_128K)
        val input = testCase.input

        val boundaries = splitter.findAllBoundaries(input)

        assertEquals(testCase.boundaries, boundaries, "Boundaries should match Go implementation")
    }

    @Test
    fun `RabinKarp64Splitter produces correct boundaries for test vector - repeated text 8K`() {
        val testCase = testVectors.splitter.rabinkarp64.find { it.name == "repeated_text_8k" }!!
        val splitter = createRabinKarp64Splitter(SIZE_8K)
        val input = testCase.input

        val boundaries = splitter.findAllBoundaries(input)

        assertEquals(testCase.boundaries, boundaries, "Boundaries should match Go implementation")
    }

    @Test
    fun `Buzhash32Splitter respects minSize boundary`() {
        // With 128K average size, minSize = 64K, maxSize = 256K
        val splitter = Buzhash32Splitter(SIZE_128K)

        // Create data smaller than minSize - should not split
        val smallData = ByteArray(SIZE_128K / 4) { it.toByte() } // 32K, less than minSize of 64K

        val boundaries = splitter.findAllBoundaries(smallData)

        // Should have only one boundary at the end (no early splits)
        assertEquals(1, boundaries.size)
        assertEquals(smallData.size, boundaries[0])
    }

    @Test
    fun `Buzhash32Splitter forces split at maxSize`() {
        // With 8K average size, minSize = 4K, maxSize = 16K
        val splitter = Buzhash32Splitter(SIZE_8K)

        // Create all-zeros data (very unlikely to trigger content-based split)
        val zeroData = ByteArray(SIZE_8K * 4) // 32K bytes

        val boundaries = splitter.findAllBoundaries(zeroData)

        // All boundaries should be at maxSize intervals
        boundaries.forEachIndexed { i, boundary ->
            if (i < boundaries.size - 1) {
                val chunkSize = boundary - (if (i > 0) boundaries[i - 1] else 0)
                assertTrue(
                    chunkSize <= SIZE_8K * 2,
                    "Chunk $i size $chunkSize should not exceed maxSize ${SIZE_8K * 2}",
                )
            }
        }
    }

    @Test
    fun `RabinKarp64Splitter respects minSize boundary`() {
        // With 128K average size, minSize = 64K, maxSize = 256K
        val splitter = RabinKarp64Splitter(SIZE_128K)

        // Create data smaller than minSize - should not split
        val smallData = ByteArray(SIZE_128K / 4) { it.toByte() } // 32K, less than minSize of 64K

        val boundaries = splitter.findAllBoundaries(smallData)

        // Should have only one boundary at the end (no early splits)
        assertEquals(1, boundaries.size)
        assertEquals(smallData.size, boundaries[0])
    }

    @Test
    fun `RabinKarp64Splitter forces split at maxSize`() {
        // With 8K average size, minSize = 4K, maxSize = 16K
        val splitter = RabinKarp64Splitter(SIZE_8K)

        // Create all-zeros data (very unlikely to trigger content-based split)
        val zeroData = ByteArray(SIZE_8K * 4) // 32K bytes

        val boundaries = splitter.findAllBoundaries(zeroData)

        // All boundaries should be at maxSize intervals
        boundaries.forEachIndexed { i, boundary ->
            if (i < boundaries.size - 1) {
                val chunkSize = boundary - (if (i > 0) boundaries[i - 1] else 0)
                assertTrue(
                    chunkSize <= SIZE_8K * 2,
                    "Chunk $i size $chunkSize should not exceed maxSize ${SIZE_8K * 2}",
                )
            }
        }
    }

    @Test
    fun `DefaultSplitterFactory creates correct splitter types`() {
        val buzhash = DefaultSplitterFactory.create(SplitterAlgorithms.DYNAMIC_4M_BUZHASH)
        assertTrue(buzhash is Buzhash32Splitter)

        val rabinkarp = DefaultSplitterFactory.create(SplitterAlgorithms.DYNAMIC_4M_RABINKARP)
        assertTrue(rabinkarp is RabinKarp64Splitter)

        val fixed = DefaultSplitterFactory.create(SplitterAlgorithms.FIXED_4M)
        assertTrue(fixed is FixedSplitter)
    }

    @Test
    fun `DefaultSplitterFactory throws for unknown algorithm`() {
        assertThrows<IllegalArgumentException> {
            DefaultSplitterFactory.create("UNKNOWN")
        }
    }

    @Test
    fun `DefaultSplitterFactory supports all documented algorithms`() {
        val expectedAlgorithms = listOf(
            "FIXED-128K", "FIXED-256K", "FIXED-512K", "FIXED-1M", "FIXED-2M", "FIXED-4M", "FIXED-8M",
            "DYNAMIC-128K-BUZHASH", "DYNAMIC-256K-BUZHASH", "DYNAMIC-512K-BUZHASH",
            "DYNAMIC-1M-BUZHASH", "DYNAMIC-2M-BUZHASH", "DYNAMIC-4M-BUZHASH", "DYNAMIC-8M-BUZHASH",
            "DYNAMIC-128K-RABINKARP", "DYNAMIC-256K-RABINKARP", "DYNAMIC-512K-RABINKARP",
            "DYNAMIC-1M-RABINKARP", "DYNAMIC-2M-RABINKARP", "DYNAMIC-4M-RABINKARP", "DYNAMIC-8M-RABINKARP",
            "FIXED", "DYNAMIC",
        )

        for (alg in expectedAlgorithms) {
            val factory = DefaultSplitterFactory.getFactory(alg)
            assertTrue(factory != null, "Factory for $alg should exist")
            val splitter = factory!!.create()
            assertTrue(splitter.maxSegmentSize() > 0, "Splitter for $alg should have positive max size")
        }
    }

    @Test
    fun `Splitter reset works correctly`() {
        val splitter = Buzhash32Splitter(SIZE_8K)
        val data = ByteArray(10000) { (it % 256).toByte() }

        // First run
        val boundaries1 = splitter.findAllBoundaries(data)

        // Second run without reset - should still work because findAllBoundaries resets internally
        val boundaries2 = splitter.findAllBoundaries(data)

        assertEquals(boundaries1, boundaries2, "Same data should produce same boundaries")
    }

    @Test
    fun `splitIntoChunks produces correct chunks`() {
        val splitter = FixedSplitter(100)
        val data = ByteArray(250) { it.toByte() }

        val chunks = splitter.splitIntoChunks(data).toList()

        assertEquals(3, chunks.size)
        assertEquals(100, chunks[0].size)
        assertEquals(100, chunks[1].size)
        assertEquals(50, chunks[2].size)

        // Verify content
        for (i in 0 until 100) {
            assertEquals(i.toByte(), chunks[0][i])
        }
        for (i in 0 until 100) {
            assertEquals((i + 100).toByte(), chunks[1][i])
        }
        for (i in 0 until 50) {
            assertEquals((i + 200).toByte(), chunks[2][i])
        }
    }
}
