package com.faforever.icebreaker.util

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

@ApplicationScoped
class ClockProducer {
    @Produces
    @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
