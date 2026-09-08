package com.faforever.icebreaker.service.hetzner;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class IpRangeAggregatorBenchmark {
    @Param({"mixed1500", "random5000", "under400", "clustered4696"})
    public String scenario;

    private List<IPAddress> addresses;

    @Setup
    public void setup() {
        addresses = new ArrayList<>();
        switch (scenario) {
            case "mixed1500" -> {
                addSparseIpv4(1000);
                for (int i = 0; i < 500; i++) {
                    addresses.add(new IPAddressString("2001:db8:" + Integer.toHexString(i) + "::1").getAddress());
                }
            }
            case "random5000" -> {
                Random random = new Random(4221);
                for (int i = 0; i < 5000; i++) {
                    addresses.add(new IPv4Address(random.nextInt()));
                }
            }
            case "under400" -> addSparseIpv4(400);
            case "clustered4696" -> {
                for (int i = 0; i < 4096; i++) {
                    addresses.add(new IPv4Address(0xc0a80000 + i));
                }
                addSparseIpv4(600);
            }
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
    }

    private void addSparseIpv4(int count) {
        for (int i = 0; i < count; i++) {
            addresses.add(new IPv4Address(0x0a000001 + (i << 8)));
        }
    }

    @Benchmark
    public Object aggregate() {
        return IpRangeAggregator.INSTANCE.aggregate(addresses, 500);
    }
}
