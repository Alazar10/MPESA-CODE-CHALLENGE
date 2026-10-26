package com.fintech.ledger.fakebank;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
@EnableAutoConfiguration(excludeName = {
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "org.springframework.boot.data.jpa.autoconfigure.HibernateJpaAutoConfiguration"
})
@RestController
public class FakeBankApplication {

    public static void main(String[] args) {
        System.setProperty("server.port", "9090");
        SpringApplication.run(FakeBankApplication.class, args);
    }

    private final AtomicLong totalActuallyPaid = new AtomicLong(0);
    private final ConcurrentMap<String, PayoutRecord> payouts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService callbackScheduler = Executors.newScheduledThreadPool(10);
    private final RestClient restClient = RestClient.builder().build();
    private final Random random = new Random();

    private volatile double acceptedRate = 0.80;
    private volatile double rejectedRate = 0.08;
    private volatile double noResponseRate = 0.12;
    private volatile double duplicateCallbackRate = 0.30;
    private volatile double acceptedFailRate = 0.05;

    public record PayoutRecord(String payoutId, String bankAccount, long amount, String status, String bankRef) {}
    public record PayoutRequest(@JsonProperty("payout_id") String payoutId, @JsonProperty("bank_account") String bankAccount, long amount) {}
    public record BankConfig(Double acceptedRate, Double rejectedRate, Double noResponseRate, Double duplicateCallbackRate, Double acceptedFailRate) {}
    public record CallbackPayload(@JsonProperty("event_id") String eventId, @JsonProperty("payout_id") String payoutId, @JsonProperty("bank_ref") String bankRef, String status) {}

    @PostMapping("/bank/config")
    public ResponseEntity<Void> updateConfig(@RequestBody BankConfig cfg) {
        if (cfg.acceptedRate() != null) this.acceptedRate = cfg.acceptedRate();
        if (cfg.rejectedRate() != null) this.rejectedRate = cfg.rejectedRate();
        if (cfg.noResponseRate() != null) this.noResponseRate = cfg.noResponseRate();
        if (cfg.duplicateCallbackRate() != null) this.duplicateCallbackRate = cfg.duplicateCallbackRate();
        if (cfg.acceptedFailRate() != null) this.acceptedFailRate = cfg.acceptedFailRate();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/bank/reconciliation/total")
    public Map<String, Long> getTotalPaid() {
        return Map.of("total_paid", totalActuallyPaid.get());
    }

    @PostMapping("/bank/reset")
    public ResponseEntity<Void> resetBank() {
        payouts.clear();
        totalActuallyPaid.set(0);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bank/payouts")
    public ResponseEntity<Map<String, String>> submitPayout(@RequestBody PayoutRequest req) throws InterruptedException {
        PayoutRecord existing = payouts.get(req.payoutId());
        if (existing != null) {
            if (existing.amount() != req.amount()) {
                return ResponseEntity.badRequest().body(Map.of("result", "REJECTED", "reason", "AMOUNT_MISMATCH"));
            }
            if ("REJECTED".equals(existing.status())) {
                return ResponseEntity.ok(Map.of("result", "REJECTED", "reason", "BENEFICIARY_ACCOUNT_INVALID"));
            }
            return ResponseEntity.ok(Map.of("result", "ACCEPTED", "bank_ref", existing.bankRef()));
        }

        double roll = random.nextDouble();
        if (roll < noResponseRate) {
            TimeUnit.SECONDS.sleep(60);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }

        int delay = 2000 + random.nextInt(4001);
        Thread.sleep(delay);

        if (roll < noResponseRate + rejectedRate) {
            payouts.put(req.payoutId(), new PayoutRecord(req.payoutId(), req.bankAccount(), req.amount(), "REJECTED", null));
            return ResponseEntity.ok(Map.of("result", "REJECTED", "reason", "BENEFICIARY_ACCOUNT_INVALID"));
        }

        String bankRef = "BR" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        payouts.put(req.payoutId(), new PayoutRecord(req.payoutId(), req.bankAccount(), req.amount(), "IN_PROGRESS", bankRef));

        int callbackDelay = 1 + random.nextInt(10);
        boolean succeeds = random.nextDouble() >= acceptedFailRate;
        String finalStatus = succeeds ? "SUCCEEDED" : "FAILED";

        callbackScheduler.schedule(() -> {
            if (succeeds) {
                totalActuallyPaid.addAndGet(req.amount());
            }
            payouts.put(req.payoutId(), new PayoutRecord(req.payoutId(), req.bankAccount(), req.amount(), finalStatus, bankRef));
            dispatchCallback(req.payoutId(), bankRef, finalStatus);
        }, callbackDelay, TimeUnit.SECONDS);

        return ResponseEntity.ok(Map.of("result", "ACCEPTED", "bank_ref", bankRef));
    }

    @GetMapping("/bank/payouts/{payoutId}")
    public ResponseEntity<Map<String, String>> getPayout(@PathVariable("payoutId") String payoutId) throws InterruptedException {
        Thread.sleep(1000 + random.nextInt(2001));
        PayoutRecord rec = payouts.get(payoutId);
        if (rec == null) {
            return ResponseEntity.ok(Map.of("status", "NOT_FOUND"));
        }
        return ResponseEntity.ok(Map.of("status", rec.status()));
    }

    private void dispatchCallback(String payoutId, String bankRef, String status) {
        String eventId = UUID.randomUUID().toString();
        CallbackPayload payload = new CallbackPayload(eventId, payoutId, bankRef, status);

        int copies = (random.nextDouble() < duplicateCallbackRate) ? (random.nextBoolean() ? 2 : 3) : 1;
        for (int i = 0; i < copies; i++) {
            CompletableFuture.runAsync(() -> sendWithRetry(payload));
        }
    }

    private void sendWithRetry(CallbackPayload payload) {
        int[] targetPorts = {8081, 8082};
        int port = targetPorts[random.nextInt(targetPorts.length)];
        String url = "http://localhost:" + port + "/mpesa/v1/callbacks/bank";

        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                ResponseEntity<Void> res = restClient.post()
                        .uri(url)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                if (res.getStatusCode().is2xxSuccessful()) {
                    return;
                }
            } catch (Exception ignored) {}
            try {
                TimeUnit.SECONDS.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}