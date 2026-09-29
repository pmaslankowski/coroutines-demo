# Kotlin coroutines demo

This project uses executable tests to explore coroutine behavior alongside a real Spring `WebClient` request. The HTTP integration test talks only to a local WireMock server; it does not need an external service.

## Run the examples

```bash
./gradlew test
```

The tests in `StructuredConcurrencyTests` demonstrate structured-concurrency behavior:

- `coroutineScope` does not return until its children finish, even after the scope block itself has reached its end.
- A child failure cancels its sibling and is propagated from the scope.
- Swallowing a `CancellationException` allows immediate non-suspending code to run, but the next suspension still observes the cancelled job.

The structured-concurrency examples use delays to make child work visible: one shows the scope waiting for a child, another shows a failing child cancelling its sibling during a long delay, and a third demonstrates why cancellation exceptions should not be swallowed.

## Following the HTTP thread trace

The threading tests and their supporting fixtures are grouped under `pl.allegro.cyan.coroutines.threading`.

`GreetingClientTests` makes the same WireMock request in two ways: one test wraps the call in `withContext` using a named, single-thread dispatcher, and the other calls it directly from `runBlocking`. The service itself only handles the WebClient request; the tests own the coroutine context and record each phase:

| Phase | What is running |
| --- | --- |
| `caller-before` | The test coroutine before calling the service |
| `coroutine-start` | The service call, after `withContext` dispatches it in the custom-dispatcher test |
| `webclient-subscribe` | Subscription to the WebClient publisher |
| `webclient-response` | Reactor's response signal callback |
| `coroutine-resumed` | The coroutine after `awaitSingle` receives the response |
| `caller-after` | The test coroutine after the service returns |

With the custom dispatcher, the coroutine starts and resumes on its named worker and `withContext` returns to the caller's original thread. Without it, coroutine code starts and resumes on the `runBlocking` caller thread. In both tests the WebClient response signal runs on a different thread. Assertions check these relationships instead of assuming a particular Reactor thread name; the printed names are useful observations for the current machine and runtime.

`ScheduledGreetingClientTests` adds a third entry point: a real Spring `@Scheduled` suspending method. It starts the request from Spring's scheduler rather than from `runBlocking`, then records the scheduler, WebClient, response-signal, and coroutine-resumption threads. In this setup the coroutine starts and subscribes on Spring's scheduler thread, then resumes on the Reactor response-signal thread. The test starts a small scheduling-enabled application context and waits for the scheduled request to complete.

`SuspendingControllerTests` sends a real HTTP request to a suspending Spring MVC controller. The trace is recorded inside the server-side controller and WebClient filter, so the test's blocking HTTP client thread is distinct from the server execution threads.

Each coroutine-focused test logs the active continuation interceptor (dispatcher) from inside the coroutine, alongside the recorded thread phases. These traces sample application code and one Reactor signal; they are not a complete inventory of WebClient, Reactor Netty, or WireMock's internal threads. In particular, `awaitSingle` suspends the coroutine without blocking a thread, and the response signal's thread is not necessarily the thread on which the coroutine continues.
