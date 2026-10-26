# DESIGN.md

## 1. Every Wait
Every place in the codebase where a thread can wait is strictly bounded:

1. **Tomcat Inbound Thread Pool**: Capped at `server.tomcat.threads.max=50` per instance. If all 50 threads are occupied, incoming requests wait in the OS accept queue (bounded by `accept-count`, default 100) before being rejected.
2. **HikariCP Connection Pool**: Bounded by `hikari.maximum-pool-size=10` and `hikari.connection-timeout=2000` ms. A thread waiting for a database connection fails with `503 SERVICE_UNAVAILABLE` after exactly 2,000 ms.
3. **Pessimistic Database Row Locks**: In `P2P` transfers and reversals, wallets are locked via `SELECT ... FOR UPDATE`. Deadlocks are eliminated by sorting wallet IDs lexicographically prior to lock acquisition. Lock wait time is bounded by PostgreSQL's `lock_timeout` (configured at 1,500 ms), aborting with a clean error before Hikari connection timeout triggers.
4. **External Bank HTTP Call**: Handled out-of-transaction via an asynchronous worker thread pool (`CompletableFuture` / `bankSubmitExecutor` capped at 20 threads). The HTTP client wait time is bounded by an 8,000 ms read timeout and a 2,000 ms connect timeout. Threads never block database connections while waiting on the bank.
5. **Scheduled Reconciler**: Runs on a single background executor thread every 5 seconds. It queries `SELECT ... FOR UPDATE SKIP LOCKED`, bounding the wait time to 0 ms under lock contention by skipping locked rows immediately.

---

## 2. Database Restart
If PostgreSQL restarts while a payout is between reservation and the bank's answer:

1. **Reservation Phase (Pre-Crash)**: The initial funds reservation (`reserved += amount`, `available -= amount`) and the payout record insertion (`status = 'PENDING'`) were already committed inside a short (<5 ms) database transaction prior to the bank call. If PostgreSQL crashed before this commit finished, the transaction rolled back entirely, the client received a connection error/503, and zero money was reserved.
2. **Bank Call in Flight**: The bank request is executed outside the database transaction. If the bank processes the payout and issues a callback while PostgreSQL is offline, the callback HTTP request receives a connection error (`503 SERVICE_UNAVAILABLE`). Per Section 4.3, the bank retries callbacks up to 5 times every 3 seconds.
3. **Database Recovery**: Once PostgreSQL restarts, Hikari re-establishes connections (`connection-test-query = SELECT 1`).
4. **Reconciliation & Finalization**: The `@Scheduled` background reconciler discovers the stranded `PENDING` or `SUBMITTED` payout via `created_at < NOW() - INTERVAL '10 seconds'` using `SKIP LOCKED`. It queries `GET /bank/payouts/{id}`. If the bank processed the payout (`SUCCEEDED`), a new transaction deducts `balance` and clears `reserved`. If the bank rejected or never received it (`FAILED`/`REJECTED`/`NOT_FOUND`), the reservation is released. Money remains exact and balanced.

---

## 3. What You Left Out
APIs 5.6 (B2C Disbursements) and 5.7 (Statement Pagination) were omitted to prioritize correctness and concurrency resilience on the core ledger write paths (5.1–5.5).

- **5.6 B2C Disbursements**:
  - *Hardest Part*: Executing 5,000 independent transfers within 60 seconds while keeping standard P2P transfers under 300 ms p95 without Hikari pool starvation.
  - *Design Solution*: The batch would be chunked into workers processing 50 rows per short transaction. Each chunk acquires short row locks on the merchant wallet and destination wallets, updates balances, and commits immediately, preventing any long-held lock on `M001` from blocking P2P transfers.
- **5.7 Statements with Cursor**:
  - *Hardest Part*: Achieving repeatable snapshot isolation across cursor-based pagination while 500 concurrent transfers write to the same wallet, ensuring no entry is missed or duplicated and query SLA stays <150 ms across 1,000,000 records.
  - *Design Solution*: Model an append-only `ledger_entries` table with a monotonically increasing `entry_id` (using a sequence or Snowflake ID) and a composite index `(wallet_id, entry_id DESC)`. The opaque cursor would encode `(created_at, entry_id)`, guaranteeing linear cursor iteration without table scans or lock contention.

---

## Decisions, Isolation Level & Idempotency

- **Transaction Isolation Level**: Standard `READ COMMITTED` across all transactions. Concurrency and consistency are guaranteed via explicit pessimistic row locks (`SELECT ... FOR UPDATE`) with deterministic ordering, avoiding `SERIALIZABLE` retry overhead while guaranteeing zero dirty or lost updates.
- **Concurrent Idempotency Policy**: Returns `409 REQUEST_IN_PROGRESS` if a duplicate key arrives while the original request is executing. If identical and finished, the cached response code and body are returned. If the payload hash differs, `409 IDEMPOTENCY_CONFLICT` is returned.
- **Network / Database Decoupling**: Database connections are never held across external HTTP boundaries.