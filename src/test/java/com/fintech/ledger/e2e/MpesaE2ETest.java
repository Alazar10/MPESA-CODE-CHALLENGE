package com.fintech.ledger.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MpesaE2ETest {

    private static final String INST1 = "http://localhost:8081";
    private static final String INST2 = "http://localhost:8082";
    private static final String BANK = "http://localhost:9090";

    private final RestClient client1 = RestClient.builder().baseUrl(INST1).build();
    private final RestClient client2 = RestClient.builder().baseUrl(INST2).build();
    private final RestClient bankClient = RestClient.builder().baseUrl(BANK).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetState() {
        client1.post().uri("/mpesa/internal/reset").retrieve().toBodilessEntity();
        bankClient.post().uri("/bank/reset").retrieve().toBodilessEntity();
    }

    private JsonNode getWallet(String portUrl, String walletId) throws Exception {
        RestClient c = portUrl.contains("8081") ? client1 : client2;
        String res = c.get().uri("/mpesa/v1/wallets/" + walletId).retrieve().body(String.class);
        return mapper.readTree(res);
    }

    @Test
    @Order(1)
    void test_S1_1_ConcurrentTransfersUntilEmpty() throws Exception {
        int totalRequests = 200;
        ExecutorService pool = Executors.newFixedThreadPool(40);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        Random rand = new Random();

        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    String targetWallet = String.format("W%03d", 2 + rand.nextInt(50));
                    String url = (index % 2 == 0) ? INST1 : INST2;
                    RestClient c = (index % 2 == 0) ? client1 : client2;

                    Map<String, Object> req = Map.of(
                            "from_wallet", "W001",
                            "to_wallet", targetWallet,
                            "amount", 10000,
                            "reference", "tx-" + index
                    );

                    ResponseEntity<String> res = c.post()
                            .uri("/mpesa/v1/p2p/transfers")
                            .header("Idempotency-Key", "key-s11-" + index)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(req)
                            .retrieve()
                            .toEntity(String.class);

                    if (res.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    }
                } catch (HttpClientErrorException.UnprocessableEntity ex) {
                    failCount.incrementAndGet();
                } catch (Exception ex) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(100, successCount.get(), "Exactly 100 transfers must succeed");
        assertEquals(100, failCount.get(), "Exactly 100 transfers must fail with 422");

        JsonNode w001 = getWallet(INST1, "W001");
        assertEquals(0L, w001.get("balance").asLong(), "W001 balance must be exactly 0");
    }

    @Test
    @Order(2)
    void test_S1_2_ConcurrentIdempotentRequests() throws Exception {
        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        String commonKey = "idemp-key-s12-shared";
        Map<String, Object> payload = Map.of(
                "from_wallet", "W001",
                "to_wallet", "W002",
                "amount", 50000,
                "reference", "idempotency-test"
        );

        List<String> transferIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inProgressCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    RestClient c = (index % 2 == 0) ? client1 : client2;
                    ResponseEntity<String> res = c.post()
                            .uri("/mpesa/v1/p2p/transfers")
                            .header("Idempotency-Key", commonKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(payload)
                            .retrieve()
                            .toEntity(String.class);

                    if (res.getStatusCode() == HttpStatus.CREATED) {
                        JsonNode json = mapper.readTree(res.getBody());
                        transferIds.add(json.get("transfer_id").asText());
                    }
                } catch (HttpClientErrorException.Conflict ex) {
                    inProgressCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        ResponseEntity<String> postRes = client1.post()
                .uri("/mpesa/v1/p2p/transfers")
                .header("Idempotency-Key", commonKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toEntity(String.class);

        assertEquals(HttpStatus.CREATED, postRes.getStatusCode());
        String finalTxId = mapper.readTree(postRes.getBody()).get("transfer_id").asText();
        transferIds.add(finalTxId);

        String expectedId = transferIds.get(0);
        for (String id : transferIds) {
            assertEquals(expectedId, id, "Every 201 must carry the identical transfer_id");
        }

        JsonNode w001 = getWallet(INST1, "W001");
        assertEquals(950000L, w001.get("balance").asLong());
    }

    @Test
    @Order(3)
    void test_S1_3_CrossTransfersDeadlockFreedom() throws Exception {
        int total = 100;
        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(total);

        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < total; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    boolean direction = (index % 2 == 0);
                    String from = direction ? "W001" : "W002";
                    String to = direction ? "W002" : "W001";
                    RestClient c = (index % 2 == 0) ? client1 : client2;

                    Map<String, Object> req = Map.of(
                            "from_wallet", from,
                            "to_wallet", to,
                            "amount", 1000,
                            "reference", "cross-" + index
                    );

                    ResponseEntity<String> res = c.post()
                            .uri("/mpesa/v1/p2p/transfers")
                            .header("Idempotency-Key", "s13-key-" + index)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(req)
                            .retrieve()
                            .toEntity(String.class);

                    if (res.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Must finish within 10 seconds");
        pool.shutdown();

        assertEquals(100, successCount.get(), "All 100 cross-transfers must return 201");
        JsonNode w1 = getWallet(INST1, "W001");
        JsonNode w2 = getWallet(INST2, "W002");
        assertEquals(1000000L, w1.get("balance").asLong());
        assertEquals(1000000L, w2.get("balance").asLong());
    }

    @Test
    @Order(4)
    void test_S2_1_ConcurrentReversals() throws Exception {
        Map<String, Object> req = Map.of("from_wallet", "W001", "to_wallet", "W002", "amount", 50000, "reference", "rev-target");
        String res = client1.post()
                .uri("/mpesa/v1/p2p/transfers")
                .header("Idempotency-Key", "s21-initial-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(String.class);
        String transferId = mapper.readTree(res).get("transfer_id").asText();

        int concurrency = 30;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        AtomicInteger okCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    RestClient c = (index % 2 == 0) ? client1 : client2;
                    c.post()
                            .uri("/mpesa/v1/p2p/transfers/" + transferId + "/reversal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("reason", "fraud"))
                            .retrieve()
                            .toBodilessEntity();
                    okCount.incrementAndGet();
                } catch (HttpClientErrorException.Conflict ex) {
                    conflictCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, okCount.get(), "Exactly one reversal must return 200");
        assertEquals(29, conflictCount.get(), "29 reversals must return 409 ALREADY_REVERSED");

        JsonNode w1 = getWallet(INST1, "W001");
        JsonNode w2 = getWallet(INST2, "W002");
        assertEquals(1000000L, w1.get("balance").asLong());
        assertEquals(1000000L, w2.get("balance").asLong());
    }

    @Test
    @Order(5)
    void test_S2_3_ReversalToFrozenWallet() throws Exception {
        Map<String, Object> req = Map.of("from_wallet", "W030", "to_wallet", "W031", "amount", 5000, "reference", "rev-frozen");
        String res = client1.post()
                .uri("/mpesa/v1/p2p/transfers")
                .header("Idempotency-Key", "s23-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(String.class);
        String transferId = mapper.readTree(res).get("transfer_id").asText();

        client2.put()
                .uri("/mpesa/v1/wallets/W030/status")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "FROZEN", "reason", "review"))
                .retrieve()
                .toBodilessEntity();

        ResponseEntity<String> revRes = client1.post()
                .uri("/mpesa/v1/p2p/transfers/" + transferId + "/reversal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "chargeback"))
                .retrieve()
                .toEntity(String.class);

        assertEquals(HttpStatus.OK, revRes.getStatusCode());
        JsonNode w030 = getWallet(INST1, "W030");
        assertEquals(1000000L, w030.get("balance").asLong(), "W030 must be credited back to 1,000,000");
        assertEquals("FROZEN", w030.get("status").asText());
    }
}