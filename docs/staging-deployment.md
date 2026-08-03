# Staging Deployment Guide

## Prerequisites on VPS
- Docker and Docker Compose installed
- Docker Hub credentials configured (`docker login`)
- `.env` file with staging configuration

## Option 1: Using docker-compose

Create or update `docker-compose.yml` on your VPS:

```yaml
version: "3.9"
services:
  gatekeeperd:
    image: mikesplore/gatekeeperd:1.0.0-SNAPSHOT
    env_file: .env
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    depends_on:
      - postgres
      - redis
    networks:
      - gatekeeper-internal

  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: gatekeeper
      POSTGRES_USER: gatekeeper
      POSTGRES_PASSWORD: changeme
    volumes:
      - pgdata:/var/lib/postgresql/data
    networks:
      - gatekeeper-internal

  redis:
    image: redis:7-alpine
    networks:
      - gatekeeper-internal

networks:
  gatekeeper-internal:
    external: false

volumes:
  pgdata:
```

Deploy with:
```bash
docker-compose up -d
```

## Option 2: Using the deployment script (Recommended)

### On your dev machine (build + push)

```bash
./build.sh
./push.sh
```

### On the VPS (pull + run)

Copy only `deploy.sh` and `.env` to the server — no Dockerfile or source code needed.

```bash
chmod +x deploy.sh
./deploy.sh          # pulls mikesplore/gatekeeperd:1.0.0-SNAPSHOT from Docker Hub
./deploy.sh --fresh  # also resets the postgres volume (use when DB credentials changed)
```

Use `./deploy.sh --fresh` when DB credentials changed since the first deploy.
1. Create the `gatekeeper-internal` network if it doesn't exist
2. Create the `pgdata` volume for postgres (or reset it with `--fresh`)
3. Pull the app image from Docker Hub (postgres + redis too)
4. Stop and remove existing containers if they exist
5. Start postgres, verify credentials, then start redis and gatekeeperd
6. Wait for `/api/health` to respond

## Running gatekeeperd on the host (for nginx management)

The `/api/admin/nginx/*` endpoints need host access to:
- write `/etc/nginx/sites-available` and `/etc/nginx/sites-enabled`
- run `nginx -t` and `systemctl reload nginx`

The simplest setup is to run **gatekeeperd on the host (systemd)** and run client apps in Docker.

### Build artifact sources

- GitHub Actions publishes a Docker image to GHCR: `ghcr.io/<owner>/<repo>:<tag>`
- GitHub Actions also uploads a jar artifact named `gatekeeperd-all-jar`

### Extract jar from GHCR image (no local build on VPS)

On the VPS:

```bash
# Example (pick a tag from GHCR, e.g. "latest" or the short SHA)
IMAGE="ghcr.io/<owner>/<repo>:latest"

docker pull "$IMAGE"
cid="$(docker create "$IMAGE")"
sudo mkdir -p /opt/gatekeeperd
sudo docker cp "$cid":/app/gatekeeperd.jar /opt/gatekeeperd/gatekeeperd-all.jar
docker rm "$cid"
```

Then run via systemd as described in the main README or your local ops notes.

To stop all services:
```bash
docker stop gatekeeperd redis postgres
```

To remove all services:
```bash
docker rm gatekeeperd redis postgres
```

## Important Environment Variables

Make sure your `.env` file on the VPS has the correct values:

```env
# Database
DB_URL=jdbc:postgresql://postgres:5432/gatekeeper
DB_USER=your_db_user
DB_PASSWORD=your_secure_password

# Redis
REDIS_HOST=redis
REDIS_PORT=6379

# JWT (use a strong random string in production)
JWT_SECRET=your-long-random-secret-key-here
JWT_ISSUER=gatekeeperd
JWT_AUDIENCE=gatekeeperd-admin

# Paystack (use live keys for staging if needed)
PAYSTACK_SECRET_KEY=sk_live_xxx
PAYSTACK_PUBLIC_KEY=pk_live_xxx

# Docker
DOCKER_SOCKET=unix:///var/run/docker.sock
GATEKEEPER_INTERNAL_NETWORK=gatekeeper-internal

# Configuration
DEFAULT_GRACE_PERIOD_DAYS=3
FAIL_MODE=open
```

## Verify Deployment

Check if the container is running:
```bash
docker ps
```

View logs:
```bash
docker logs gatekeeperd
```

Test the API:
```bash
curl http://your-server-ip:8080/health
