package pl.allegro.cyan.coroutines.threading

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import pl.allegro.cyan.coroutines.demo.GreetingClient
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScheduledGreetingClientTests {

    @Test
    fun `scheduled suspending method calls WebClient on Spring scheduler`() {
        val context = AnnotationConfigApplicationContext(ScheduledGreetingTestConfiguration::class.java)
        try {
            val trace = context.getBean(ScheduledThreadTrace::class.java)
            assertTrue(trace.completed.await(10, TimeUnit.SECONDS), "the scheduled request should complete")
            assertEquals("hello from WireMock", trace.greeting)

            val observations = trace.readObservations()
            val schedulerThread = observations.threadFor("scheduled-start")
            val subscribeThread = observations.threadFor("webclient-subscribe")
            val responseThread = observations.threadFor("webclient-response")
            val resumedThread = observations.threadFor("scheduled-resumed")

            assertSame(schedulerThread, subscribeThread)
            assertNotSame(Thread.currentThread(), schedulerThread)
            assertNotSame(schedulerThread, responseThread)
            assertSame(responseThread, resumedThread)
            println(observations.joinToString(separator = "\n") { "${it.phase}: ${it.thread.name}" })
        } finally {
            context.close()
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableScheduling
class ScheduledGreetingTestConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    fun wireMockServer(): WireMockServer =
        WireMockServer(wireMockConfig().dynamicPort()).apply {
            stubFor(
                get(urlEqualTo("/greeting"))
                    .willReturn(aResponse().withBody("hello from WireMock")),
            )
        }

    @Bean
    fun scheduledThreadTrace() = ScheduledThreadTrace()

    @Bean
    fun scheduledGreetingClient(
        server: WireMockServer,
        trace: ScheduledThreadTrace,
    ): GreetingClient {
        val webClient = WebClient.builder()
            .baseUrl(server.baseUrl())
            .filter { request, next ->
                next.exchange(request)
                    .doOnSubscribe { trace.record("webclient-subscribe") }
                    .doOnNext { trace.record("webclient-response") }
            }
            .build()
        return GreetingClient(webClient)
    }

    @Bean
    fun scheduledGreetingJob(
        client: GreetingClient,
        trace: ScheduledThreadTrace,
    ) = ScheduledGreetingJob(client, trace)
}

class ScheduledGreetingJob(
    private val client: GreetingClient,
    private val trace: ScheduledThreadTrace,
) {

    @Scheduled(fixedDelay = 60_000)
    suspend fun fetchGreeting() {
        logCoroutineDispatcher("scheduled method")
        trace.record("scheduled-start")
        trace.greeting = client.fetchGreeting("/greeting")
        trace.record("scheduled-resumed")
        trace.completed.countDown()
    }
}

class ScheduledThreadTrace {
    private val observations = ConcurrentLinkedQueue<ScheduledThreadObservation>()
    val completed = CountDownLatch(1)
    @Volatile
    var greeting: String? = null

    fun record(phase: String) {
        observations.add(ScheduledThreadObservation(phase, Thread.currentThread()))
    }

    fun readObservations(): List<ScheduledThreadObservation> {
        val result = observations.toList()
        assertEquals(
            listOf("scheduled-start", "webclient-subscribe", "webclient-response", "scheduled-resumed"),
            result.map { it.phase },
        )
        return result
    }
}

data class ScheduledThreadObservation(val phase: String, val thread: Thread)

private fun List<ScheduledThreadObservation>.threadFor(phase: String): Thread =
    first { it.phase == phase }.thread
