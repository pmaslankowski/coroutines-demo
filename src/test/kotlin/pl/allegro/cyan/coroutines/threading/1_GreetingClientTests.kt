package pl.allegro.cyan.coroutines.threading

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import pl.allegro.cyan.coroutines.demo.GreetingClient
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class `1_GreetingClientTests` {

    private lateinit var server: WireMockServer
    private lateinit var client: GreetingClient
    private lateinit var trace: ThreadTrace

    @BeforeEach
    fun setUp() {
        server = WireMockServer(wireMockConfig().dynamicPort())
        server.start()
        server.stubFor(
            get(urlEqualTo("/greeting"))
                .willReturn(aResponse().withBody("hello from WireMock")),
        )

        trace = ThreadTrace()
        val webClient = WebClient.builder()
            .baseUrl(server.baseUrl())
            .filter { request, next ->
                next.exchange(request)
                    .doOnSubscribe { trace.recordCurrentThread("webclient-subscribe") }
                    .doOnNext { trace.recordCurrentThread("webclient-response") }
            }
            .build()
        client = GreetingClient(webClient)
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `without a custom dispatcher coroutine stays on the caller thread`() = runBlocking {
        logCoroutineDispatcher("runBlocking WebClient test")
        trace.recordCurrentThread("caller-before")
        trace.recordCurrentThread("coroutine-start")
        val greeting = client.fetchGreeting("/greeting")
        trace.recordCurrentThread("coroutine-resumed")
        trace.recordCurrentThread("caller-after")

        assertEquals("hello from WireMock", greeting)
        trace.assertNoCustomDispatcherTransitions()
    }

    @Test
    fun `custom dispatcher and WebClient use observable threads`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "coroutine-demo-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        try {
            logCoroutineDispatcher("custom dispatcher test")
            trace.recordCurrentThread("caller-before")
            val greeting = withContext(dispatcher) {
                logCoroutineDispatcher("custom dispatcher WebClient call")
                trace.recordCurrentThread("coroutine-start")
                client.fetchGreeting("/greeting").also {
                    trace.recordCurrentThread("coroutine-resumed")
                }
            }
            trace.recordCurrentThread("caller-after")
            assertEquals("hello from WireMock", greeting)
            trace.assertCustomDispatcherTransitions()
        } finally {
            dispatcher.close()
        }
    }

    private class ThreadTrace {
        private val observations = ConcurrentLinkedQueue<ThreadObservation>()

        fun recordCurrentThread(phase: String) {
            observations.add(ThreadObservation(phase, Thread.currentThread()))
        }

        fun assertCustomDispatcherTransitions() {
            val trace = readTrace()
            val callerBefore = trace.threadFor("caller-before")
            val coroutineStart = trace.threadFor("coroutine-start")
            val responseSignal = trace.threadFor("webclient-response")
            val coroutineResumed = trace.threadFor("coroutine-resumed")
            val callerAfter = trace.threadFor("caller-after")

            assertSame(callerBefore, callerAfter, "withContext returns to the original caller context")
            assertSame(coroutineStart, coroutineResumed, "the suspended coroutine resumes on its dispatcher")
            assertEquals("coroutine-demo-worker", coroutineStart.name)
            assertNotSame(coroutineStart, responseSignal, "the WebClient signal is emitted outside the coroutine dispatcher")
            assertNotSame(callerBefore, responseSignal, "the HTTP response does not run on the blocking test thread")
        }

        fun assertNoCustomDispatcherTransitions() {
            val trace = readTrace()
            val callerBefore = trace.threadFor("caller-before")
            val coroutineStart = trace.threadFor("coroutine-start")
            val responseSignal = trace.threadFor("webclient-response")
            val coroutineResumed = trace.threadFor("coroutine-resumed")
            val callerAfter = trace.threadFor("caller-after")

            assertSame(callerBefore, coroutineStart, "without withContext, the coroutine starts on the caller thread")
            assertSame(callerBefore, coroutineResumed, "the coroutine resumes on the caller's runBlocking thread")
            assertSame(callerBefore, callerAfter)
            assertNotSame(callerBefore, responseSignal, "the WebClient signal still runs on its Reactor thread")
        }

        private fun readTrace(): List<ThreadObservation> {
            val trace = observations.toList()
            println(trace.joinToString(separator = "\n") { "${it.phase}: ${it.thread.name}" })

            assertEquals(
                listOf(
                    "caller-before",
                    "coroutine-start",
                    "webclient-subscribe",
                    "webclient-response",
                    "coroutine-resumed",
                    "caller-after",
                ),
                trace.map { it.phase },
            )
            return trace
        }

        private fun List<ThreadObservation>.threadFor(phase: String): Thread =
            first { it.phase == phase }.thread
    }

    private data class ThreadObservation(val phase: String, val thread: Thread)
}
