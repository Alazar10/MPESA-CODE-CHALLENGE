#!/usr/bin/env bash
set -e

cleanup() {
    echo "Stopping all processes..."
    kill $(jobs -p) 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "1. Checking/Starting PostgreSQL..."
docker rm -f mpesa-postgres 2>/dev/null || true
docker run -d \
  --name mpesa-postgres \
  -e POSTGRES_DB=mpesa_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgrespassword \
  -p 5432:5432 \
  postgres:16-alpine

echo "Waiting for PostgreSQL to be healthy..."
until docker exec mpesa-postgres pg_isready -U postgres -d mpesa_db > /dev/null 2>&1; do
    sleep 0.5
done

echo "Applying Schema..."
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
EOF

echo "Packaging application..."
./mvnw clean package -DskipTests

echo "2. Starting Fake Bank on :9090..."
java -cp target/classes:$(./mvnw dependency:build-classpath | grep -v '\[INFO\]' | tr '\n' ':') \
     com.fintech.ledger.fakebank.FakeBankApplication > /dev/null 2>&1 &
FAKE_BANK_PID=$!

echo "3. Starting Instance 1 on :8081..."
SERVER_PORT=8081 java -jar target/ledger-0.0.1-SNAPSHOT.jar > /dev/null 2>&1 &
INST1_PID=$!

echo "4. Starting Instance 2 on :8082..."
SERVER_PORT=8082 java -jar target/ledger-0.0.1-SNAPSHOT.jar > /dev/null 2>&1 &
INST2_PID=$!

echo "Waiting for services to become healthy..."
until curl -s http://localhost:9090/bank/reconciliation/total > /dev/null; do sleep 0.5; done
until curl -s http://localhost:8081/actuator/health > /dev/null 2>&1 || curl -s http://localhost:8081/mpesa/v1/wallets/W001 > /dev/null 2>&1; do sleep 0.5; done
until curl -s http://localhost:8082/actuator/health > /dev/null 2>&1 || curl -s http://localhost:8082/mpesa/v1/wallets/W001 > /dev/null 2>&1; do sleep 0.5; done

echo "All 3 services are UP and listening:"
echo " - Fake Bank:  http://localhost:9090"
echo " - Instance 1: http://localhost:8081"
echo " - Instance 2: http://localhost:8082"

# Keep cluster alive if run directly
wait