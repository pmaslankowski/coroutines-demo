package pl.allegro.cyan.coroutines.threading

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import pl.allegro.cyan.coroutines.demo.DemoApplication
import pl.allegro.cyan.coroutines.demo.GreetingClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@SpringBootTest(
    classes = [DemoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(SuspendingControllerTestConfiguration::class)
class SuspendingControllerTests {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var trace: ControllerThreadTrace

    @Test
    fun `suspending controller calls WebClient on server threads`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/greeting/from-controller"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(HttpStatus.OK.value(), response.statusCode())
        assertEquals("hello from WireMock", response.body())

        val dispatcherName = trace.readDispatcherName()
        println("controller coroutine dispatcher: $dispatcherName")
        val observations = trace.read()
        val testThread = Thread.currentThread()
        val controllerThread = observations.threadFor("controller-start")
        val subscribeThread = observations.threadFor("webclient-subscribe")
        val responseThread = observations.threadFor("webclient-response")
        val resumedThread = observations.threadFor("controller-resumed")

        assertNotSame(testThread, controllerThread)
        assertSame(controllerThread, subscribeThread)
        assertNotSame(controllerThread, responseThread)
        assertSame(responseThread, resumedThread)
        println(observations.joinToString(separator = "\n") { "${it.phase}: ${it.thread.name}" })
    }
}

@TestConfiguration(proxyBeanMethods = false)
class SuspendingControllerTestConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    fun wireMockServer(): WireMockServer =
        WireMockServer(wireMockConfig().dynamicPort()).apply {
            stubFor(
                get(urlEqualTo("/greeting"))
                    .willReturn(aResponse().withBody("hello from WireMock")),
            )
        }

    @Bean
    fun controllerThreadTrace() = ControllerThreadTrace()

    @Bean
    fun controllerGreetingClient(
        server: WireMockServer,
        trace: ControllerThreadTrace,
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
    fun suspendingGreetingController(client: GreetingClient, trace: ControllerThreadTrace) =
        SuspendingGreetingController(client, trace)
}

class ControllerThreadTrace {
    private val observations = ConcurrentLinkedQueue<ControllerThreadObservation>()
    @Volatile
    private var dispatcherName: String? = null

    fun record(phase: String) {
        observations.add(ControllerThreadObservation(phase, Thread.currentThread()))
    }

    fun recordDispatcher(name: String) {
        dispatcherName = name
    }

    fun readDispatcherName(): String = checkNotNull(dispatcherName)

    fun read(): List<ControllerThreadObservation> {
        val result = observations.toList()
        assertEquals(
            listOf("controller-start", "webclient-subscribe", "webclient-response", "controller-resumed"),
            result.map { it.phase },
        )
        return result
    }
}

data class ControllerThreadObservation(val phase: String, val thread: Thread)

private fun List<ControllerThreadObservation>.threadFor(phase: String): Thread =
    first { it.phase == phase }.thread
