package com.odasi.fieldwright.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Merkle inclusion: proofs must round-trip for every leaf of every small tree size, and
 * every byte of the proof or root must matter. Domain separation is checked explicitly
 * so a later refactor cannot quietly turn leaves into nodes.
 */
class MerkleLogTest {

    private val hasher = Sha256

    private fun leaves(count: Int): List<String> =
        (0 until count).map { index -> hasher.sha256Hex("packet-head-$index") }

    @Test
    fun `a leaf hash is domain separated from a bare hash`() {
        val data = "packet-head-0"
        val leaf = MerkleLog.leafHash(data)

        assertNotEquals(hasher.sha256Hex(data), leaf)
        assertEquals(
            hasher.sha256Hex(byteArrayOf(MerkleLog.LEAF_PREFIX) + data.toByteArray(Charsets.UTF_8)),
            leaf,
        )
        assertEquals(64, leaf.length)
    }

    @Test
    fun `an interior node hash is domain separated from a bare concatenation`() {
        val left = MerkleLog.leafHash("a")
        val right = MerkleLog.leafHash("b")
        val node = MerkleLog.nodeHash(left, right)

        assertNotEquals(hasher.sha256Hex(left + right), node)
        val expected = Sha256.sha256Bytes(
            byteArrayOf(MerkleLog.NODE_PREFIX) +
                com.odasi.fieldwright.model.MerkleHash.hexToBytes(left) +
                com.odasi.fieldwright.model.MerkleHash.hexToBytes(right),
        )
        assertEquals(Sha256.toLowerHex(expected), node)
    }

    @Test
    fun `the empty log has the published RFC 6962 empty root`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            MerkleLog.root(emptyList()),
        )
    }

    @Test
    fun `a one leaf tree root is its leaf hash`() {
        val leaf = "packet-head-0"

        assertEquals(MerkleLog.leafHash(leaf), MerkleLog.root(listOf(leaf)))
    }

    @Test
    fun `a two leaf tree root is the node of both leaves`() {
        val values = leaves(2)

        assertEquals(
            MerkleLog.nodeHash(MerkleLog.leafHash(values[0]), MerkleLog.leafHash(values[1])),
            MerkleLog.root(values),
        )
    }

    @Test
    fun `the union tree splits at the largest power of two below the size`() {
        val three = leaves(3)
        assertEquals(
            MerkleLog.nodeHash(
                MerkleLog.nodeHash(MerkleLog.leafHash(three[0]), MerkleLog.leafHash(three[1])),
                MerkleLog.leafHash(three[2]),
            ),
            MerkleLog.root(three),
        )

        val five = leaves(5)
        val firstFour = MerkleLog.nodeHash(
            MerkleLog.nodeHash(MerkleLog.leafHash(five[0]), MerkleLog.leafHash(five[1])),
            MerkleLog.nodeHash(MerkleLog.leafHash(five[2]), MerkleLog.leafHash(five[3])),
        )
        assertEquals(MerkleLog.nodeHash(firstFour, MerkleLog.leafHash(five[4])), MerkleLog.root(five))
    }

    @Test
    fun `root changes when leaves are reordered or edited`() {
        val base = leaves(4)
        val swapped = listOf(base[1], base[0], base[2], base[3])
        val edited = listOf(base[0], base[1], base[2], hasher.sha256Hex("tampered"))

        assertNotEquals(MerkleLog.root(base), MerkleLog.root(swapped))
        assertNotEquals(MerkleLog.root(base), MerkleLog.root(edited))
    }

    @Test
    fun `every inclusion proof round-trips in trees of one to nine leaves`() {
        for (size in 1..9) {
            val values = leaves(size)
            val root = MerkleLog.root(values)
            for (index in values.indices) {
                val proof = MerkleLog.proof(values, index)
                val inclusion = MerkleLog.inclusion(values, index)

                assertEquals(size, inclusion.treeSize)
                assertEquals(index, inclusion.index)
                assertEquals(root, inclusion.root)
                assertEquals(MerkleLog.leafHash(values[index]), inclusion.leafHash)

                assertTrue("4-arg verify failed for size=$size index=$index", MerkleLog.verify(values[index], proof, index, root))
                assertTrue("exact verify failed for size=$size index=$index", MerkleLog.verify(values[index], proof, index, size, root))
                assertTrue("Inclusion.verify failed for size=$size index=$index", inclusion.verify())
            }
        }
    }

    @Test
    fun `proof length equals the leaf depth`() {
        for (size in 1..9) {
            val values = leaves(size)
            for (index in values.indices) {
                assertEquals(
                    MerkleLog.depth(index, size),
                    MerkleLog.proof(values, index).size,
                )
            }
        }
    }

    @Test
    fun `a proof does not verify for the wrong index`() {
        val values = leaves(6)
        val root = MerkleLog.root(values)
        val proof = MerkleLog.proof(values, 0)

        assertFalse(MerkleLog.verify(values[0], proof, 1, root))
        assertFalse(MerkleLog.verify(values[0], proof, 1, 6, root))
    }

    @Test
    fun `a proof does not verify for a different leaf`() {
        val values = leaves(6)
        val root = MerkleLog.root(values)
        val proof = MerkleLog.proof(values, 2)

        assertFalse(MerkleLog.verify(values[3], proof, 2, root))
        assertFalse(MerkleLog.verify(hasher.sha256Hex("other"), proof, 2, root))
    }

    @Test
    fun `a tampered sibling hash fails verification`() {
        val values = leaves(5)
        val root = MerkleLog.root(values)
        val proof = MerkleLog.proof(values, 1).toMutableList()
        proof[0] = "f".repeat(64)

        assertFalse(MerkleLog.verify(values[1], proof, 1, root))
        assertFalse(MerkleLog.verify(values[1], proof, 1, 5, root))
    }

    @Test
    fun `a wrong root fails verification`() {
        val values = leaves(5)
        val proof = MerkleLog.proof(values, 3)

        assertFalse(MerkleLog.verify(values[3], proof, 3, "f".repeat(64)))
        assertFalse(MerkleLog.verify(values[3], proof, 3, 5, "f".repeat(64)))
    }

    @Test
    fun `extra or missing proof hashes are rejected by the exact verifier`() {
        val values = leaves(5)
        val root = MerkleLog.root(values)
        val proof = MerkleLog.proof(values, 2)

        assertFalse(MerkleLog.verify(values[2], proof + proof[0], 2, 5, root))
        assertFalse(MerkleLog.verify(values[2], proof.dropLast(1), 2, 5, root))
    }

    @Test
    fun `proof generation rejects indexes outside the log`() {
        try {
            MerkleLog.proof(leaves(3), 3)
            fail("An out-of-range index must not produce a proof.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        try {
            MerkleLog.proof(emptyList(), 0)
            fail("An empty log has no leaves to prove.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `a stored proof from an older tree size keeps verifying against its own root`() {
        val fiveLeaves = leaves(5)
        val inclusionAtFive = MerkleLog.inclusion(fiveLeaves, 1)

        val grown = leaves(8)

        // The old proof is still valid against the old root at the old size…
        assertTrue(MerkleLog.verify(inclusionAtFive.leafData, inclusionAtFive.proof, 1, inclusionAtFive.treeSize, inclusionAtFive.root))
        // …but the root of the grown log is a different commitment.
        assertNotEquals(inclusionAtFive.root, MerkleLog.root(grown))
    }

    @Test
    fun `single-leaf packet seal leaves verify with an empty proof`() {
        val head = hasher.sha256Hex("sealed-packet-head")
        val leaf = MerkleLog.leafHash(head)

        assertTrue(MerkleLog.verify(head, emptyList(), 0, leaf))
        assertTrue(MerkleLog.verify(head, emptyList(), 0, 1, leaf))
    }
}
