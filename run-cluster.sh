#!/usr/bin/env bash
set -e

APP_PIDS=()

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    for pid in "${APP_PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
    for pid in "${APP_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done
    exit "$status"
}
trap cleanup EXIT INT TERM
trap 'exit 130' INT
trap 'exit 143' TERM

echo "1. Checking/Starting PostgreSQL container..."
docker rm -f mpesa-postgres 2>/dev/null || true

docker run -d \
  --name mpesa-postgres \
  -e POSTGRES_DB=mpesa_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgrespassword \
  -p 5432:5432 \
  -v mpesa_pgdata:/var/lib/postgresql/data \
  postgres:16-alpine

echo "Waiting for mpesa_db to be fully initialized..."
database_ready=false
for i in {1..60}; do
    if docker exec mpesa-postgres psql -U postgres -d mpesa_db -c "SELECT 1" > /dev/null 2>&1; then
        database_ready=true
        break
    fi
    sleep 1
done
if [[ "$database_ready" != true ]]; then
    echo "PostgreSQL did not become ready within 60 seconds."
    docker logs mpesa-postgres
    exit 1
fi

echo "Applying Schema & Seed Function..."
docker exec -i mpesa-postgres psql -U postgres -d mpesa_db << 'EOF'
CREATE TABLE IF NOT EXISTS wallets (
    wallet_id VARCHAR(32) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT chk_available_non_negative CHECK (balance >= reserved)
);

CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key VARCHAR(64) PRIMARY KEY,
    endpoint VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_code INT,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transfers (
    transfer_id VARCHAR(64) PRIMARY KEY,
    from_wallet VARCHAR(32) NOT NULL REFERENCES wallets(wallet_id),
    to_wallet VARCHAR(32) NOT NULL REFERENCES wallets(wallet_id),
    amount BIGINT NOT NULL,
    reference VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS reversals (
    reversal_id VARCHAR(64) PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL UNIQUE REFERENCES transfers(transfer_id),
    amount BIGINT NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS payouts (
    payout_id VARCHAR(64) PRIMARY KEY,
    wallet_id VARCHAR(32) NOT NULL REFERENCES wallets(wallet_id),
    bank_account VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    bank_ref VARCHAR(64),
    conflict BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS bank_callback_events (
    event_id VARCHAR(64) PRIMARY KEY,
    payout_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE OR REPLACE FUNCTION reset_mpesa_state() RETURNS void AS $$
BEGIN
    TRUNCATE TABLE bank_callback_events CASCADE;
    TRUNCATE TABLE reversals CASCADE;
    TRUNCATE TABLE transfers CASCADE;
    TRUNCATE TABLE payouts CASCADE;
    TRUNCATE TABLE idempotency_records CASCADE;
    TRUNCATE TABLE wallets CASCADE;

    INSERT INTO wallets (wallet_id, status, balance, reserved)
    SELECT 'W' || LPAD(i::text, 3, '0'), 'ACTIVE', 1000000, 0
    FROM generate_series(1, 100) AS i;

    INSERT INTO wallets (wallet_id, status, balance, reserved)
    SELECT 'W' || LPAD(i::text, 3, '0'), 'FROZEN', 1000000, 0
    FROM generate_series(101, 105) AS i;

    INSERT INTO wallets (wallet_id, status, balance, reserved)
    VALUES ('M001', 'ACTIVE', 50000000, 0);
END;
$$ LANGUAGE plpgsql;

SELECT reset_mpesa_state();
EOF

echo "Packaging jar (skipping tests during build)..."
./mvnw clean package -DskipTests

echo "2. Starting Fake Bank on :9090..."
SERVER_PORT=9090 java -cp target/classes:$(./mvnw dependency:build-classpath | grep -v '\[INFO\]' | tr '\n' ':') \
     com.fintech.ledger.fakebank.FakeBankApplication > fakebank.log 2>&1 &
APP_PIDS+=("$!")

echo "3. Starting Instance 1 on :8081..."
SERVER_PORT=8081 java -jar target/ledger-0.0.1-SNAPSHOT.jar > inst1.log 2>&1 &
APP_PIDS+=("$!")

echo "4. Starting Instance 2 on :8082..."
SERVER_PORT=8082 java -jar target/ledger-0.0.1-SNAPSHOT.jar > inst2.log 2>&1 &
APP_PIDS+=("$!")

echo "Waiting for all services to become responsive..."
services_ready=false
for i in {1..60}; do
    if curl -fsS http://localhost:9090/bank/reconciliation/total > /dev/null 2>&1 && \
       curl -fsS http://localhost:8081/mpesa/v1/wallets/W001 > /dev/null 2>&1 && \
       curl -fsS http://localhost:8082/mpesa/v1/wallets/W001 > /dev/null 2>&1; then
        services_ready=true
        echo "All instances are ready!"
        break
    fi
    for pid in "${APP_PIDS[@]}"; do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "A service exited before the cluster became ready."
            tail -n 40 fakebank.log inst1.log inst2.log
            exit 1
        fi
    done
    sleep 1
done
if [[ "$services_ready" != true ]]; then
    echo "Services did not become ready within 60 seconds."
    tail -n 40 fakebank.log inst1.log inst2.log
    exit 1
fi

while true; do
    for pid in "${APP_PIDS[@]}"; do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "A service exited while the cluster was running."
            exit 1
        fi
    done
    sleep 1
done