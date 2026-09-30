package pl.allegro.cyan.coroutines.threading

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

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

    @Test
    fun `nested withContext`() = runBlocking {
        val dispatcherOuter = Executors.newSingleThreadExecutor { task ->
            Thread(task, "dispatcher-demo-worker-outer").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        val dispatcherInner = Executors.newSingleThreadExecutor { task ->
            Thread(task, "dispatcher-demo-worker-inner").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        try {
            val callerThread = Thread.currentThread()
            println("Caller: ${callerThread.name}")

            withContext(dispatcherOuter) {
                val beforeDelay = Thread.currentThread()
                println("Coroutine before outer delay: ${beforeDelay.name}")

                delay(50)

                withContext(dispatcherInner) {
                    val innerThreadBefore = Thread.currentThread()
                    println("Coroutine before inner delay: ${innerThreadBefore.name}")
                    delay(50)
                    val innerThreadAfter = Thread.currentThread()
                    println("Coroutine after inner delay: ${innerThreadAfter.name}")
                }

                val afterDelay = Thread.currentThread()
                println("Coroutine after outer delay: ${afterDelay.name}")
            }

            println("Caller after withContext: ${Thread.currentThread().name}")
        } finally {
            dispatcherInner.close()
        }
    }
}
