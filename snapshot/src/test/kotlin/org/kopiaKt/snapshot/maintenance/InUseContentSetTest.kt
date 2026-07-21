package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ContentId

class InUseContentSetTest {

    @Test
    fun `MemoryInUseContentSet add and contains`() {
        val set = MemoryInUseContentSet()
        val contentId = ContentId.parse("abcdef1234567890abcdef1234567890")

        assertThat(set.contains(contentId)).isFalse()

        val added = set.add(contentId)
        assertThat(added).isTrue()
        assertThat(set.contains(contentId)).isTrue()
    }

    @Test
    fun `MemoryInUseContentSet add returns false for duplicate`() {
        val set = MemoryInUseContentSet()
        val contentId = ContentId.parse("abcdef1234567890abcdef1234567890")

        assertThat(set.add(contentId)).isTrue()
        assertThat(set.add(contentId)).isFalse()
    }

    @Test
    fun `MemoryInUseContentSet size tracks elements`() {
        val set = MemoryInUseContentSet()

        assertThat(set.size()).isEqualTo(0)

        set.add(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"))
        assertThat(set.size()).isEqualTo(1)

        set.add(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"))
        assertThat(set.size()).isEqualTo(2)

        // Adding duplicate shouldn't increase size
        set.add(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"))
        assertThat(set.size()).isEqualTo(2)
    }

    @Test
    fun `MemoryInUseContentSet clear removes all elements`() {
        val set = MemoryInUseContentSet()

        set.add(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"))
        set.add(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"))
        assertThat(set.size()).isEqualTo(2)

        set.clear()

        assertThat(set.size()).isEqualTo(0)
        assertThat(set.contains(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"))).isFalse()
    }

    @Test
    fun `MemoryInUseContentSet close clears`() {
        val set = MemoryInUseContentSet()
        set.add(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"))

        set.close()

        assertThat(set.size()).isEqualTo(0)
    }

    @Test
    fun `BloomFilterInUseContentSet add and contains`() {
        val set = BloomFilterInUseContentSet(expectedSize = 1000)
        val contentId = ContentId.parse("abcdef1234567890abcdef1234567890")

        assertThat(set.contains(contentId)).isFalse()

        set.add(contentId)
        assertThat(set.contains(contentId)).isTrue()
    }

    @Test
    fun `BloomFilterInUseContentSet has no false negatives`() {
        val set = BloomFilterInUseContentSet(expectedSize = 1000, falsePositiveRate = 0.01)

        // Add many elements
        val ids = (1..500).map { i ->
            val hex = String.format("%032x", i)
            ContentId.parse(hex)
        }

        for (id in ids) {
            set.add(id)
        }

        // All added elements should be found
        for (id in ids) {
            assertThat(set.contains(id)).isTrue()
        }
    }

    @Test
    fun `BloomFilterInUseContentSet false positive rate is bounded`() {
        val targetFPR = 0.05 // 5%
        val set = BloomFilterInUseContentSet(expectedSize = 1000, falsePositiveRate = targetFPR)

        // Add 1000 elements
        val addedIds = (1..1000).map { i ->
            val hex = String.format("%032x", i)
            ContentId.parse(hex)
        }
        for (id in addedIds) {
            set.add(id)
        }

        // Check for false positives with different elements
        val testIds = (10001..11000).map { i ->
            val hex = String.format("%032x", i)
            ContentId.parse(hex)
        }

        var falsePositives = 0
        for (id in testIds) {
            if (set.contains(id)) {
                falsePositives++
            }
        }

        val actualFPR = falsePositives.toDouble() / testIds.size

        // Allow some margin (2x target rate)
        assertThat(actualFPR).isLessThan(targetFPR * 2)
    }

    @Test
    fun `BloomFilterInUseContentSet clear works`() {
        val set = BloomFilterInUseContentSet(expectedSize = 100)
        val contentId = ContentId.parse("abcdef1234567890abcdef1234567890")

        set.add(contentId)
        assertThat(set.contains(contentId)).isTrue()

        set.clear()

        // After clear, element should not be found
        // (with very high probability - bloom filter is deterministic)
        assertThat(set.contains(contentId)).isFalse()
    }

    @Test
    fun `BloomFilterInUseContentSet size tracks adds`() {
        val set = BloomFilterInUseContentSet(expectedSize = 100)

        assertThat(set.size()).isEqualTo(0)

        set.add(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"))
        assertThat(set.size()).isEqualTo(1)

        set.add(ContentId.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"))
        assertThat(set.size()).isEqualTo(2)
    }

    @Test
    fun `InUseContentSetFactory creates MemoryInUseContentSet for small sizes`() {
        val set = InUseContentSetFactory.create(expectedSize = 1000)
        assertThat(set).isInstanceOf(MemoryInUseContentSet::class.java)
    }

    @Test
    fun `InUseContentSetFactory creates BloomFilterInUseContentSet for large sizes`() {
        val set = InUseContentSetFactory.create(expectedSize = 20_000_000)
        assertThat(set).isInstanceOf(BloomFilterInUseContentSet::class.java)
    }

    @Test
    fun `InUseContentSetFactory creates MemoryInUseContentSet for zero size`() {
        val set = InUseContentSetFactory.create(expectedSize = 0)
        assertThat(set).isInstanceOf(MemoryInUseContentSet::class.java)
    }

    @Test
    fun `handles content IDs with prefix`() {
        val set = MemoryInUseContentSet()

        val prefixedId = ContentId.parse("kabcdef1234567890abcdef12345678")
        val regularId = ContentId.parse("abcdef1234567890abcdef1234567890")

        set.add(prefixedId)
        set.add(regularId)

        assertThat(set.contains(prefixedId)).isTrue()
        assertThat(set.contains(regularId)).isTrue()
        assertThat(set.size()).isEqualTo(2)
    }

    @Test
    fun `concurrent access is thread-safe for MemoryInUseContentSet`() {
        val set = MemoryInUseContentSet()
        val threads = mutableListOf<Thread>()

        // Spawn multiple threads that add and check elements
        for (t in 0 until 10) {
            threads.add(
                Thread {
                    for (i in 0 until 100) {
                        val id = ContentId.parse(String.format("%032x", t * 1000 + i))
                        set.add(id)
                        assertThat(set.contains(id)).isTrue()
                    }
                },
            )
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(set.size()).isEqualTo(1000)
    }
}
