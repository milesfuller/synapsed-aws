#!/usr/bin/env bash
set -euo pipefail

# 1. Build the shaded jar
cd "$(dirname "$0")"
echo "[INFO] Building shaded Lambda jar..."
mvn clean package

# 2. Start LocalStack with required services
export LOCALSTACK_CONTAINER_NAME=relayserver-localstack-test
SERVICES="lambda,apigateway,dynamodb,sqs,iam"
echo "[INFO] Starting LocalStack Docker container..."
docker run --rm -d --name $LOCALSTACK_CONTAINER_NAME -p 4566:4566 -e SERVICES=$SERVICES localstack/localstack:3.3.0

# 3. Wait for LocalStack to be ready
function wait_for_localstack() {
  echo "[INFO] Waiting for LocalStack to be ready..."
  for i in {1..30}; do
    if curl -s http://localhost:4566/health | grep '"initScripts": *true' >/dev/null; then
      echo "[INFO] LocalStack is ready."
      return 0
    fi
    sleep 2
  done
  echo "[ERROR] LocalStack did not become ready in time."
  return 1
}
wait_for_localstack

# 4. Run the integration tests
echo "[INFO] Running integration tests..."
mvn test

# 5. Stop LocalStack
echo "[INFO] Stopping LocalStack Docker container..."
docker stop $LOCALSTACK_CONTAINER_NAME

echo "[INFO] Integration test run complete." 