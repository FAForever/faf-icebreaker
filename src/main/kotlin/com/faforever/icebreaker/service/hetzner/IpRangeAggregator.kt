package com.faforever.icebreaker.service.hetzner

import inet.ipaddr.IPAddress
import inet.ipaddr.ipv4.IPv4Address
import inet.ipaddr.ipv6.IPv6Address
import java.lang.Long.compareUnsigned
import java.math.BigInteger

internal data class IpRangeAggregation(
    val prefixesByFamily: Map<IpAddressFamily, List<String>>,
    val addressCount: Int,
    val additionalAddressCount: BigInteger,
    val largestPrefixAdditionalAddressCount: BigInteger,
) {
    val prefixes: List<String> = prefixesByFamily.values.flatten()
}

internal enum class IpAddressFamily(val bitCount: Int) {
    IPV4(32),
    IPV6(128),
}

/**
 * Finds the CIDR cover of [addresses] that admits the fewest additional addresses
 * while fitting within [maxPrefixes].
 *
 * For each node in the IPv4 and IPv6 prefix tries, the dynamic program compares
 * collapsing the complete subtree into one prefix with every possible allocation
 * of the prefix budget between its children. This makes the result globally optimal
 * for the number of additional addresses admitted, rather than relying on a greedy
 * nearest-neighbour merge. Equal-cost results prefer the one whose broadest prefix
 * admits fewer additional addresses, then the result with fewer prefixes.
 *
 * The dynamic program runs on flat primitive tables. Because the chosen prefixes are
 * disjoint, the additional addresses they admit never reach 2^128, so widening costs
 * are tracked as unsigned 128-bit (high, low) word pairs instead of [BigInteger]s.
 * The reported counts are recomputed from the selected prefixes, which are few.
 * When the exact cover of the addresses already fits the budget, it is the optimum
 * and is returned without running the dynamic program at all.
 */
internal object IpRangeAggregator {
    fun aggregate(addresses: Collection<IPAddress>, maxPrefixes: Int): IpRangeAggregation {
        require(maxPrefixes > 0) { "maxPrefixes must be positive" }

        val roots = buildFamilyTries(addresses)
        if (roots.isEmpty()) {
            return IpRangeAggregation(emptyMap(), 0, BigInteger.ZERO, BigInteger.ZERO)
        }
        require(maxPrefixes >= roots.size) {
            "maxPrefixes must allow at least one prefix per address family"
        }

        val addressCount = roots.sumOf { root -> root.trie.addressCount }
        if (roots.sumOf { root -> root.trie.exactCoverSize } <= maxPrefixes) {
            return aggregationOf(roots.map { root -> root.family to exactCover(root.trie) }, addressCount)
        }

        val plans = roots.map { root -> buildPlans(root.trie, maxPrefixes) }
        val prefixCounts = allocateBudget(plans, maxPrefixes)
        return aggregationOf(
            roots.mapIndexed { index, root -> root.family to selectedPrefixes(plans[index], prefixCounts[index]) },
            addressCount,
        )
    }

    /** Groups [addresses] by family and turns each group into a path-compressed prefix trie. */
    private fun buildFamilyTries(addresses: Collection<IPAddress>): List<FamilyRoot> {
        if (addresses.isEmpty()) return emptyList()

        val values = addresses
            .map { address ->
                require(!address.isMultiple) { "Only individual IP addresses can be aggregated" }
                val normalized = address.withoutPrefixLength()
                val bytes = normalized.bytes
                if (normalized.isIPv4) {
                    AddressValue(IpAddressFamily.IPV4, 0, wordOf(bytes, 0, 4))
                } else {
                    AddressValue(IpAddressFamily.IPV6, wordOf(bytes, 0, 8), wordOf(bytes, 8, 16))
                }
            }
            .sortedWith(ADDRESS_ORDER)

        return IpAddressFamily.entries.mapNotNull { family ->
            val familyValues = values.filter { value -> value.family == family }
            if (familyValues.isEmpty()) return@mapNotNull null

            val highWords = LongArray(familyValues.size)
            val lowWords = LongArray(familyValues.size)
            var size = 0
            for (value in familyValues) {
                if (size == 0 || highWords[size - 1] != value.high || lowWords[size - 1] != value.low) {
                    highWords[size] = value.high
                    lowWords[size] = value.low
                    size++
                }
            }
            FamilyRoot(family, buildTrie(family.bitCount, highWords, lowWords, 0, size))
        }
    }

    /** Builds the trie over the ascending, de-duplicated address words in `[fromIndex, toIndex)`. */
    private fun buildTrie(
        bitCount: Int,
        highWords: LongArray,
        lowWords: LongArray,
        fromIndex: Int,
        toIndex: Int,
    ): TrieNode {
        val high = highWords[fromIndex]
        val low = lowWords[fromIndex]
        val addressCount = toIndex - fromIndex
        if (addressCount == 1) {
            return TrieNode(bitCount, bitCount, high, low, 1, 1)
        }

        val commonPrefixLength = bitCount - bitLength(
            high xor highWords[toIndex - 1],
            low xor lowWords[toIndex - 1],
        )
        val splitBitIndex = bitCount - commonPrefixLength - 1

        var lowIndex = fromIndex
        var highIndex = toIndex
        while (lowIndex < highIndex) {
            val middle = (lowIndex + highIndex) ushr 1
            if (isBitSet(highWords[middle], lowWords[middle], splitBitIndex)) {
                highIndex = middle
            } else {
                lowIndex = middle + 1
            }
        }

        val left = buildTrie(bitCount, highWords, lowWords, fromIndex, lowIndex)
        val right = buildTrie(bitCount, highWords, lowWords, lowIndex, toIndex)
        val hostBits = bitCount - commonPrefixLength
        val coversEveryAddress = hostBits < Long.SIZE_BITS - 1 && addressCount.toLong() == 1L shl hostBits
        return TrieNode(
            bitCount = bitCount,
            prefixLength = commonPrefixLength,
            high = high,
            low = low,
            addressCount = addressCount,
            exactCoverSize = if (coversEveryAddress) 1 else left.exactCoverSize + right.exactCoverSize,
            left = left,
            right = right,
        )
    }

    /**
     * Fills the widening cost of covering [node] with `1..min(maxPrefixes, exactCoverSize)` prefixes.
     *
     * Budgets beyond the exact cover cannot improve on it - it already admits no additional
     * addresses with fewer prefixes - so those columns are left out of the table entirely.
     */
    private fun buildPlans(node: TrieNode, maxPrefixes: Int): PlanTable {
        val left = node.left?.let { child -> buildPlans(child, maxPrefixes) }
        val right = node.right?.let { child -> buildPlans(child, maxPrefixes) }
        val plans = PlanTable(node, left, right, minOf(maxPrefixes, node.exactCoverSize))

        val collapseHigh = node.collapseCostHigh()
        val collapseLow = node.collapseCostLow()
        plans.costHigh[1] = collapseHigh
        plans.costLow[1] = collapseLow
        plans.widestHigh[1] = collapseHigh
        plans.widestLow[1] = collapseLow
        if (left == null || right == null) return plans

        for (leftCount in 1..minOf(left.size, plans.size - 1)) {
            val leftHigh = left.costHigh[leftCount]
            val leftLow = left.costLow[leftCount]
            val leftWidestHigh = left.widestHigh[leftCount]
            val leftWidestLow = left.widestLow[leftCount]
            for (rightCount in 1..minOf(right.size, plans.size - leftCount)) {
                val costHigh = addHigh(leftHigh, leftLow, right.costHigh[rightCount], right.costLow[rightCount])
                val costLow = leftLow + right.costLow[rightCount]
                val rightWidestHigh = right.widestHigh[rightCount]
                val rightWidestLow = right.widestLow[rightCount]
                val widestIsLeft =
                    compare(leftWidestHigh, leftWidestLow, rightWidestHigh, rightWidestLow) >= 0
                val widestHigh = if (widestIsLeft) leftWidestHigh else rightWidestHigh
                val widestLow = if (widestIsLeft) leftWidestLow else rightWidestLow

                val prefixCount = leftCount + rightCount
                if (plans.isBetter(prefixCount, costHigh, costLow, widestHigh, widestLow)) {
                    plans.costHigh[prefixCount] = costHigh
                    plans.costLow[prefixCount] = costLow
                    plans.widestHigh[prefixCount] = widestHigh
                    plans.widestLow[prefixCount] = widestLow
                    plans.leftPrefixCount[prefixCount] = leftCount
                }
            }
        }
        return plans
    }

    /** Splits [maxPrefixes] between the address families, returning the count granted to each. */
    private fun allocateBudget(plans: List<PlanTable>, maxPrefixes: Int): IntArray {
        var combined = CombinedTable(maxPrefixes)
        combined.reachable[0] = true
        val familyPrefixCounts = List(plans.size) { IntArray(maxPrefixes + 1) }
        val sum = WideSum()

        plans.forEachIndexed { familyIndex, familyPlans ->
            val next = CombinedTable(maxPrefixes)
            val chosen = familyPrefixCounts[familyIndex]
            for (existingCount in 0..maxPrefixes) {
                if (!combined.reachable[existingCount]) continue
                val existingWidestHigh = combined.widestHigh[existingCount]
                val existingWidestLow = combined.widestLow[existingCount]
                for (familyCount in 1..familyPlans.size) {
                    val prefixCount = existingCount + familyCount
                    if (prefixCount > maxPrefixes) break

                    sum.set(
                        combined.costCarry[existingCount],
                        combined.costHigh[existingCount],
                        combined.costLow[existingCount],
                    )
                    sum.add(familyPlans.costHigh[familyCount], familyPlans.costLow[familyCount])
                    val familyWidestHigh = familyPlans.widestHigh[familyCount]
                    val familyWidestLow = familyPlans.widestLow[familyCount]
                    val widestIsExisting = compare(
                        existingWidestHigh,
                        existingWidestLow,
                        familyWidestHigh,
                        familyWidestLow,
                    ) >= 0
                    val widestHigh = if (widestIsExisting) existingWidestHigh else familyWidestHigh
                    val widestLow = if (widestIsExisting) existingWidestLow else familyWidestLow

                    if (next.isBetter(prefixCount, sum, widestHigh, widestLow)) {
                        next.costCarry[prefixCount] = sum.carry
                        next.costHigh[prefixCount] = sum.high
                        next.costLow[prefixCount] = sum.low
                        next.widestHigh[prefixCount] = widestHigh
                        next.widestLow[prefixCount] = widestLow
                        next.reachable[prefixCount] = true
                        chosen[prefixCount] = familyCount
                    }
                }
            }
            combined = next
        }

        var bestCount = -1
        for (prefixCount in 1..maxPrefixes) {
            if (!combined.reachable[prefixCount]) continue
            if (bestCount < 0 || combined.isCheaperThan(prefixCount, bestCount)) {
                bestCount = prefixCount
            }
        }

        check(bestCount > 0) { "Every family can be covered by one prefix, so a budget must be reachable" }

        val result = IntArray(plans.size)
        var remaining = bestCount
        for (familyIndex in plans.indices.reversed()) {
            result[familyIndex] = familyPrefixCounts[familyIndex][remaining]
            remaining -= result[familyIndex]
        }
        return result
    }

    /** Walks the decisions recorded in [plans] and returns the prefixes of the chosen cover. */
    private fun selectedPrefixes(plans: PlanTable, prefixCount: Int): List<TrieNode> =
        buildList { collectSelectedPrefixes(plans, prefixCount, this) }

    private fun collectSelectedPrefixes(plans: PlanTable, prefixCount: Int, destination: MutableList<TrieNode>) {
        val leftPrefixCount = plans.leftPrefixCount[prefixCount]
        if (leftPrefixCount == COLLAPSE) {
            destination.add(plans.node)
            return
        }
        collectSelectedPrefixes(plans.left!!, leftPrefixCount, destination)
        collectSelectedPrefixes(plans.right!!, prefixCount - leftPrefixCount, destination)
    }

    /** Returns the smallest set of prefixes that covers the addresses of [node] and nothing else. */
    private fun exactCover(node: TrieNode): List<TrieNode> = buildList { collectExactCover(node, this) }

    private fun collectExactCover(node: TrieNode, destination: MutableList<TrieNode>) {
        if (node.exactCoverSize == 1) {
            destination.add(node)
            return
        }
        collectExactCover(node.left!!, destination)
        collectExactCover(node.right!!, destination)
    }

    private fun aggregationOf(
        selectionsByFamily: List<Pair<IpAddressFamily, List<TrieNode>>>,
        addressCount: Int,
    ): IpRangeAggregation {
        var additionalAddressCount = BigInteger.ZERO
        var largestPrefixAdditionalAddressCount = BigInteger.ZERO
        for ((_, nodes) in selectionsByFamily) {
            for (node in nodes) {
                val nodeAdditionalAddressCount = node.additionalAddressCount()
                additionalAddressCount += nodeAdditionalAddressCount
                if (nodeAdditionalAddressCount > largestPrefixAdditionalAddressCount) {
                    largestPrefixAdditionalAddressCount = nodeAdditionalAddressCount
                }
            }
        }
        return IpRangeAggregation(
            prefixesByFamily = selectionsByFamily.associate { (family, nodes) ->
                family to nodes.map { node -> node.toPrefix() }
            },
            addressCount = addressCount,
            additionalAddressCount = additionalAddressCount,
            largestPrefixAdditionalAddressCount = largestPrefixAdditionalAddressCount,
        )
    }

    private val ADDRESS_ORDER = Comparator<AddressValue> { first, second ->
        val familyComparison = first.family.compareTo(second.family)
        if (familyComparison != 0) {
            familyComparison
        } else {
            compare(first.high, first.low, second.high, second.low)
        }
    }

    private class AddressValue(val family: IpAddressFamily, val high: Long, val low: Long)

    private class FamilyRoot(val family: IpAddressFamily, val trie: TrieNode)

    private class TrieNode(
        val bitCount: Int,
        val prefixLength: Int,
        val high: Long,
        val low: Long,
        val addressCount: Int,
        val exactCoverSize: Int,
        val left: TrieNode? = null,
        val right: TrieNode? = null,
    ) {
        private val hostBits: Int get() = bitCount - prefixLength

        /** Low word of `2^hostBits - addressCount`, the additional addresses this prefix admits. */
        fun collapseCostLow(): Long = powerOfTwoLow(hostBits) - addressCount

        /** High word of `2^hostBits - addressCount`, borrowing from the low word where needed. */
        fun collapseCostHigh(): Long {
            val low = powerOfTwoLow(hostBits)
            val borrow = if (compareUnsigned(low, addressCount.toLong()) < 0) 1L else 0L
            return powerOfTwoHigh(hostBits) - borrow
        }

        fun additionalAddressCount(): BigInteger =
            BigInteger.ONE.shiftLeft(hostBits) - BigInteger.valueOf(addressCount.toLong())

        fun toPrefix(): String {
            val address: IPAddress = if (bitCount == IpAddressFamily.IPV4.bitCount) {
                IPv4Address(low.toInt())
            } else {
                IPv6Address(ByteArray(16) { index -> byteOf(high, low, index) })
            }
            return address.setPrefixLength(prefixLength).toPrefixBlock().toCanonicalString()
        }
    }

    /**
     * Widening costs of covering one trie node, indexed by the number of prefixes spent on it.
     * [leftPrefixCount] records how many prefixes the best split of a budget grants to the left
     * child, or [COLLAPSE] when the whole subtree becomes one prefix. A budget that no split has
     * filled in yet reads as [COLLAPSE] too, which is harmless: only a budget of one prefix
     * leaves a subtree no choice but to collapse.
     */
    private class PlanTable(val node: TrieNode, val left: PlanTable?, val right: PlanTable?, val size: Int) {
        val costHigh = LongArray(size + 1)
        val costLow = LongArray(size + 1)
        val widestHigh = LongArray(size + 1)
        val widestLow = LongArray(size + 1)
        val leftPrefixCount = IntArray(size + 1)

        fun isBetter(prefixCount: Int, costHigh: Long, costLow: Long, widestHigh: Long, widestLow: Long): Boolean {
            if (leftPrefixCount[prefixCount] == COLLAPSE) return true
            val costComparison = compare(costHigh, costLow, this.costHigh[prefixCount], this.costLow[prefixCount])
            if (costComparison != 0) return costComparison < 0
            return compare(widestHigh, widestLow, this.widestHigh[prefixCount], this.widestLow[prefixCount]) < 0
        }
    }

    /**
     * Widening costs of covering all address families, indexed by the total number of prefixes.
     * Two families can together admit slightly more than 2^128 additional addresses, so the cost
     * carries a third word.
     */
    private class CombinedTable(maxPrefixes: Int) {
        val reachable = BooleanArray(maxPrefixes + 1)
        val costCarry = LongArray(maxPrefixes + 1)
        val costHigh = LongArray(maxPrefixes + 1)
        val costLow = LongArray(maxPrefixes + 1)
        val widestHigh = LongArray(maxPrefixes + 1)
        val widestLow = LongArray(maxPrefixes + 1)

        fun isBetter(prefixCount: Int, cost: WideSum, widestHigh: Long, widestLow: Long): Boolean {
            if (!reachable[prefixCount]) return true
            val costComparison = cost.compareTo(costCarry[prefixCount], costHigh[prefixCount], costLow[prefixCount])
            if (costComparison != 0) return costComparison < 0
            return compare(widestHigh, widestLow, this.widestHigh[prefixCount], this.widestLow[prefixCount]) < 0
        }

        /** Compares two reachable budgets, preferring cheap covers, then narrow prefixes, then few prefixes. */
        fun isCheaperThan(prefixCount: Int, other: Int): Boolean {
            val carryComparison = costCarry[prefixCount].compareTo(costCarry[other])
            if (carryComparison != 0) return carryComparison < 0
            val costComparison = compare(costHigh[prefixCount], costLow[prefixCount], costHigh[other], costLow[other])
            if (costComparison != 0) return costComparison < 0
            val widestComparison =
                compare(widestHigh[prefixCount], widestLow[prefixCount], widestHigh[other], widestLow[other])
            if (widestComparison != 0) return widestComparison < 0
            return prefixCount < other
        }
    }

    /** Reusable 192-bit accumulator, so summing family costs allocates nothing. */
    private class WideSum {
        var carry = 0L
        var high = 0L
        var low = 0L

        fun set(carry: Long, high: Long, low: Long) {
            this.carry = carry
            this.high = high
            this.low = low
        }

        fun add(high: Long, low: Long) {
            val sumLow = this.low + low
            val lowCarry = if (compareUnsigned(sumLow, this.low) < 0) 1L else 0L
            val sumHigh = this.high + high
            var highCarry = if (compareUnsigned(sumHigh, this.high) < 0) 1L else 0L
            val carriedHigh = sumHigh + lowCarry
            if (compareUnsigned(carriedHigh, sumHigh) < 0) highCarry++
            this.low = sumLow
            this.high = carriedHigh
            this.carry += highCarry
        }

        fun compareTo(carry: Long, high: Long, low: Long): Int {
            val carryComparison = this.carry.compareTo(carry)
            return if (carryComparison != 0) carryComparison else compare(this.high, this.low, high, low)
        }
    }
}

/** Marks a budget of one prefix, which can only be spent by collapsing the whole subtree. */
private const val COLLAPSE = 0

/** Reads the big-endian bytes `[fromIndex, toIndex)` as an unsigned word. */
private fun wordOf(bytes: ByteArray, fromIndex: Int, toIndex: Int): Long {
    var word = 0L
    for (index in fromIndex until toIndex) {
        word = (word shl Byte.SIZE_BITS) or (bytes[index].toLong() and 0xFF)
    }
    return word
}

private fun byteOf(high: Long, low: Long, index: Int): Byte {
    val word = if (index < Long.SIZE_BYTES) high else low
    return (word ushr ((Long.SIZE_BYTES - 1 - (index % Long.SIZE_BYTES)) * Byte.SIZE_BITS)).toByte()
}

private fun powerOfTwoLow(exponent: Int): Long = if (exponent < Long.SIZE_BITS) 1L shl exponent else 0L

private fun powerOfTwoHigh(exponent: Int): Long =
    if (exponent in Long.SIZE_BITS until 2 * Long.SIZE_BITS) 1L shl (exponent - Long.SIZE_BITS) else 0L

private fun bitLength(high: Long, low: Long): Int = if (high != 0L) {
    2 * Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(high)
} else {
    Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(low)
}

private fun isBitSet(high: Long, low: Long, index: Int): Boolean = if (index < Long.SIZE_BITS) {
    (low ushr index) and 1L != 0L
} else {
    (high ushr (index - Long.SIZE_BITS)) and 1L != 0L
}

/** Adds two unsigned 128-bit values and returns the high word of the sum. */
private fun addHigh(firstHigh: Long, firstLow: Long, secondHigh: Long, secondLow: Long): Long {
    val low = firstLow + secondLow
    return firstHigh + secondHigh + if (compareUnsigned(low, firstLow) < 0) 1L else 0L
}

private fun compare(firstHigh: Long, firstLow: Long, secondHigh: Long, secondLow: Long): Int {
    val highComparison = compareUnsigned(firstHigh, secondHigh)
    return if (highComparison != 0) highComparison else compareUnsigned(firstLow, secondLow)
}
