package com.example.todoapp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

@Timeout(15)
class HolidayApiTests {

    private HttpServer server;
    private ExecutorService executor;
    private final CountDownLatch releaseResponse = new CountDownLatch(1);

    @AfterEach
    void stopServer() {
        releaseResponse.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void returnsHolidaysWithoutUnavailableHeader() throws Exception {
        URI uri = startServer(exchange -> respond(exchange, 200,
                "{\"2026-01-01\":\"元日\",\"2026-02-11\":\"建国記念の日\"}"));

        mockMvc(clientFor(uri)).perform(get("/api/holidays"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"2026-01-01\":\"元日\",\"2026-02-11\":\"建国記念の日\"}"))
                .andExpect(header().doesNotExist("X-Holidays-Unavailable"));
    }

    @Test
    void preservesDateFiltering() throws Exception {
        URI uri = startServer(exchange -> respond(exchange, 200,
                "{\"2026-01-01\":\"元日\",\"2026-02-11\":\"建国記念の日\"}"));

        mockMvc(clientFor(uri)).perform(get("/api/holidays")
                        .param("from", "2026-02-01").param("to", "2026-02-28"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"2026-02-11\":\"建国記念の日\"}"))
                .andExpect(header().doesNotExist("X-Holidays-Unavailable"));
    }

    @Test
    void successfulEmptyResponseDoesNotIndicateFailure() throws Exception {
        URI uri = startServer(exchange -> respond(exchange, 200, "{}"));

        mockMvc(clientFor(uri)).perform(get("/api/holidays"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"))
                .andExpect(header().doesNotExist("X-Holidays-Unavailable"));
    }

    @Test
    void unavailableHostnameReturnsEmptyObjectAndHeader() throws Exception {
        expectUnavailable(mockMvc(new HolidayClient()));
    }

    @Test
    void refusedConnectionReturnsEmptyObjectAndHeader() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        expectUnavailable(mockMvc(clientFor(URI.create("http://127.0.0.1:" + unusedPort))));
    }

    @Test
    void slowResponseTimesOutAndReturnsEmptyObjectAndHeader() throws Exception {
        URI uri = startServer(exchange -> {
            try {
                releaseResponse.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        long start = System.nanoTime();

        expectUnavailable(mockMvc(clientFor(uri)));

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isBetween(Duration.ofSeconds(4), Duration.ofSeconds(10));
    }

    @Test
    void upstreamErrorReturnsEmptyObjectAndHeader() throws Exception {
        URI uri = startServer(exchange -> respond(exchange, 503, "{}"));

        expectUnavailable(mockMvc(clientFor(uri)));
    }

    @Test
    void missingBodyReturnsEmptyObjectAndHeader() throws Exception {
        URI uri = startServer(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        expectUnavailable(mockMvc(clientFor(uri)));
    }

    private void expectUnavailable(MockMvc mvc) throws Exception {
        mvc.perform(get("/api/holidays").param("from", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{}"))
                .andExpect(header().string("X-Holidays-Unavailable", "true"));
    }

    private MockMvc mockMvc(HolidayClient client) {
        return MockMvcBuilders.standaloneSetup(new HolidayApiController(client)).build();
    }

    private HolidayClient clientFor(URI uri) {
        // Keep the production timeouts and redirect only test requests to a local server.
        return new HolidayClient(RestClient.builder().requestInterceptor((request, body, execution) ->
                execution.execute(new HttpRequestWrapper(request) {
                    @Override
                    public URI getURI() {
                        return uri;
                    }
                }, body)));
    }

    private URI startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/date.json");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
