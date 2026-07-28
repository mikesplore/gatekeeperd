#!/bin/bash
set -e

echo "=== Gatekeeperd Deployment Script ==="
echo ""

# Configuration - UPDATE THESE VALUES AS NEEDED
NETWORK_NAME="gatekeeper-internal"
POSTGRES_VOLUME="pgdata"
IMAGE_NAME="mikesplore/gatekeeperd:1.0.0-SNAPSHOT"
CONTAINER_NAME="gatekeeperd"

# Env file location (same directory as this script)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

# Verify .env exists
if [ ! -f "$ENV_FILE" ]; then
    echo "Error: .env file not found at $ENV_FILE"
    echo "Please create a .env file with your configuration."
    exit 1
fi

# Step 1: Create network if it doesn't exist
echo "[1/5] Setting up network..."
if ! docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    echo "  Creating network: $NETWORK_NAME"
    docker network create "$NETWORK_NAME"
else
    echo "  Network $NETWORK_NAME already exists"
fi

# Step 2: Create volume if it doesn't exist
echo "[2/5] Setting up volumes..."
if ! docker volume inspect "$POSTGRES_VOLUME" >/dev/null 2>&1; then
    echo "  Creating volume: $POSTGRES_VOLUME"
    docker volume create "$POSTGRES_VOLUME"
else
    echo "  Volume $POSTGRES_VOLUME already exists"
fi

# Step 3: Pull required images
echo "[3/5] Pulling Docker images..."
echo "  Pulling postgres:16..."
docker pull postgres:16

echo "  Pulling redis:7-alpine..."
docker pull redis:7-alpine

echo "  Pulling $IMAGE_NAME..."
docker pull "$IMAGE_NAME"

# Step 4: Stop and remove existing containers if they exist
echo "[4/5] Cleaning up existing containers..."
for container in postgres redis "$CONTAINER_NAME"; do
    if docker ps -a --format '{{.Names}}' | grep -q "^${container}$"; then
        echo "  Stopping and removing $container..."
        docker stop "$container" >/dev/null 2>&1 || true
        docker rm "$container" >/dev/null 2>&1 || true
    else
        echo "  $container does not exist, skipping..."
    fi
done

# Step 5: Start containers in order
echo "[5/5] Starting containers..."

echo "  Starting postgres..."
docker run -d \
  --name postgres \
  --network="$NETWORK_NAME" \
  -e POSTGRES_DB=gatekeeper \
  -e POSTGRES_USER=gatekeeper \
  -e POSTGRES_PASSWORD=changeme \
  -v "$POSTGRES_VOLUME":/var/lib/postgresql/data \
  postgres:16

echo "  Waiting for postgres to be ready..."
sleep 5

echo "  Starting redis..."
docker run -d \
  --name redis \
  --network="$NETWORK_NAME" \
  redis:7-alpine

echo "  Starting gatekeeperd..."
docker run -d \
  --name "$CONTAINER_NAME" \
  --network="$NETWORK_NAME" \
  --env-file "$ENV_FILE" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  "$IMAGE_NAME"

echo ""
echo "=== Deployment Complete ==="
echo ""
echo "Container status:"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

echo ""
echo "To view logs: docker logs $CONTAINER_NAME"
echo "To test API: curl http://\$(curl -s ifconfig.me):8080/health"
echo ""
echo "To stop all services:"
echo "  docker stop $CONTAINER_NAME redis postgres"
echo ""
echo "To remove all services:"
echo "  docker rm $CONTAINER_NAME redis postgres"