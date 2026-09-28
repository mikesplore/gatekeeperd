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

## Deploy the systemd service from a CI artifact

The repository's `deploy.sh` downloads the JAR from the latest successful `build.yml`
workflow run on `main`. On the VPS, authenticate GitHub CLI with permission to read
workflow artifacts, then copy the script and run it:

```bash
gh auth login
chmod +x deploy.sh
./deploy.sh
```

The script downloads from a specific workflow run, checks the JAR SHA-256, prints the
full source commit, saves it to `/opt/gatekeeperd/source-commit.txt`, and restarts the
systemd service. The printed commit identifies the exact CI artifact installed.

## Running gatekeeperd on the host (for nginx management)

The `/api/admin/nginx/*` endpoints need host access to:
- write `/etc/nginx/sites-available` and `/etc/nginx/sites-enabled`
- run `nginx -t` and `systemctl reload nginx`

The simplest setup is to run **gatekeeperd on the host (systemd)** and run client apps in Docker.

### Allowing Gatekeeperd to manage Let's Encrypt certificates

Certbot needs root access to its configuration, work, and log directories. Keep the
application running as its normal service user and install the restricted helper:

```bash
sudo install -o root -g root -m 0755 scripts/gatekeeperd-certbot /usr/local/sbin/gatekeeperd-certbot
sudo visudo -f /etc/sudoers.d/gatekeeperd-certbot
```

Add this line to the sudoers file, replacing `ubuntu` with the `User=` configured
for `gatekeeperd.service`:

```sudoers
ubuntu ALL=(root) NOPASSWD: /usr/local/sbin/gatekeeperd-certbot *
```

The helper is root-owned and only accepts validated certificate install, renewal, and remove
requests. Validate the rule with `sudo visudo -cf /etc/sudoers.d/gatekeeperd-certbot`.
Gatekeeperd invokes it with `sudo -n`, so missing permission is reported immediately
instead of hanging for a password.

### Build artifact sources

- GitHub Actions uploads a jar artifact named `gatekeeperd-all-jar` containing:
  - `gatekeeperd-all.jar`
  - `gatekeeperd-all.jar.sha256`

### Install jar on VPS

Run the repository's `deploy.sh` on the VPS. It selects a specific successful workflow
run, reports its source commit, validates the downloaded checksum, installs the JAR at
`/opt/gatekeeperd/gatekeeperd-all.jar`, and restarts the systemd service.

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
