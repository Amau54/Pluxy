package com.pluxy.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.Closeable

/** Commands cannot observe a partially restored library or overtake an earlier command. */
internal class ServerCommandQueue<C>(
    scope: CoroutineScope,
    initialize: suspend () -> Unit,
    handle: suspend (C) -> Unit,
    onFailure: (Exception) -> Unit,
) : Closeable {
    private val commands = Channel<C>(Channel.UNLIMITED)
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        try {
            initialize()
            for (command in commands) handle(command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
        } finally {
            commands.cancel()
        }
    }.also { job ->
        // Also reject commands when the owning scope was cancelled before launch.
        job.invokeOnCompletion { commands.cancel() }
    }

    fun submit(command: C): Boolean {
        if (!commands.trySend(command).isSuccess) return false
        worker.start()
        return true
    }

    override fun close() {
        commands.cancel()
        worker.cancel()
    }
}
