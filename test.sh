#!/usr/bin/env bash
set -e

echo "=== M-PESA ASSESSMENT SUITE ==="

CLUSTER_PID=""

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ -n "$CLUSTER_PID" ]] && kill -0 "$CLUSTER_PID" 2>/dev/null; then
        kill -TERM "$CLUSTER_PID" 2>/dev/null || true
    fi
    if [[ -n "$CLUSTER_PID" ]]; then
        wait "$CLUSTER_PID" 2>/dev/null || true
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_suite() {
    echo "--- Starting Cluster (Run $1) ---"
    ./run-cluster.sh &
    CLUSTER_PID=$!

    echo "Waiting for services to become responsive..."
    services_ready=false
    for i in {1..90}; do
        if curl -fsS http://localhost:8081/mpesa/v1/wallets/W001 > /dev/null 2>&1 && \
           curl -fsS http://localhost:8082/mpesa/v1/wallets/W001 > /dev/null 2>&1 && \
           curl -fsS http://localhost:9090/bank/reconciliation/total > /dev/null 2>&1; then
            services_ready=true
            break
        fi
        if ! kill -0 "$CLUSTER_PID" 2>/dev/null; then
            echo "Cluster startup failed."
            wait "$CLUSTER_PID" || true
            CLUSTER_PID=""
            return 1
        fi
        sleep 1
    done
    if [[ "$services_ready" != true ]]; then
        echo "Services did not become ready within 90 seconds."
        return 1
    fi

    echo "Running E2E tests against :8081 and :8082..."
    ./mvnw test -Dtest=MpesaE2ETest

    echo "Stopping Cluster..."
    kill -TERM "$CLUSTER_PID" 2>/dev/null || true
    wait "$CLUSTER_PID" 2>/dev/null || true
    CLUSTER_PID=""
    sleep 2
}

run_suite 1

run_suite 2

echo "=== ALL MANDATORY TESTS PASSED TWICE IN A ROW WITH ZERO MANUAL CLEANUP ==="