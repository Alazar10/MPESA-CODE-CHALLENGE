# FAILURE_MODES.md

## 5.1 P2P Transfers
- Deadlock on concurrent cross-transfers (W001 -> W002 and W002 -> W001 simultaneously): Prevented by ordering wallet lock acquisition lexicographically by `wallet_id` before running `SELECT ... FOR UPDATE`.
- Debit commits without credit: Prevented by executing both balance mutations inside a single ACID database transaction.
- Negative available balance during concurrent race: Prevented by pessimistic row locking and verifying `available >= amount` before updating.
- Thread exhaustion / Hikari starvation: Prevented by eliminating all external I/O from the transaction and keeping execution under 5 ms.
- Reusing idempotency key with different payload: Prevented by storing a SHA-256 hash of the request body and returning `409 IDEMPOTENCY_CONFLICT` on mismatch.
- Concurrent duplicate requests with the same idempotency key: Prevented by atomic insertion of status `'IN_PROGRESS'` guarded by a primary key constraint, returning `409 REQUEST_IN_PROGRESS`.

## 5.2 Transfer Reversals
- Reversing a transfer twice concurrently: Prevented by atomic conditional update `UPDATE transfers SET status = 'REVERSED' WHERE transfer_id = ? AND status = 'COMPLETED'` checking updated row count.
- Reversal causes recipient balance to go negative: Prevented by locking the original recipient wallet and checking `available >= amount`.
- Reversing an invalid, unknown, or pending transfer: Prevented by explicit status validation returning `404 TRANSFER_NOT_FOUND` or `422 NOT_REVERSIBLE`.
- Reversal blocked by frozen sender: Handled; crediting a frozen wallet is explicitly permitted.

## 5.3 Wallets Snapshot & Status
- Balance snapshot inconsistency during concurrent transfer: Prevented by selecting consistent fields within a single atomic query; `available = balance - reserved` enforced by check constraints.
- In-flight debit commits after wallet freeze returns: Prevented by acquiring `FOR UPDATE` on the wallet during status change and checking `status == 'ACTIVE'` inside the transfer transaction.

## 5.4 Payouts
- Hikari pool starvation during slow bank latency (2-60 seconds): Prevented by reserving funds in Transaction 1, committing, executing bank HTTP call asynchronously outside the transaction, and updating state in Transaction 2.
- Bank times out (504 or >8 seconds): Outcome treated as `UNKNOWN`; status remains `SUBMITTED`, reservation remains held, and finality is delegated to callbacks or reconciler.
- Instance crash loses in-flight payout: Prevented by background reconciliation job polling stale payouts via `FOR UPDATE SKIP LOCKED` and querying `GET /bank/payouts/{id}`.
- Payout double-paid by bank: Prevented by sending idempotent `payout_id` to the bank.

## 5.5 Bank Callbacks
- Duplicate callbacks concurrently applied: Prevented by recording callback `event_id` in a dedicated table with a primary key constraint.
- Callback arrives before submit returns: Handled by committing the reservation before initiating the bank call.
- Conflicting callback arriving for final status: Prevented by making `SUCCEEDED` and `FAILED` terminal; contradictory callbacks leave state unchanged, record `conflict = true`, and return 200.

## 5.6 B2C Disbursements
- Not handled (omitted in favor of core write paths).

## 5.7 Statement Cursor Pagination
- Not handled (omitted in favor of core write paths).