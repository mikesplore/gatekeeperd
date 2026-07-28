#!/bin/bash
set -e

echo "=== Gatekeeperd Deployment Script ==="
echo ""

FRESH=false
for arg in "$@"; do
    case "$arg" in
        --fresh) FRESH=true ;;
        -h|--help)
            echo "Usage: ./deploy.sh [--fresh]"
            echo ""
            echo "  --fresh   Stop containers and delete the postgres volume before deploy."
            echo "            Required when DB_USER/DB_PASSWORD changed since first deploy."
            exit 0
            ;;
        *)
            echo "Unknown option: $arg (try --help)"
            exit 1
            ;;
    esac
done

NETWORK_NAME="gatekeeper-internal"
POSTGRES_VOLUME="pgdata"
IMAGE_NAME="mikesplore/gatekeeperd:1.0.0-SNAPSHOT"
CONTAINER_NAME="gatekeeperd"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
    echo "Error: .env file not found at $ENV_FILE"
    echo "Copy .env.example to .env and set DB_URL, DB_USER, and DB_PASSWORD."
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

missing=()
[ -z "${DB_URL:-}" ] && missing+=("DB_URL")
[ -z "${DB_USER:-}" ] && missing+=("DB_USER")
[ -z "${DB_PASSWORD:-}" ] && missing+=("DB_PASSWORD")
[ -z "${REDIS_HOST:-}" ] && missing+=("REDIS_HOST")

if [ "${#missing[@]}" -gt 0 ]; then
    echo "Error: missing required variables in .env:"
    printf '  - %s\n' "${missing[@]}"
    exit 1
fi

# Derive postgres database name from DB_URL (jdbc:postgresql://host:port/dbname)
POSTGRES_DB="${POSTGRES_DB:-${DB_URL##*/}}"
POSTGRES_USER="$DB_USER"
POSTGRES_PASSWORD="$DB_PASSWORD"

echo "Configuration (from .env):"
echo "  DB_USER=$POSTGRES_USER"
echo "  DB_NAME=$POSTGRES_DB"
echo "  REDIS_HOST=$REDIS_HOST"
echo "  IMAGE=$IMAGE_NAME (pull from registry)"
echo ""

echo "[1/7] Setting up network..."
if ! docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    echo "  Creating network: $NETWORK_NAME"
    docker network create "$NETWORK_NAME"
else
    echo "  Network $NETWORK_NAME already exists"
fi

echo "[2/7] Stopping existing containers..."
for container in "$CONTAINER_NAME" redis postgres; do
    if docker ps -a --format '{{.Names}}' | grep -q "^${container}$"; then
        echo "  Stopping and removing $container..."
        docker stop "$container" >/dev/null 2>&1 || true
        docker rm "$container" >/dev/null 2>&1 || true
    else
        echo "  $container does not exist, skipping..."
    fi
done

echo "[3/7] Setting up postgres volume..."
if [ "$FRESH" = true ]; then
    if docker volume inspect "$POSTGRES_VOLUME" >/dev/null 2>&1; then
        echo "  --fresh: removing volume $POSTGRES_VOLUME"
        docker volume rm "$POSTGRES_VOLUME"
    fi
fi

if ! docker volume inspect "$POSTGRES_VOLUME" >/dev/null 2>&1; then
    echo "  Creating volume: $POSTGRES_VOLUME (postgres will initialize with user=$POSTGRES_USER)"
    docker volume create "$POSTGRES_VOLUME"
else
    echo "  Volume $POSTGRES_VOLUME already exists"
    echo "  Note: Postgres ignores POSTGRES_USER/POSTGRES_PASSWORD when reusing an existing volume."
    echo "        If auth fails, rerun with: ./deploy.sh --fresh"
fi

echo "[4/7] Pulling app image..."
docker pull "$IMAGE_NAME"

echo "[5/7] Pulling dependency images..."
docker pull postgres:16
docker pull redis:7-alpine

echo "[6/7] Starting containers..."

echo "  Starting postgres (user=$POSTGRES_USER, db=$POSTGRES_DB)..."
docker run -d \
  --name postgres \
  --network="$NETWORK_NAME" \
  -e POSTGRES_DB="$POSTGRES_DB" \
  -e POSTGRES_USER="$POSTGRES_USER" \
  -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  -v "$POSTGRES_VOLUME":/var/lib/postgresql/data \
  postgres:16

echo "  Waiting for postgres to be ready..."
for i in {1..30}; do
    if docker exec postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then
        echo "  Postgres is ready"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "  Error: postgres did not become ready in time"
        docker logs postgres | tail -20
        exit 1
    fi
    sleep 1
done

echo "  Verifying postgres credentials..."
if ! docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1" >/dev/null 2>&1; then
    echo "  Error: postgres rejected user=$POSTGRES_USER with the password from .env"
    echo "  The $POSTGRES_VOLUME volume was likely initialized with different credentials."
    echo "  Reset it with: ./deploy.sh --fresh"
    exit 1
fi

echo "  Starting redis..."
docker run -d \
  --name redis \
  --network="$NETWORK_NAME" \
  redis:7-alpine

echo "  Starting gatekeeperd..."
docker run -d \
  --name "$CONTAINER_NAME" \
  --network="$NETWORK_NAME" \
  -p 8080:8080 \
  -e DB_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB}" \
  -e DB_USER="$POSTGRES_USER" \
  -e DB_PASSWORD="$POSTGRES_PASSWORD" \
  -e REDIS_HOST=redis \
  -e REDIS_PORT="${REDIS_PORT:-6379}" \
  -e JWT_SECRET="${JWT_SECRET:?JWT_SECRET is required in .env}" \
  -e JWT_ISSUER="${JWT_ISSUER:-gatekeeperd}" \
  -e JWT_AUDIENCE="${JWT_AUDIENCE:-gatekeeperd-admin}" \
  -e PAYSTACK_SECRET_KEY="${PAYSTACK_SECRET_KEY:-}" \
  -e PAYSTACK_PUBLIC_KEY="${PAYSTACK_PUBLIC_KEY:-}" \
  -e DOCKER_SOCKET="${DOCKER_SOCKET:-unix:///var/run/docker.sock}" \
  -e GATEKEEPER_INTERNAL_NETWORK="${GATEKEEPER_INTERNAL_NETWORK:-gatekeeper-internal}" \
  -e DEFAULT_GRACE_PERIOD_DAYS="${DEFAULT_GRACE_PERIOD_DAYS:-3}" \
  -e FAIL_MODE="${FAIL_MODE:-open}" \
  -e CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-https://gatekeeperd.mikesplore.me,http://localhost:5173}" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  "$IMAGE_NAME"

echo "[7/7] Waiting for gatekeeperd health check..."
for i in {1..30}; do
    if curl -sf http://localhost:8080/api/health >/dev/null 2>&1; then
        echo "  Health check passed"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "  Error: gatekeeperd did not respond on /api/health"
        echo ""
        echo "Recent logs:"
        docker logs "$CONTAINER_NAME" | tail -40
        exit 1
    fi
    sleep 1
done

echo ""
echo "=== Deployment Complete ==="
echo ""
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
echo ""
echo "Health: curl http://localhost:8080/api/health"
echo "Logs:   docker logs $CONTAINER_NAME"
