package pl.allegro.cyan.coroutines.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pl.allegro.cyan.coroutines.threading.logCoroutineDispatcher
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StructuredConcurrencyTests {

    @Test
    fun `coroutineScope waits for its children before returning`() = runBlocking {
        coroutineScope {
            launch {
                println("Child: starting a short delay")
                delay(50)
                println("Child: finished after the scope block returned")
            }
            println("Scope block: finished; coroutineScope still waits for its child")
        }

        println("Parent: coroutineScope returned after the child finished")
    }

    @Test
    fun `a failed child cancels its sibling and propagates the failure`() {
        val failure = IllegalStateException("child failed")

        val thrown = runCatching {
            runBlocking {
                coroutineScope {
                    launch {
                        try {
                            println("Sibling: starting a long delay")
                            delay(10_000)
                            println("Sibling: long delay completed")
                        } finally {
                            println("Sibling: finishing (due to failure if you don't see long delay completed above) ")
                        }
                    }
                    launch {
                        println("Failing child: starting a short delay")
                        delay(50)
                        println("Failing child: throwing an exception")
                        throw failure
                    }
                }
            }
        }.exceptionOrNull()

        println("Parent: coroutineScope propagated the failure: $thrown")
        assertIs<IllegalStateException>(thrown)
        assertEquals("child failed", thrown.message)
    }

    @Test
    fun `swallowing cancellation lets code continue until its next suspension`() {
        val failure = IllegalStateException("child failed")

        val thrown = runCatching {
            runBlocking {
                coroutineScope {
                    launch {
                        try {
                            println("Sibling: starting a long delay")
                            delay(10_000)
                        } catch (cancelled: Exception) {
                            println("Sibling: accidentally swallowed $cancelled")
                        }

                        println("Sibling: ran code after swallowing cancellation")

                        try {
                            delay(1)
                        } catch (ex: CancellationException) {
                            println("Sibling: next suspension still sees cancellation: $ex")
                            throw ex
                        }
                    }
                    launch {
                        println("Failing child: starting a short delay")
                        delay(50)
                        println("Failing child: throwing an exception")
                        throw failure
                    }
                }
            }
        }.exceptionOrNull()

        println("Parent: coroutineScope propagated the failure: $thrown")
        assertIs<IllegalStateException>(thrown)
        assertEquals("child failed", thrown.message)
    }
}
