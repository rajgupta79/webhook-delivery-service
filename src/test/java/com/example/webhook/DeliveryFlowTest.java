package com.example.webhook;

import com.example.webhook.api.DeadLetterController;
import com.example.webhook.domain.DeliveryAttempt;
import com.example.webhook.domain.Endpoint;
import com.example.webhook.repo.DeliveryAttemptRepository;
import com.example.webhook.repo.EndpointRepository;
import com.example.webhook.repo.EventRepository;
import com.example.webhook.service.IngestService;
import com.example.webhook.service.SecretGenerator;
import com.example.webhook.service.SignatureVerifier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DeliveryFlowTest {

    // ---------- real Postgres and Redis, started once for this test class ----------
    static final String PG_URL;
    static final String REDIS_HOST;
    static final int REDIS_PORT;

    @SuppressWarnings({"rawtypes", "unchecked"})   // raw type on purpose: works with any Testcontainers version
    private static void startContainers() { }

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));   // Postgres rejects "Asia/Calcutta"

        GenericContainer pg = new GenericContainer(DockerImageName.parse("postgres:16"));
        pg.addEnv("POSTGRES_DB", "webhooks");
        pg.addEnv("POSTGRES_USER", "app");
        pg.addEnv("POSTGRES_PASSWORD", "app");
        pg.addExposedPort(5432);
        pg.setWaitStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
        pg.start();
        PG_URL = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/webhooks";

        GenericContainer redis = new GenericContainer(DockerImageName.parse("redis:7"));
        redis.addExposedPort(6379);
        redis.start();
        REDIS_HOST = redis.getHost();
        REDIS_PORT = redis.getMappedPort(6379);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG_URL);
        r.add("spring.datasource.username", () -> "app");
        r.add("spring.datasource.password", () -> "app");
        r.add("spring.data.redis.host", () -> REDIS_HOST);
        r.add("spring.data.redis.port", () -> REDIS_PORT);
    }

    // ---------- a fake customer server we fully control ----------
    static class FakeCustomer implements AutoCloseable {
        record Received(String body, String signature, String eventId) {}

        final HttpServer server;
        final Deque<Integer> script = new ConcurrentLinkedDeque<>();   // statuses to answer with, in order
        final List<Received> received = new CopyOnWriteArrayList<>();

        FakeCustomer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/hook", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(new Received(body,
                        ex.getRequestHeaders().getFirst("X-Webhook-Signature"),
                        ex.getRequestHeaders().getFirst("X-Event-Id")));
                Integer next = script.pollFirst();
                ex.sendResponseHeaders(next != null ? next : 200, -1);   // empty body
                ex.close();
            });
            server.start();
        }

        String url() { return "http://localhost:" + server.getAddress().getPort() + "/hook"; }

        @Override public void close() { server.stop(0); }
    }

    @Autowired IngestService ingest;
    @Autowired EndpointRepository endpoints;
    @Autowired EventRepository events;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired DeadLetterController deadLetters;

    private Endpoint newEndpoint(String url) {
        Endpoint e = new Endpoint();
        e.setUrl(url);
        e.setSecret(SecretGenerator.newSecret());
        return endpoints.save(e);
    }

    private void awaitStatus(Long eventId, String status) {
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() ->
                        assertEquals(status, events.findById(eventId).orElseThrow().getStatus()));
    }

    // ---------- the tests ----------

    @Test
    void deliversAndSignsTheWebhook() throws Exception {
        try (FakeCustomer customer = new FakeCustomer()) {
            Endpoint ep = newEndpoint(customer.url());
            Long id = ingest.ingest(ep.getId(), "t1", "{\"hello\":\"world\"}");
            awaitStatus(id, "DELIVERED");

            assertEquals(1, customer.received.size());
            FakeCustomer.Received r = customer.received.get(0);
            assertEquals("{\"hello\":\"world\"}", r.body());
            assertEquals(id.toString(), r.eventId());
            assertTrue(SignatureVerifier.verify(r.signature(), r.body(), ep.getSecret(),
                    300, Instant.now().getEpochSecond()));
        }
    }

    @Test
    void sameIdempotencyKeyIsDeliveredOnce() throws Exception {
        try (FakeCustomer customer = new FakeCustomer()) {
            Endpoint ep = newEndpoint(customer.url());
            Long first = ingest.ingest(ep.getId(), "dup", "{}");
            Long second = ingest.ingest(ep.getId(), "dup", "{}");
            assertEquals(first, second);

            awaitStatus(first, "DELIVERED");
            Thread.sleep(1_500);                       // give a wrongly queued duplicate time to show up
            assertEquals(1, customer.received.size());
        }
    }

    @Test
    void retriesTransientFailuresThenDelivers() throws Exception {
        try (FakeCustomer customer = new FakeCustomer()) {
            customer.script.add(500);
            customer.script.add(500);                  // then the default answer: 200
            Endpoint ep = newEndpoint(customer.url());
            Long id = ingest.ingest(ep.getId(), "retry", "{}");

            awaitStatus(id, "DELIVERED");
            List<DeliveryAttempt> tries = attempts.findByEventIdOrderById(id);
            assertEquals(List.of(500, 500, 200), tries.stream().map(DeliveryAttempt::getStatusCode).toList());
        }
    }

    @Test
    void permanentFailureGoesDeadAndCanBeReplayed() throws Exception {
        try (FakeCustomer customer = new FakeCustomer()) {
            customer.script.add(404);
            Endpoint ep = newEndpoint(customer.url());
            Long id = ingest.ingest(ep.getId(), "dead", "{}");

            awaitStatus(id, "DEAD");
            assertTrue(events.findById(id).orElseThrow().getDeadReason().contains("404"));
            assertEquals(1, customer.received.size());          // a permanent error is not retried

            deadLetters.replay(id);                              // "the customer fixed their server"
            awaitStatus(id, "DELIVERED");
            assertEquals(1, events.findById(id).orElseThrow().getReplayCount());
            assertEquals(2, attempts.findByEventIdOrderById(id).size());
        }
    }
}