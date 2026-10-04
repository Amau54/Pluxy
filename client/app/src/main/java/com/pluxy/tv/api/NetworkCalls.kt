package com.pluxy.tv.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Keep cancellation connected to the socket until the body has been read and closed. */
internal suspend fun <T> Call.awaitResult(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    // Parsing runs on OkHttp's worker, and use closes error responses too.
                    val result = response.use(read)
                    continuation.resume(result)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        })
    }

/** Optional network data may fail, but cancellation must never become a fallback value. */
internal inline fun <T> networkOrNull(read: () -> T): T? = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
