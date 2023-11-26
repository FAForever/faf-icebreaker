package com.faforever.icebreaker.util

import java.util.concurrent.CompletableFuture

object AsyncRunner {

    fun runLater(runnable: Runnable) {
        CompletableFuture.runAsync(runnable)
    }
}
