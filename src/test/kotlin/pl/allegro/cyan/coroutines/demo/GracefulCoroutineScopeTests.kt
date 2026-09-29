package pl.allegro.cyan.coroutines.demo

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GracefulCoroutineScopeTests {

    @Test
    fun `close waits for active children to finish gracefully`() {
        val workCompleted = AtomicBoolean()
        val scope = GracefulCoroutineScope(
            dispatcher = Dispatchers.Default,
            shutdownTimeout = 1.seconds,
        )

        scope.launch {
            println("Worker: started work")
            delay(100)
            workCompleted.set(true)
            println("Worker: finished work")
        }

        println("Application: starting graceful shutdown")
        scope.close()
        println("Application: shutdown finished")

        assertTrue(workCompleted.get(), "close should wait for work to finish before returning")
    }

    @Test
    fun `regular Job cancels sibling work when a child fails`() {
        val failureReported = AtomicReference<Throwable?>()
        val siblingCancelled = AtomicBoolean()
        val exceptionHandler = CoroutineExceptionHandler { _, failure ->
            println("Exception handler: reported $failure")
            failureReported.set(failure)
        }
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + exceptionHandler + job)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                println("Sibling worker: starting a long delay in a regular Job scope")
                delay(10_000)
                println("Sibling worker: long delay completed")
            } finally {
                siblingCancelled.set(true)
                println("Sibling worker: cancelled by the other child's failure")
            }
        }
        scope.launch {
            println("Failing worker: starting")
            delay(50)
            println("Failing worker: throwing")
            throw IllegalStateException("worker failed")
        }

        runBlocking { job.join() }

        assertEquals("worker failed", failureReported.get()?.message)
        assertTrue(siblingCancelled.get(), "a regular Job propagates child failure and cancels its siblings")
    }
}
