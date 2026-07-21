// Ported from github.com/chmduquesne/rollinghash (rabinkarp64), BSD-2-Clause,
// adapted from restic; Copyright (c) 2014 Alexander Neumann, (c) 2017
// Christophe-Marie Duquesne. See THIRD_PARTY_NOTICES.md.
package org.kopiaKt.core.splitter

/**
 * Polynomial over GF(2) represented as a 64-bit integer.
 *
 * This is a port of the Go rabinkarp64.Pol type used for RabinKarp rolling hash.
 * Operations are performed in the finite field GF(2), where addition is XOR.
 */
@JvmInline
value class Pol(val value: ULong) {
    /**
     * Returns x + y (XOR in GF(2)).
     */
    fun add(y: Pol): Pol = Pol(value xor y.value)

    /**
     * Returns the degree of the polynomial.
     * The degree of 0 is -1.
     */
    fun deg(): Int {
        if (value == 0uL) return -1

        var x = value
        var r = 0
        if (x and 0xffffffff00000000uL > 0uL) {
            x = x shr 32
            r = r or 32
        }
        if (x and 0xffff0000uL > 0uL) {
            x = x shr 16
            r = r or 16
        }
        if (x and 0xff00uL > 0uL) {
            x = x shr 8
            r = r or 8
        }
        if (x and 0xf0uL > 0uL) {
            x = x shr 4
            r = r or 4
        }
        if (x and 0xcuL > 0uL) {
            x = x shr 2
            r = r or 2
        }
        if (x and 0x2uL > 0uL) {
            r = r or 1
        }
        return r
    }

    /**
     * Returns x / d = q and remainder r.
     */
    fun divMod(d: Pol): Pair<Pol, Pol> {
        if (value == 0uL) return Pol(0uL) to Pol(0uL)
        require(d.value != 0uL) { "division by zero" }

        val D = d.deg()
        var x = this
        var diff = x.deg() - D
        if (diff < 0) return Pol(0uL) to x

        var q = Pol(0uL)
        while (diff >= 0) {
            val m = Pol(d.value shl diff)
            q = Pol(q.value or (1uL shl diff))
            x = x.add(m)
            diff = x.deg() - D
        }
        return q to x
    }

    /**
     * Returns x mod d.
     */
    fun mod(d: Pol): Pol = divMod(d).second

    companion object {
        val ZERO = Pol(0uL)
    }
}

/**
 * Precomputed tables for RabinKarp64 rolling hash operations.
 */
private class RabinKarp64Tables(
    val out: Array<Pol>,
    val mod: Array<Pol>,
)

/**
 * RabinKarp64 rolling hash implementation.
 *
 * This is a port of the Go rollinghash/rabinkarp64 package used by Kopia.
 * It implements a polynomial rolling hash over GF(2).
 */
class RabinKarp64 private constructor(
    private val pol: Pol,
) {
    private var tables: RabinKarp64Tables? = null
    private val polShift: Int = pol.deg() - 8
    private var hashValue: Pol = Pol.ZERO

    // Window is treated as a circular buffer
    private var window: ByteArray = ByteArray(0)
    private var oldest: Int = 0

    init {
        updateTables()
    }

    private fun updateTables() {
        val windowSize = window.size.coerceAtLeast(1)
        tables = buildTables(pol, windowSize)
    }

    private fun buildTables(pol: Pol, windowSize: Int): RabinKarp64Tables {
        val outTable = Array(256) { Pol.ZERO }
        val modTable = Array(256) { Pol.ZERO }

        // Calculate table for sliding out bytes
        // This matches Go's implementation exactly:
        //   var h Pol
        //   h <<= 8
        //   h |= Pol(b)
        //   h = h.Mod(pol)
        //   for i := 0; i < windowsize-1; i++ {
        //       h <<= 8
        //       h |= Pol(0)
        //       h = h.Mod(pol)
        //   }
        for (b in 0 until 256) {
            var h = Pol(b.toULong()) // Start with just b, not b << 8
            h = h.mod(pol)
            for (i in 0 until windowSize - 1) {
                h = Pol(h.value shl 8) // h |= 0 is a no-op
                h = h.mod(pol)
            }
            outTable[b] = h
        }

        // Calculate table for reduction mod polynomial
        val k = pol.deg()
        for (b in 0 until 256) {
            val bv = b.toULong() shl k
            modTable[b] = Pol(bv).mod(pol).let { r ->
                Pol(r.value or (b.toULong() shl k))
            }
        }

        return RabinKarp64Tables(outTable, modTable)
    }

    /**
     * Resets the hash to its initial state.
     */
    fun reset() {
        tables = null
        hashValue = Pol.ZERO
        window = ByteArray(0)
        oldest = 0
        updateTables()
    }

    /**
     * Appends data to the rolling window and updates the digest.
     * This recomputes the hash over the entire new window.
     *
     * @param data The bytes to append
     * @return The number of bytes now in the window
     */
    fun write(data: ByteArray): Int {
        if (data.isEmpty()) return 0

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
        hashValue = Pol.ZERO
        for (b in window) {
            hashValue = Pol(hashValue.value shl 8)
            hashValue = Pol(hashValue.value or (b.toULong() and 0xFFuL))
            hashValue = hashValue.mod(pol)
        }

        updateTables()
        return window.size
    }

    /**
     * Returns the current hash value as a 64-bit unsigned integer.
     */
    fun sum64(): ULong = hashValue.value

    /**
     * Updates the hash by rolling the window: the oldest byte leaves
     * and a new byte enters.
     *
     * You MUST initialize a window with write() before calling this method.
     *
     * @param c The new byte entering the window
     */
    fun roll(c: Byte) {
        val t = tables!!
        val enter = c.toULong() and 0xFFuL
        val leave = window[oldest].toULong() and 0xFFuL

        window[oldest] = c
        oldest++
        if (oldest >= window.size) {
            oldest = 0
        }

        hashValue = hashValue.add(t.out[leave.toInt()])
        val index = ((hashValue.value shr polShift) and 0xFFuL).toInt()
        hashValue = Pol(hashValue.value shl 8)
        hashValue = Pol(hashValue.value or enter)
        hashValue = hashValue.add(t.mod[index])
    }

    companion object {
        /**
         * Default polynomial generated with seed=1, matching Go implementation.
         * This is an irreducible polynomial of degree 53.
         */
        val DEFAULT_POLYNOMIAL = Pol(0x2e3e3e4a305605uL)

        /**
         * Creates a new RabinKarp64 with the default polynomial (seed=1).
         */
        fun new(): RabinKarp64 = newFromPol(DEFAULT_POLYNOMIAL)

        /**
         * Creates a new RabinKarp64 with the given polynomial.
         *
         * @param p An irreducible polynomial over GF(2)
         */
        fun newFromPol(p: Pol): RabinKarp64 = RabinKarp64(p)
    }
}
