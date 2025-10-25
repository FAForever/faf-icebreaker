package com.faforever.icebreaker.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import kotlin.time.Duration.Companion.milliseconds

/**
 * Polls the predicate until it returns true or a timeout expires.
 * Throws an AssertionError if the condition is not met within the timeout.
 */
suspend fun waitUntil(pred: () -> Boolean) {
    val timeout = 5_000.milliseconds
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
