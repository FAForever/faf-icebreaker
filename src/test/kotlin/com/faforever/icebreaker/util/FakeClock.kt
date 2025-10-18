package com.faforever.icebreaker.util

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@Mock
@ApplicationScoped
class FakeClock : Clock() {
    private var now = Instant.now()
    private var zone: ZoneId = ZoneOffset.UTC

    override fun instant(): Instant {
        val oldNow = now
        now += Duration.ofSeconds(1)
        return oldNow
    }

    fun setNow(newNow: Instant) {
        now = newNow
    }

    override fun getZone() = zone
    override fun withZone(newZone: ZoneId): Clock {
        zone = newZone
        return this
    }
}
