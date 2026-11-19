package com.odasi.fieldwright.crypto

import com.odasi.fieldwright.model.MerkleHash

/**
 * Append-only Merkle log over packet (or record) head hashes — RFC 6962 style.
 *
 * Why a Merkle log at all: a chain proves that *within one packet* nothing was edited.
 * It cannot prove that the packet itself was not swapped, because any packet carries its
 * own chain. The log gives one short root that commits to every packet in order, so a
 * single anchor (published or held by the requester) protects the whole vault. Inclusion
 * proofs let a worker show "my packet is in the log" without shipping the log.
 *
 * RFC 6962 domain separation (shared with `:core:model` [MerkleHash] so that
 * `PacketAssembler.seal` can stamp a leaf while offline):
 * - leaf  = SHA-256(0x00 || leafData)
 * - node  = SHA-256(0x01 || left || right)
 *
 * The `0x00`/`0x01` prefix prevents a leaf from being reinterpreted as an interior node
 * (second-preimage attack), which is exactly the class of forgery an evidence log must
 * not allow.
 *
 * Trees are the RFC 6962 "union" trees: for n > 1 leaves, the split is at
 * k = the largest power of two strictly smaller than n. That split makes the tree
 * append-friendly: adding a leaf changes the root but never re-buckets older leaves, so
 * inclusion proofs at an old tree size stay valid forever.
 */
object MerkleLog {
    const val LEAF_PREFIX: Byte = MerkleHash.LEAF_PREFIX
    const val NODE_PREFIX: Byte = MerkleHash.NODE_PREFIX

    /** A stored inclusion proof, with everything needed to re-verify it later. */
    data class Inclusion(
        /** The raw leaf data that was inserted (a packet head hash, in practice). */
        val leafData: String,
        /** RFC 6962 leaf hash: `SHA-256(0x00 || leafData)`. */
        val leafHash: String,
        val index: Int,
        val treeSize: Int,
        val root: String,
        val proof: List<String>,
    ) {
        /** Re-verifies this inclusion proof against the root captured at [treeSize]. */
        fun verify(): Boolean = MerkleLog.verify(leafData, proof, index, treeSize, root)
    }

    /** RFC 6962 leaf hash of one leaf's data. */
    fun leafHash(packetHash: String): String = MerkleHash.leafHash(packetHash, Sha256)

    /** RFC 6962 interior node hash of two child hashes. */
    fun nodeHash(leftHash: String, rightHash: String): String =
        MerkleHash.nodeHash(leftHash, rightHash, Sha256)

    /**
     * Root of the tree over [leaves], in insertion order.
     *
     * The empty log has the RFC 6962 empty-tree root `SHA-256("")` — a defined value, so
     * "no packets yet" is never confused with "verification unavailable".
     */
    fun root(leaves: List<String>): String {
        if (leaves.isEmpty()) return Sha256.sha256Hex(ByteArray(0))
        return treeHash(leaves, 0, leaves.size)
    }

    /** Inclusion proof (audit path) for the leaf at [index], ordered from leaf upward. */
    fun proof(leaves: List<String>, index: Int): List<String> {
        require(leaves.isNotEmpty()) { "Cannot build an inclusion proof for an empty log." }
        require(index in leaves.indices) { "Leaf index $index is outside 0..${leaves.lastIndex}." }
        val path = ArrayList<String>(ceilLog2(leaves.size))
        collectProof(leaves, from = 0, size = leaves.size, index = index, out = path)
        return path
    }

    /** Convenience bundle for storing on a packet: leaf hash, index, tree size, root, proof. */
    fun inclusion(leaves: List<String>, index: Int): Inclusion {
        require(index in leaves.indices) { "Leaf index $index is outside the log." }
        return Inclusion(
            leafData = leaves[index],
            leafHash = leafHash(leaves[index]),
            index = index,
            treeSize = leaves.size,
            root = root(leaves),
            proof = proof(leaves, index),
        )
    }

    /**
     * Verify an inclusion proof **without knowing the tree size**.
     *
     * RFC 6962 proofs are bare sibling hashes; the direction of each combination depends
     * on the leaf count, so a size-less verifier recovers the leaf count from the proof
     * length. This overload scans the candidate sizes (bounded to logs up to 2^26 leaves)
     * and accepts if any of them reconstructs [root]. It is intended for device evidence
     * logs of ordinary size; when the caller knows the log size at sealing time, prefer
     * the explicit [verify] overload, which is exact and O(log n).
     *
     * @param leaf the raw leaf data (the packet head hash), not its leaf hash.
     */
    fun verify(leaf: String, proof: List<String>, index: Int, root: String): Boolean {
        if (index < 0 || index.toLong() >= MAX_IMPLICIT_TREE_SIZE) return false
        val levels = proof.size
        // A leaf at `index` whose path has `levels` nodes can live in a tree no larger
        // than 2*index + 2^levels: the worst case is a right-edge leaf of a
        // non-power-of-two tree, where one sibling commits to everything to its left.
        // (The naive 2^(levels+1)-1 bound misses, e.g., index 4 in a 5-leaf tree.)
        if (levels > MAX_IMPLICIT_PROOF_LEVELS) return false
        val maxCandidate = minOf(
            MAX_IMPLICIT_TREE_SIZE,
            2L * index + (1L shl levels),
        )
        var size = (index + 1).toLong()
        var steps = 0L
        while (size <= maxCandidate) {
            if (++steps > MAX_IMPLICIT_SEARCH_STEPS) return false
            if (depth(index, size.toInt()) == levels) {
                if (verify(leaf, proof, index, size.toInt(), root)) return true
            }
            size++
        }
        return false
    }

    // Practical limits for the size-less overload: a device evidence log larger than
    // 2^26 packets is beyond this sprint's scope and must use the exact overload.
    private const val MAX_IMPLICIT_PROOF_LEVELS = 27
    private const val MAX_IMPLICIT_TREE_SIZE = 1L shl 26
    private const val MAX_IMPLICIT_SEARCH_STEPS = 4_000_000L

    /**
     * Verify an inclusion proof at a known tree size: the exact, constant-cost check.
     * Use this whenever the log size at seal time is stored with the packet.
     */
    fun verify(leaf: String, proof: List<String>, index: Int, treeSize: Int, root: String): Boolean {
        if (index < 0 || treeSize <= 0 || index >= treeSize) return false
        if (proof.size != depth(index, treeSize)) return false
        val siblings = ArrayDeque(proof)
        val candidate = reconstruct(index, treeSize, leaf, siblings)
        return siblings.isEmpty() && candidate == root
    }

    private fun treeHash(leaves: List<String>, from: Int, size: Int): String {
        if (size == 1) return leafHash(leaves[from])
        val split = largestPowerOfTwoLessThan(size)
        val left = treeHash(leaves, from, split)
        val right = treeHash(leaves, from + split, size - split)
        return nodeHash(left, right)
    }

    private fun collectProof(
        leaves: List<String>,
        from: Int,
        size: Int,
        index: Int,
        out: MutableList<String>,
    ) {
        if (size <= 1) return
        val split = largestPowerOfTwoLessThan(size)
        if (index < split) {
            collectProof(leaves, from, split, index, out)
            out += treeHash(leaves, from + split, size - split)
        } else {
            collectProof(leaves, from + split, size - split, index - split, out)
            out += treeHash(leaves, from, split)
        }
    }

    private fun reconstruct(
        index: Int,
        size: Int,
        leaf: String,
        siblings: ArrayDeque<String>,
    ): String {
        if (size == 1) return leafHash(leaf)
        val split = largestPowerOfTwoLessThan(size)
        return if (index < split) {
            val left = reconstruct(index, split, leaf, siblings)
            val right = siblings.removeFirst()
            nodeHash(left, right)
        } else {
            val right = reconstruct(index - split, size - split, leaf, siblings)
            val left = siblings.removeFirst()
            nodeHash(left, right)
        }
    }

    /** Depth of the leaf at [index] in a union tree of [size] leaves. */
    internal fun depth(index: Int, size: Int): Int {
        var cursorIndex = index
        var cursorSize = size
        var levels = 0
        while (cursorSize > 1) {
            val split = largestPowerOfTwoLessThan(cursorSize)
            if (cursorIndex < split) {
                cursorSize = split
            } else {
                cursorIndex -= split
                cursorSize -= split
            }
            levels++
        }
        return levels
    }

    /** Largest power of two strictly less than [size]; [size] must be > 1. */
    internal fun largestPowerOfTwoLessThan(size: Int): Int {
        require(size > 1) { "A split needs at least two leaves." }
        var power = 1
        while (power * 2 < size) power *= 2
        return power
    }

    private fun ceilLog2(value: Int): Int {
        var levels = 0
        var remaining = value - 1
        while (remaining > 0) {
            remaining = remaining shr 1
            levels++
        }
        return levels
    }
}
