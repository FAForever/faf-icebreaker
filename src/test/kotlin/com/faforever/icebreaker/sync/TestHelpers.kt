package com.faforever.icebreaker.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Polls the predicate until it returns true or a timeout expires.
 * Throws an AssertionError if the condition is not met within the timeout.
 */
suspend fun waitUntil(
    timeout: Duration = 5_000.milliseconds,
    pred: () -> Boolean,
) {
    val checkInterval = 100.milliseconds
    val succeeded = withTimeoutOrNull(timeout) {
        while (!pred()) {
            delay(checkInterval)
        }
        true
    } ?: false

    assertThat(succeeded)
        .withFailMessage("waitUntil condition was not met within ${timeout.inWholeSeconds}s")
        .isTrue()
}
