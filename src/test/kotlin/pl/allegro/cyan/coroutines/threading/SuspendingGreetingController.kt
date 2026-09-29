package pl.allegro.cyan.coroutines.threading

import pl.allegro.cyan.coroutines.demo.GreetingClient
import pl.allegro.cyan.coroutines.threading.currentCoroutineDispatcherName
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SuspendingGreetingController(
    private val client: GreetingClient,
    private val trace: ControllerThreadTrace,
) {

    @GetMapping("/greeting/from-controller")
    suspend fun greeting(): String {
        trace.recordDispatcher(currentCoroutineDispatcherName())
        trace.record("controller-start")
        return client.fetchGreeting("/greeting")
            .also { trace.record("controller-resumed") }
    }
}
