package pl.allegro.cyan.coroutines.threading

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DispatcherDemoTests {

    @Test
    fun `withContext switches execution and resumes after delay on its dispatcher`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "dispatcher-demo-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        try {
            val callerThread = Thread.currentThread()
            println("Caller: ${callerThread.name}")

            withContext(dispatcher) {
                val beforeDelay = Thread.currentThread()
                println("Coroutine before delay: ${beforeDelay.name}")

                delay(50)

                val afterDelay = Thread.currentThread()
                println("Coroutine after delay: ${afterDelay.name}")
            }

            println("Caller after withContext: ${Thread.currentThread().name}")
        } finally {
            dispatcher.close()
        }
    }
}
