# Gatekeeperd

Gatekeeperd puts payment-aware access control in front of client applications. Paying
clients reach their apps; overdue clients see a payment page. A confirmed payment
restores access without stopping or redeploying the app. Nginx or Traefik remains the
public reverse proxy.

The admin dashboard is in the separate `gatekeeperd-frontend` repository.

## Deploy to a VPS

Production deployment uses the latest successful `build.yml` artifact from `main`.
The VPS runs `deploy.sh`, which verifies the artifact, starts the inactive systemd
slot, checks readiness, and changes the Nginx upstream only after the candidate passes.
The previous slot is retained for rollback. The VPS needs GitHub CLI authentication
with access to workflow artifacts.

### 1. Test the built JAR locally

Configure `.env` with database and Redis addresses reachable from your development
machine, then build and start the same JAR locally:

```bash
./gradlew test buildFatJar
./scripts/deploy-local.sh
```

The local script uses `build/libs/gatekeeperd-all.jar`, listens on port `18081`, and
waits for `/api/health/ready`. It shows readiness progress and keeps the app running
for manual checks. Press Ctrl+C to stop it. The log is written to
`build/deploy-local.log`.

### 2. Confirm the production prerequisites

Keep the application environment at `/etc/gatekeeperd/gatekeeperd.env` and deployment
settings at `/etc/gatekeeperd/deploy.env`. The slot unit reads the application
environment file; do not set a fixed `PORT` there because each slot has its own port.
See [the staging deployment guide](docs/staging-deployment.md) for the full setup.

For the first blue/green migration, Nginx must use the managed upstream file and the
legacy `gatekeeperd.service` must be active on port `8080`. The deployment script
refuses the first cutover if Nginx still points at `8080` but that service is stopped.
Check this before deploying:

```bash
sudo systemctl status gatekeeperd.service --no-pager -l
sudo cat /etc/nginx/conf.d/gatekeeperd-upstream.conf
sudo nginx -t
```

If the upstream is still `127.0.0.1:8080`, restore the legacy service before the
first migration:

```bash
sudo systemctl enable --now gatekeeperd.service
sudo systemctl is-active gatekeeperd.service
curl -i --max-time 5 http://127.0.0.1:8080/api/health/live
```

Do not stop the service currently targeted by Nginx. After the first successful
blue/green deployment, Nginx targets port `18081` or `18082`, and the deployment
script manages the inactive slot on subsequent releases.

### 3. Deploy the successful build

Wait for the `build` workflow for the intended `main` commit to pass. On the VPS:

```bash
cd ~/gatekeeperd
git status --short
git pull --ff-only origin main
./deploy.sh --force
```

Review `git status --short` before pulling; preserve any local changes. `--force`
allows an intentional retry of a previously failed or rolled-back commit. The script
prints progress during artifact download, service readiness, stability checks, and
Nginx worker draining.

After deployment, verify the active upstream and service:

```bash
sudo cat /etc/nginx/conf.d/gatekeeperd-upstream.conf
sudo systemctl status gatekeeperd-slot@blue.service gatekeeperd-slot@green.service --no-pager
curl -i --max-time 5 https://YOUR_GATEKEEPERD_HOST/api/health/ready
```

If a candidate fails, the script leaves or restores the previous route. Inspect the
candidate journal before retrying:

```bash
sudo journalctl -u gatekeeperd-slot@blue.service --since "15 minutes ago" --no-pager -n 100
sudo journalctl -u gatekeeperd-slot@green.service --since "15 minutes ago" --no-pager -n 100
```

Never remove PostgreSQL data or the application environment as a response to a failed
readiness check. Readiness requires PostgreSQL, Redis, and migrations to be healthy;
the endpoint reports each dependency separately.

## Other deployment and architecture docs

- [Staging and VPS deployment](docs/staging-deployment.md)
- [Backend architecture and environment variables](docs/README.md)
- [API reference](docs/API.md)
- [Nginx client gating](docs/nginx-client-gating.md)
- [System design](docs/backend-system-guide.md)
- [.env.example](.env.example)

## Local development

Copy `.env.example` to `.env`, configure PostgreSQL and Redis, then run:

```bash
./gradlew run
```
