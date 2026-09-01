package com.faforever.icebreaker.service.hetzner

import inet.ipaddr.IPAddress
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
 */
internal object IpRangeAggregator {
    fun aggregate(addresses: Collection<IPAddress>, maxPrefixes: Int): IpRangeAggregation {
        require(maxPrefixes > 0) { "maxPrefixes must be positive" }

        val uniqueAddresses = addresses
            .map { address ->
                require(!address.isMultiple) { "Only individual IP addresses can be aggregated" }
                val normalized = address.withoutPrefixLength()
                AddressValue(
                    family = if (normalized.isIPv4) IpAddressFamily.IPV4 else IpAddressFamily.IPV6,
                    value = BigInteger(1, normalized.bytes),
                    address = normalized,
                )
            }
            .distinctBy { address -> address.family to address.value }
            .sortedWith(compareBy<AddressValue> { it.family }.thenBy { it.value })

        if (uniqueAddresses.isEmpty()) {
            return IpRangeAggregation(emptyMap(), 0, BigInteger.ZERO, BigInteger.ZERO)
        }

        val roots = uniqueAddresses
            .groupBy { it.family }
            .toSortedMap()
            .map { (family, addresses) -> FamilyRoot(family, buildTrie(addresses)) }
        require(maxPrefixes >= roots.size) {
            "maxPrefixes must allow at least one prefix per address family"
        }
        val usableMaxPrefixes = minOf(maxPrefixes, uniqueAddresses.size)

        var combined = arrayOfNulls<CombinedPlan>(usableMaxPrefixes + 1)
        combined[0] = CombinedPlan(BigInteger.ZERO, BigInteger.ZERO, emptyList())

        for (root in roots) {
            val familyPlans = buildPlans(root.trie, usableMaxPrefixes)
            val next = arrayOfNulls<CombinedPlan>(usableMaxPrefixes + 1)
            for (existingCount in combined.indices) {
                val existing = combined[existingCount] ?: continue
                for (familyCount in 1 until familyPlans.size) {
                    val familyPlan = familyPlans[familyCount] ?: continue
                    val totalCount = existingCount + familyCount
                    if (totalCount > usableMaxPrefixes) break

                    val candidate = CombinedPlan(
                        additionalAddressCount = existing.additionalAddressCount + familyPlan.additionalAddressCount,
                        largestPrefixAdditionalAddressCount = maxOf(
                            existing.largestPrefixAdditionalAddressCount,
                            familyPlan.largestPrefixAdditionalAddressCount,
                        ),
                        familyPlans = existing.familyPlans + familyPlan,
                    )
                    if (candidate.isBetterThan(next[totalCount])) {
                        next[totalCount] = candidate
                    }
                }
            }
            combined = next
        }

        val best = combined.withIndex()
            .filter { (_, plan) -> plan != null }
            .minWith { first, second -> compareCombined(first.index, first.value!!, second.index, second.value!!) }
            .value!!

        return IpRangeAggregation(
            prefixesByFamily = buildMap {
                roots.forEachIndexed { index, root ->
                    put(root.family, buildList { best.familyPlans[index].collectPrefixes(this) })
                }
            },
            addressCount = uniqueAddresses.size,
            additionalAddressCount = best.additionalAddressCount,
            largestPrefixAdditionalAddressCount = best.largestPrefixAdditionalAddressCount,
        )
    }

    private fun buildTrie(addresses: List<AddressValue>): TrieNode = buildTrie(addresses, 0, addresses.size)

    private fun buildTrie(addresses: List<AddressValue>, fromIndex: Int, toIndex: Int): TrieNode {
        val first = addresses[fromIndex]
        val addressCount = toIndex - fromIndex
        if (addressCount == 1) {
            return TrieNode(first.bitCount, first.bitCount, first.address, 1)
        }

        val last = addresses[toIndex - 1]
        val differingBits = first.value.xor(last.value)
        val commonPrefixLength = first.bitCount - differingBits.bitLength()
        val splitBit = BigInteger.ONE.shiftLeft(first.bitCount - commonPrefixLength - 1)

        var low = fromIndex
        var high = toIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (addresses[middle].value.and(splitBit) == BigInteger.ZERO) {
                low = middle + 1
            } else {
                high = middle
            }
        }

        return TrieNode(
            bitCount = first.bitCount,
            prefixLength = commonPrefixLength,
            representative = first.address,
            addressCount = addressCount,
            left = buildTrie(addresses, fromIndex, low),
            right = buildTrie(addresses, low, toIndex),
        )
    }

    private fun buildPlans(node: TrieNode, maxPrefixes: Int): Array<Plan?> {
        val plans = arrayOfNulls<Plan>(minOf(maxPrefixes, node.addressCount) + 1)
        val collapseCost = BigInteger.ONE.shiftLeft(node.bitCount - node.prefixLength) -
            BigInteger.valueOf(node.addressCount.toLong())
        plans[1] = Plan(collapseCost, collapseCost, Collapse(node))

        val left = node.left ?: return plans
        val right = node.right ?: return plans
        val leftPlans = buildPlans(left, maxPrefixes)
        val rightPlans = buildPlans(right, maxPrefixes)

        for (leftCount in 1 until leftPlans.size) {
            val leftPlan = leftPlans[leftCount] ?: continue
            for (rightCount in 1 until rightPlans.size) {
                val rightPlan = rightPlans[rightCount] ?: continue
                val totalCount = leftCount + rightCount
                if (totalCount >= plans.size) break

                val candidate = Plan(
                    additionalAddressCount = leftPlan.additionalAddressCount + rightPlan.additionalAddressCount,
                    largestPrefixAdditionalAddressCount = maxOf(
                        leftPlan.largestPrefixAdditionalAddressCount,
                        rightPlan.largestPrefixAdditionalAddressCount,
                    ),
                    decision = Split(leftPlan, rightPlan),
                )
                if (candidate.isBetterThan(plans[totalCount])) {
                    plans[totalCount] = candidate
                }
            }
        }
        return plans
    }

    private fun compareCombined(
        firstPrefixCount: Int,
        first: CombinedPlan,
        secondPrefixCount: Int,
        second: CombinedPlan,
    ): Int {
        val totalComparison = first.additionalAddressCount.compareTo(second.additionalAddressCount)
        if (totalComparison != 0) return totalComparison

        val largestComparison = first.largestPrefixAdditionalAddressCount
            .compareTo(second.largestPrefixAdditionalAddressCount)
        if (largestComparison != 0) return largestComparison

        return firstPrefixCount.compareTo(secondPrefixCount)
    }

    private data class AddressValue(
        val family: IpAddressFamily,
        val value: BigInteger,
        val address: IPAddress,
    ) {
        val bitCount: Int = family.bitCount
    }

    private data class FamilyRoot(
        val family: IpAddressFamily,
        val trie: TrieNode,
    )

    private data class TrieNode(
        val bitCount: Int,
        val prefixLength: Int,
        val representative: IPAddress,
        val addressCount: Int,
        val left: TrieNode? = null,
        val right: TrieNode? = null,
    ) {
        fun toPrefix(): String = representative.setPrefixLength(prefixLength).toPrefixBlock().toCanonicalString()
    }

    private data class Plan(
        val additionalAddressCount: BigInteger,
        val largestPrefixAdditionalAddressCount: BigInteger,
        val decision: Decision,
    ) {
        fun isBetterThan(other: Plan?): Boolean = other == null ||
            additionalAddressCount < other.additionalAddressCount ||
            (
                additionalAddressCount == other.additionalAddressCount &&
                    largestPrefixAdditionalAddressCount < other.largestPrefixAdditionalAddressCount
                )

        fun collectPrefixes(destination: MutableList<String>) {
            when (val selected = decision) {
                is Collapse -> destination.add(selected.node.toPrefix())
                is Split -> {
                    selected.left.collectPrefixes(destination)
                    selected.right.collectPrefixes(destination)
                }
            }
        }
    }

    private data class CombinedPlan(
        val additionalAddressCount: BigInteger,
        val largestPrefixAdditionalAddressCount: BigInteger,
        val familyPlans: List<Plan>,
    ) {
        fun isBetterThan(other: CombinedPlan?): Boolean = other == null ||
            additionalAddressCount < other.additionalAddressCount ||
            (
                additionalAddressCount == other.additionalAddressCount &&
                    largestPrefixAdditionalAddressCount < other.largestPrefixAdditionalAddressCount
                )
    }

    private sealed interface Decision

    private data class Collapse(val node: TrieNode) : Decision

    private data class Split(val left: Plan, val right: Plan) : Decision
}
