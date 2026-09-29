package pl.allegro.cyan.coroutines.demo

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GracefulCoroutineScope(
    dispatcher: CoroutineDispatcher,
    private val shutdownTimeout: Duration = 30.seconds,
    exceptionHandler: CoroutineExceptionHandler? = null,
) : CoroutineScope, AutoCloseable {
    private val job = SupervisorJob()
    private val handlerContext: CoroutineContext = exceptionHandler ?: EmptyCoroutineContext

    override val coroutineContext: CoroutineContext = dispatcher + handlerContext + job

    override fun close() {
        runBlocking {
            job.complete()
            val completedGracefully = withTimeoutOrNull(shutdownTimeout) {
                job.join()
                true
            } ?: false

            if (!completedGracefully) {
                job.cancelAndJoin()
            }
        }
    }
}
