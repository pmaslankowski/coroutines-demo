package pl.allegro.cyan.coroutines.demo

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.web.reactive.function.client.WebClient

class GreetingClient(
    private val webClient: WebClient,
) {
    suspend fun fetchGreeting(path: String): String =
        webClient.get()
            .uri(path)
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingle()
}
