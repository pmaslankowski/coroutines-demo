package pl.allegro.cyan.coroutines.threading

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

internal suspend fun currentCoroutineDispatcherName(): String =
    coroutineContext[ContinuationInterceptor]?.toString() ?: "none"

internal suspend fun logCoroutineDispatcher(testName: String) {
    println("$testName coroutine dispatcher: ${currentCoroutineDispatcherName()}")
}
