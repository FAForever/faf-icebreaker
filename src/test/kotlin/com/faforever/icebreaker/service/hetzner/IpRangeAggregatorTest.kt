package com.faforever.icebreaker.service.hetzner

import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.math.BigInteger

internal class IpRangeAggregatorTest {
    @Test
    fun `Empty input produces no prefixes`() {
        val result = IpRangeAggregator.aggregate(emptyList(), maxPrefixes = 500)

        assertThat(result.prefixes).isEmpty()
        assertThat(result.additionalAddressCount).isEqualTo(BigInteger.ZERO)
    }

    @Test
    fun `Adjacent addresses merge without widening the whitelist`() {
        val result = IpRangeAggregator.aggregate(
            listOf("192.0.2.0", "192.0.2.1", "192.0.2.2", "192.0.2.3").map(::ip),
            maxPrefixes = 500,
        )

        assertThat(result.prefixes).containsExactly("192.0.2.0/30")
        assertThat(result.additionalAddressCount).isEqualTo(BigInteger.ZERO)
    }

    @Test
    fun `Closest cover is chosen when the exact representation exceeds the budget`() {
        val result = IpRangeAggregator.aggregate(
            listOf("10.0.0.0", "10.0.0.3", "10.0.0.4").map(::ip),
            maxPrefixes = 2,
        )

        assertThat(result.prefixes).containsExactly("10.0.0.0/30", "10.0.0.4/32")
        assertThat(result.additionalAddressCount).isEqualTo(BigInteger.valueOf(2))
        assertThat(result.largestPrefixAdditionalAddressCount).isEqualTo(BigInteger.valueOf(2))
    }

    @Test
    fun `IPv6 addresses use the same closest-cover optimization`() {
        val result = IpRangeAggregator.aggregate(
            listOf("2001:db8::1", "2001:db8::2").map(::ip),
            maxPrefixes = 1,
        )

        assertThat(result.prefixes).containsExactly("2001:db8::/126")
        assertThat(result.additionalAddressCount).isEqualTo(BigInteger.valueOf(2))
    }

    @Test
    fun `IPv4 and IPv6 share the global prefix budget`() {
        val addresses = listOf("192.0.2.1", "192.0.2.2", "2001:db8::1", "2001:db8::2").map(::ip)

        val result = IpRangeAggregator.aggregate(addresses, maxPrefixes = 2)

        assertThat(result.prefixes).containsExactly("192.0.2.0/30", "2001:db8::/126")
        assertThat(result.additionalAddressCount).isEqualTo(BigInteger.valueOf(4))
    }

    @Test
    fun `Duplicate addresses do not consume the prefix budget`() {
        val result = IpRangeAggregator.aggregate(
            listOf("192.0.2.1", "192.0.2.1", "192.0.2.2").map(::ip),
            maxPrefixes = 1,
        )

        assertThat(result.prefixes).containsExactly("192.0.2.0/30")
        assertThat(result.additionalAddressCount).isEqualTo(BigInteger.valueOf(2))
    }

    @Test
    fun `Output is deterministic regardless of input order`() {
        val addresses = listOf("10.0.0.0", "10.0.0.3", "10.0.0.4", "2001:db8::1").map(::ip)

        val forward = IpRangeAggregator.aggregate(addresses, maxPrefixes = 3)
        val reverse = IpRangeAggregator.aggregate(addresses.reversed(), maxPrefixes = 3)

        assertThat(reverse).isEqualTo(forward)
    }

    @Test
    fun `Every address remains covered at the production budget`() {
        val addresses = buildList {
            repeat(1_000) { index -> add(ip("10.${index / 256}.${index % 256}.1")) }
            repeat(500) { index -> add(ip("2001:db8:${index.toString(16)}::1")) }
        }

        val result = IpRangeAggregator.aggregate(addresses, maxPrefixes = 500)
        val prefixes = result.prefixes.map(::ip)

        assertThat(prefixes).hasSizeLessThanOrEqualTo(500)
        assertThat(addresses).allMatch { address -> prefixes.any { prefix -> prefix.contains(address) } }
        assertThat(prefixes.sumOf { prefix -> prefix.count } - BigInteger.valueOf(addresses.size.toLong()))
            .isEqualTo(result.additionalAddressCount)
        for (index in prefixes.indices) {
            assertThat(prefixes.withIndex())
                .filteredOn { candidate -> candidate.index != index }
                .noneMatch { candidate -> prefixes[index].contains(candidate.value) }
        }
    }

    @Test
    fun `Production limit is respected at 499 500 and 501 addresses`() {
        val addresses = List(501) { index -> ip("10.${index / 256}.${index % 256}.1") }

        val belowLimit = IpRangeAggregator.aggregate(addresses.take(499), maxPrefixes = 500)
        val atLimit = IpRangeAggregator.aggregate(addresses.take(500), maxPrefixes = 500)
        val aboveLimit = IpRangeAggregator.aggregate(addresses, maxPrefixes = 500)

        assertThat(belowLimit.prefixes).hasSize(499)
        assertThat(atLimit.prefixes).hasSize(500)
        assertThat(aboveLimit.prefixes).hasSize(500)
        assertThat(aboveLimit.additionalAddressCount).isPositive()
        val cover = aboveLimit.prefixes.map(::ip)
        assertThat(addresses).allMatch { address -> cover.any { prefix -> prefix.contains(address) } }
    }

    @Test
    fun `IPv6 widening metrics do not overflow`() {
        val addresses = listOf(ip("2001:db8::1"), ip("f001:db8::1"))

        val result = IpRangeAggregator.aggregate(addresses, maxPrefixes = 1)

        assertThat(result.prefixes).hasSize(1)
        assertThat(result.largestPrefixAdditionalAddressCount)
            .isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE))
        val cover = ip(result.prefixes.single())
        assertThat(addresses).allMatch(cover::contains)
    }

    @Test
    fun `Result is optimal for every subset of a small address space`() {
        val candidateCoverMasks = listOf(
            0xff,
            0x0f,
            0xf0,
            0x03,
            0x0c,
            0x30,
            0xc0,
            0x01,
            0x02,
            0x04,
            0x08,
            0x10,
            0x20,
            0x40,
            0x80,
        )
        val selectedBlockCounts = IntArray(1 shl candidateCoverMasks.size)
        val selectedCoverMasks = IntArray(1 shl candidateCoverMasks.size)
        for (selection in 1 until (1 shl candidateCoverMasks.size)) {
            val leastSignificantBit = selection and -selection
            val previous = selection xor leastSignificantBit
            val candidateIndex = Integer.numberOfTrailingZeros(leastSignificantBit)
            selectedBlockCounts[selection] = selectedBlockCounts[previous] + 1
            selectedCoverMasks[selection] = selectedCoverMasks[previous] or candidateCoverMasks[candidateIndex]
        }

        for (requiredMask in 1..0xff) {
            val requiredAddresses = (0 until 8)
                .filter { offset -> requiredMask and (1 shl offset) != 0 }
                .map { offset -> ip("198.51.100.$offset") }
            for (maxPrefixes in 1..minOf(4, requiredAddresses.size)) {
                val expectedAdditionalAddresses = selectedCoverMasks.indices
                    .asSequence()
                    .filter { selection -> selectedBlockCounts[selection] in 1..maxPrefixes }
                    .map { selection -> selectedCoverMasks[selection] }
                    .filter { cover -> cover and requiredMask == requiredMask }
                    .minOf { cover -> Integer.bitCount(cover) - requiredAddresses.size }

                val result = IpRangeAggregator.aggregate(requiredAddresses, maxPrefixes)

                assertThat(result.additionalAddressCount)
                    .describedAs("requiredMask=%s, maxPrefixes=%s", requiredMask, maxPrefixes)
                    .isEqualTo(BigInteger.valueOf(expectedAdditionalAddresses.toLong()))
                assertThat(result.prefixes).hasSizeLessThanOrEqualTo(maxPrefixes)
            }
        }
    }

    @Test
    fun `Prefix budget must be positive`() {
        assertThatIllegalArgumentException()
            .isThrownBy { IpRangeAggregator.aggregate(listOf(ip("192.0.2.1")), maxPrefixes = 0) }
    }

    @Test
    fun `Each address family requires one prefix`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                IpRangeAggregator.aggregate(
                    listOf(ip("192.0.2.1"), ip("2001:db8::1")),
                    maxPrefixes = 1,
                )
            }
            .withMessage("maxPrefixes must allow at least one prefix per address family")
    }

    @Test
    fun `Budget larger than the address set is harmless`() {
        val result = IpRangeAggregator.aggregate(listOf(ip("192.0.2.1")), maxPrefixes = Int.MAX_VALUE)

        assertThat(result.prefixes).containsExactly("192.0.2.1/32")
    }

    private fun ip(value: String): IPAddress = IPAddressString(value).toAddress()
}
