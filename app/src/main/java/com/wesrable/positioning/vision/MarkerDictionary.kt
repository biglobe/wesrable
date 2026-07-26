package com.wesrable.positioning.vision

/**
 * The set of printable square markers this app recognises, and the code that
 * turns sixteen sampled bits back into a marker id.
 *
 * A marker is a 6x6 grid of cells: a one-cell black border all the way round,
 * and a 4x4 payload inside it. The border is what makes the marker findable at
 * all — it produces a solid dark quadrilateral that survives thresholding under
 * almost any lighting — and the 16 payload cells carry the identity.
 *
 * Sixteen bits could name 65,536 markers, and that would be a mistake. A camera
 * looking at a cabinet across a room resolves each cell to a handful of pixels,
 * so bits get read wrong; if every bit pattern were a valid id, a misread would
 * silently return a *different cabinet* rather than nothing. So the dictionary
 * keeps only codes that stay at least [MIN_HAMMING] bits apart from each other
 * **and from every 90-degree rotation of each other**. That buys two things:
 * up to [MAX_CORRECTABLE] wrong bits can be fixed outright, and the marker's
 * orientation falls out of which rotation matched, since no rotation of one
 * marker can be mistaken for any rotation of another.
 *
 * The dictionary is generated rather than tabulated, so the printed markers and
 * the decoder can never disagree about it.
 */
object MarkerDictionary {

    /** Payload cells per side. */
    const val GRID = 4

    /** Cells per side including the black border. */
    const val CELLS = GRID + 2

    /** Payload bits per marker. */
    const val BITS = GRID * GRID

    /**
     * Minimum bit difference between any two codes, counting rotations. Six
     * allows two bits to be corrected and a third to be detected; pushing it
     * higher shrinks the dictionary faster than it helps, since a home needs
     * dozens of markers rather than thousands.
     */
    const val MIN_HAMMING = 6

    /** Wrong bits that can be repaired: floor((MIN_HAMMING - 1) / 2). */
    const val MAX_CORRECTABLE = (MIN_HAMMING - 1) / 2

    /**
     * Every valid code, in order. A marker's id is its index here, so ids stay
     * small and human-quotable ("marker 7 is the winter-clothes cabinet")
     * rather than being 16-bit numbers nobody can read off a printout.
     */
    val codes: List<Int> by lazy { generate() }

    val size: Int get() = codes.size

    /** A successful decode: which marker, turned which way, and how marginal. */
    data class Match(
        val id: Int,
        /** 90-degree steps to turn the observed grid to upright. */
        val rotation: Int,
        /** Bits that had to be corrected; 0 is a clean read. */
        val bitErrors: Int,
    )

    /**
     * Identifies sixteen observed bits, or returns null if they are not close
     * enough to any marker to be worth believing.
     *
     * Returning null is the common and correct outcome: most dark quadrilaterals
     * in a room are door frames, picture frames and window mullions, and this is
     * the step that throws them away.
     */
    fun match(observedBits: Int): Match? {
        var bestId = -1
        var bestRotation = 0
        var bestDistance = Int.MAX_VALUE
        var runnerUp = Int.MAX_VALUE

        codes.forEachIndexed { id, code ->
            var rotated = code
            for (rotation in 0 until 4) {
                val distance = Integer.bitCount(rotated xor observedBits)
                if (distance < bestDistance) {
                    runnerUp = bestDistance
                    bestDistance = distance
                    bestId = id
                    bestRotation = rotation
                } else if (distance < runnerUp) {
                    runnerUp = distance
                }
                rotated = rotate(rotated)
            }
        }

        if (bestId < 0 || bestDistance > MAX_CORRECTABLE) return null
        // With MIN_HAMMING enforced across the dictionary this cannot fail, but
        // it is cheap and it is the invariant the whole scheme rests on.
        if (runnerUp <= bestDistance) return null
        return Match(bestId, bestRotation, bestDistance)
    }

    /** The bit grid of one marker, as [CELLS] x [CELLS] booleans; true is black. */
    fun pattern(id: Int): Array<BooleanArray> {
        val code = codes[id]
        return Array(CELLS) { row ->
            BooleanArray(CELLS) { col ->
                val isBorder = row == 0 || col == 0 || row == CELLS - 1 || col == CELLS - 1
                if (isBorder) {
                    true
                } else {
                    // A payload bit of 1 is white, 0 is black — arbitrary, but
                    // fixed here so renderer and decoder share one convention.
                    !bitAt(code, row - 1, col - 1)
                }
            }
        }
    }

    /** Rotates a payload code 90 degrees clockwise. */
    fun rotate(code: Int): Int {
        var rotated = 0
        for (row in 0 until GRID) {
            for (col in 0 until GRID) {
                if (!bitAt(code, row, col)) continue
                rotated = rotated or (1 shl indexOf(col, GRID - 1 - row))
            }
        }
        return rotated
    }

    fun bitAt(code: Int, row: Int, col: Int): Boolean =
        (code shr indexOf(row, col)) and 1 == 1

    private fun indexOf(row: Int, col: Int): Int = row * GRID + col

    /**
     * Greedy selection over all 65,536 candidates: keep a code when it is at
     * least [MIN_HAMMING] bits from every rotation of every code already kept,
     * and when its own four rotations are that far from each other too. The
     * second condition is what makes orientation recoverable — a rotationally
     * symmetric marker would decode identically whichever way up it was stuck
     * to the cabinet, and there would be no way to tell.
     *
     * Greedy is not optimal, but it is deterministic and it runs in a few
     * milliseconds, so the alternative — shipping a hand-checked table that
     * could drift out of step with the renderer — buys nothing.
     */
    private fun generate(): List<Int> {
        val accepted = mutableListOf<Int>()
        val acceptedRotations = mutableListOf<IntArray>()

        for (candidate in 0 until (1 shl BITS)) {
            val rotations = rotationsOf(candidate)

            var selfDistinct = true
            for (a in 0 until 4) {
                for (b in a + 1 until 4) {
                    if (Integer.bitCount(rotations[a] xor rotations[b]) < MIN_HAMMING) {
                        selfDistinct = false
                    }
                }
            }
            if (!selfDistinct) continue

            val farFromAll = acceptedRotations.all { existing ->
                existing.all { code -> Integer.bitCount(code xor candidate) >= MIN_HAMMING }
            }
            if (!farFromAll) continue

            accepted.add(candidate)
            acceptedRotations.add(rotations)
        }

        return accepted
    }

    private fun rotationsOf(code: Int): IntArray {
        val out = IntArray(4)
        var current = code
        for (index in 0 until 4) {
            out[index] = current
            current = rotate(current)
        }
        return out
    }
}
