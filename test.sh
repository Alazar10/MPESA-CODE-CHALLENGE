#!/usr/bin/env bash
set -e

echo "=== M-PESA ASSESSMENT SUITE ==="

# Function to run the suite
run_suite() {
    echo "--- Starting Cluster (Run $1) ---"
    ./run-cluster.sh &
    CLUSTER_PID=$!

    # Wait until all endpoints respond
    echo "Waiting for services to become responsive..."
    for i in {1..30}; do
        if curl -s http://localhost:8081/mpesa/v1/wallets/W001 > /dev/null && \
           curl -s http://localhost:8082/mpesa/v1/wallets/W001 > /dev/null && \
           curl -s http://localhost:9090/bank/reconciliation/total > /dev/null; then
            break
        fi
        sleep 1
    done

    echo "Running E2E tests against :8081 and :8082..."
    ./mvnw test -Dtest=MpesaE2ETest

    echo "Stopping Cluster..."
    kill $CLUSTER_PID 2>/dev/null || true
    pkill -f "FakeBankApplication" 2>/dev/null || true
    pkill -f "ledger-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
    sleep 2
}

# Run 1
run_suite 1

# Run 2 (Ensures no manual cleanup needed)
run_suite 2

echo "=== ALL MANDATORY TESTS PASSED TWICE IN A ROW WITH ZERO MANUAL CLEANUP ==="