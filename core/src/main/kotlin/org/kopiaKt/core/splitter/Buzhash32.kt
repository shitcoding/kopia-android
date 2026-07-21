package org.kopiaKt.core.splitter

/**
 * Buzhash32 rolling hash implementation.
 *
 * This is a port of the Go rollinghash/buzhash32 package used by Kopia.
 * It implements a cyclic polynomial (Buzhash) rolling hash.
 *
 * The hash is computed over a sliding window of bytes. When rolling,
 * the contribution of the oldest byte is removed and the newest byte is added.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Rolling_hash#Cyclic_polynomial">Cyclic polynomial</a>
 */
class Buzhash32 private constructor(
    private val byteHash: UIntArray,
) {
    private var sum: UInt = 0u
    private var nRotate: Int = 0
    private var nRotateComplement: Int = 32

    // Window is treated as a circular buffer
    private var window: ByteArray = ByteArray(0)
    private var oldest: Int = 0

    /**
     * Resets the hash to its initial state.
     */
    fun reset() {
        window = ByteArray(0)
        oldest = 0
        sum = 0u
    }

    /**
     * Appends data to the rolling window and updates the digest.
     * This recomputes the hash over the entire new window.
     *
     * @param data The bytes to append
     * @return The number of bytes now in the window
     */
    fun write(data: ByteArray): Int {
        if (data.isEmpty()) {
            return 0
        }

        // Re-arrange the window so that the leftmost element is at index 0
        val n = window.size
        if (oldest != 0) {
            val tmp = window.copyOfRange(0, oldest)
            window.copyInto(window, 0, oldest, n)
            tmp.copyInto(window, n - oldest, 0, oldest)
            oldest = 0
        }

        // Append new data
        window = window + data

        // Recompute hash over entire window
        sum = 0u
        for (c in window) {
            sum = (sum shl 1) or (sum shr 31)
            sum = sum xor byteHash[c.toInt() and 0xFF]
        }

        nRotate = window.size % 32
        nRotateComplement = 32 - nRotate

        return window.size
    }

    /**
     * Returns the current hash value.
     */
    fun sum32(): UInt = sum

    /**
     * Updates the hash by rolling the window: the oldest byte leaves
     * and a new byte enters.
     *
     * You MUST initialize a window with write() before calling this method.
     *
     * @param c The new byte entering the window
     */
    fun roll(c: Byte) {
        // Extract the entering/leaving bytes and update the circular buffer
        val hn = byteHash[c.toInt() and 0xFF]
        val h0 = byteHash[window[oldest].toInt() and 0xFF]

        window[oldest] = c
        oldest++
        if (oldest >= window.size) {
            oldest = 0
        }

        // Rolling hash formula:
        // new_sum = rotate_left(sum, 1) XOR rotate_left(h0, nRotate) XOR hn
        // The h0 term removes the leaving byte's contribution
        // The hn term adds the entering byte's contribution
        sum = ((sum shl 1) or (sum shr 31)) xor
            ((h0 shl nRotate) or (h0 shr nRotateComplement)) xor
            hn
    }

    companion object {
        /**
         * Default byte hash table, generated from Go with seed=1.
         * This table is pre-generated to ensure exact compatibility with Go's math/rand.
         */
        @Suppress("ktlint:standard:max-line-length")
        private val DEFAULT_HASHES = uintArrayOf(
            0x07fcfd52u, 0x5f3f164fu, 0x6695721du, 0x7b4d7c03u, 0x49c6e2d1u, 0x0d1d68d8u, 0x7916001eu, 0x22c4d294u,
            0x392907a0u, 0x189deb99u, 0xf3875d04u, 0x95e94627u, 0xba517936u, 0x83c471d4u, 0x7cb3ad0bu, 0xa42655d9u,
            0x7c4e0b68u, 0xd4491d1bu, 0x25632186u, 0xa9d78d73u, 0x169c1121u, 0xbb158644u, 0xb68e6a3fu, 0x759805f5u,
            0x2cdf5b8au, 0x57d29245u, 0xc5d6d268u, 0x6b83d0e2u, 0x7674cb74u, 0xb068d9dbu, 0xbb9457d8u, 0xa15d523bu,
            0x794209ffu, 0x9da1d7ebu, 0x5a25e0cbu, 0xf840ec4bu, 0x36d4ff9bu, 0xf4a5ee3bu, 0x5ad145f4u, 0xf6740304u,
            0x3f71f8cbu, 0x91018d7cu, 0xfccae224u, 0xb186b51fu, 0x7d9e8fbcu, 0x93f93f33u, 0xf63a5b6fu, 0x19476c36u,
            0xbc897d06u, 0x169873f5u, 0x724c7af1u, 0x581eeb39u, 0x2257bb7du, 0x6f269a28u, 0xbe8e9981u, 0x7039374bu,
            0x25416fedu, 0xded7e411u, 0x6678e7aau, 0x244fcd36u, 0x56aa6b86u, 0xde4561adu, 0x3e99b0a8u, 0xbed80a3au,
            0xe58348b0u, 0xaf63e58du, 0x406aec9du, 0xc233f007u, 0xa5ead0bdu, 0x050143a6u, 0x738b6829u, 0x3699caf3u,
            0x967cd710u, 0x06f665a6u, 0x25fd3d7fu, 0x0fd6e479u, 0x26fbf29bu, 0x16de4c35u, 0x2bf3394bu, 0x4af864bau,
            0x1cb9e6c6u, 0x41439089u, 0x69a39144u, 0xc34f182du, 0x421657ffu, 0xf9fc6568u, 0x8b02c917u, 0x6c9c64b7u,
            0x83d17909u, 0xea3d4ca5u, 0xaf635d47u, 0x7fc78769u, 0xbe14186fu, 0x3539b1eau, 0xe9174548u, 0xc051e18au,
            0x07b73658u, 0x29ec300cu, 0xa250bf34u, 0xa7ed5d97u, 0xfe3dea79u, 0xb8b352f7u, 0xc9b344e9u, 0x8e5f0475u,
            0x1941e52au, 0x2694763du, 0x6b40eabcu, 0x8485d68bu, 0x336eaccau, 0x4399a363u, 0x67149b9cu, 0xae10a901u,
            0x5a5ffefbu, 0x563b26deu, 0x6f00f02bu, 0x9f06397du, 0x545836c4u, 0x31d6416bu, 0x12f4128du, 0x752f33ffu,
            0x4a305605u, 0x0c8dc214u, 0x72521a90u, 0x8eb3e4a1u, 0x83efc6c6u, 0x090ec04fu, 0xc1540864u, 0xec2c8aaau,
            0x70ab53bau, 0x38d3b494u, 0x878d4063u, 0xa317ae3fu, 0x3cb62f07u, 0xf360412cu, 0x4ffbf3a9u, 0x22d554b4u,
            0x3f190476u, 0x0796a710u, 0xf5c353cfu, 0x04f59bb7u, 0x2d230176u, 0xe2d6a9ceu, 0x3f1d7427u, 0xafd915bbu,
            0xb01aa47du, 0xcde2c269u, 0x7417bf38u, 0x1e094f9au, 0x55ece0eau, 0xcb94539bu, 0x13d346b5u, 0x54e0c0c1u,
            0x6db30e37u, 0xdc02b390u, 0xa6e222f5u, 0xe2f8c1feu, 0x2e166bdfu, 0x67588a74u, 0x21898f34u, 0x4c330f1du,
            0x19af53bau, 0x70658b94u, 0x133c9673u, 0x4efeeaddu, 0x0f212551u, 0x070f0914u, 0x6f241c57u, 0xf13e41b7u,
            0x3be70cb0u, 0xf4b6f47fu, 0x20f31127u, 0x6551cb89u, 0x289cbd2cu, 0x8946f23du, 0x40a79c3bu, 0x03c6d982u,
            0x0365790cu, 0x0cf52517u, 0x0bb131e8u, 0x3da5475cu, 0x139efccau, 0xbcd44eb4u, 0xa54af747u, 0xb73c32edu,
            0x9f6c47acu, 0xe8ba8f22u, 0x54043a66u, 0x3b0a7f20u, 0xb4926431u, 0xe17c02d5u, 0x8f0d2558u, 0x014fbff2u,
            0x7f803594u, 0x79b76fbeu, 0x33fe2656u, 0x792c8ee8u, 0x6b41292du, 0xfdff9c32u, 0x82093298u, 0x59483870u,
            0xb2d5a113u, 0x2dd96e5au, 0x5b8e56a9u, 0xebd9dda9u, 0x04f9ce92u, 0xcb440950u, 0xa67e52b1u, 0x49f6d261u,
            0x92417fc3u, 0x1c3b6bd9u, 0xb027b7e0u, 0x1f765a41u, 0x44c9ab40u, 0x45d99121u, 0xfd7a84afu, 0x99b75788u,
            0xe3abff4au, 0xa88aa67fu, 0x736e41ccu, 0x9cbcbe5eu, 0x7b3ccebcu, 0xe1b7fa93u, 0x315ae6afu, 0x9cced2e2u,
            0x19ea0f2fu, 0xa9770722u, 0x84a6bfdcu, 0x88f03f07u, 0xa44a03a4u, 0x27a6b885u, 0xb580c8bau, 0x018153dau,
            0xb648e604u, 0x51180278u, 0x893a310fu, 0x728f5f4cu, 0x37f5198bu, 0x3cc0ea9bu, 0xe39d02dbu, 0x13883142u,
            0xca599392u, 0xc12d154eu, 0xc176163du, 0x5c92e2b8u, 0xf9f95edeu, 0xb802bdfcu, 0x8a928585u, 0xdca6e10bu,
            0x887953e8u, 0x48b9e962u, 0xe533e9a3u, 0x5e0ce6e5u, 0x1dba77aeu, 0xc8214b8au, 0x53b428d7u, 0x4cf20a65u,
        )

        /**
         * Creates a new Buzhash32 with the default hash table (seed=1).
         */
        fun new(): Buzhash32 = Buzhash32(DEFAULT_HASHES)

        /**
         * Creates a new Buzhash32 with a custom hash table.
         */
        fun newFromUIntArray(b: UIntArray): Buzhash32 {
            require(b.size == 256) { "Hash table must have exactly 256 entries" }
            return Buzhash32(b)
        }
    }
}
