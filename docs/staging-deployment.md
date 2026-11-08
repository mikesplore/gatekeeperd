# VPS Deployment and Operations

This guide covers local artifact smoke checks and the supported CI-artifact deployment to a systemd-managed VPS. Gatekeeperd runs as a host service behind nginx. Client workloads may run in Docker.

## Prerequisites

- PostgreSQL and Redis reachable from the Gatekeeperd host.
- A Linux VPS with systemd, nginx, and the host permissions required by the deployment and nginx integration.
- GitHub CLI authenticated on the VPS with permission to read successful Actions artifacts.
- Application settings in `/etc/gatekeeperd/gatekeeperd.env`; use [`.env.example`](../.env.example) as the template.

For a first installation, follow [the root README setup](../README.md#first-vps-install). Use `deploy.sh` for normal upgrades after setup.

## Test the built JAR locally before production deployment

Run the tests and build the JAR locally, then start that exact JAR with the local
smoke-deployment script:

```bash
./gradlew test buildFatJar
./scripts/deploy-local.sh
```

The local script does not build or download anything. It uses
`build/libs/gatekeeperd-all.jar`, loads `.env` when present, overrides `PORT` to
`18081`, and waits for `/api/health/ready`. It prints readiness responses while
waiting and keeps the app running for manual API checks; press Ctrl+C to stop it.
For host execution, configure `.env` with database and Redis addresses reachable
from the host (for example, `localhost` rather than a container-network hostname when dependencies run in Docker).
The startup log is written to `build/deploy-local.log`.

This local check is separate from the production deployment script below. Production
deployment continues to use the CI artifact and blue/green cutover.

## Deploy the systemd service from a CI artifact

`deploy.sh` downloads the JAR from the latest successful `build.yml` workflow run on
`main`. The VPS does not need a source checkout. Authenticate GitHub CLI with
permission to read workflow artifacts, download the deployment script, and run it:

```bash
gh auth login
curl -fsSLo deploy.sh https://raw.githubusercontent.com/mikesplore/gatekeeperd/main/deploy.sh
chmod +x deploy.sh
./deploy.sh
```

The script downloads a specific workflow artifact and checks its JAR, Certbot helper,
and deployment-tool checksums. Systemd deployments use blue/green slots: the inactive
slot starts on a separate localhost port, passes dependency readiness and a stability
soak, then Nginx switches traffic with a graceful reload. The previous slot remains
running as a rollback target. If the candidate fails before or after cutover, traffic
stays on or returns to the previous slot; the script never restarts the active slot to
test a new release. It records the active commit in
`/opt/gatekeeperd/source-commit.txt` and preserves the current and previous slot releases.

### One-time systemd blue/green setup

The first blue/green deployment needs a stable Nginx upstream and a shared environment
file. Keep the current `gatekeeperd.service` running while preparing these settings.

1. Copy the existing application environment to `/etc/gatekeeperd/gatekeeperd.env`.
   Keep it readable by the same service user and group as `gatekeeperd.service`; protect
   its secrets with appropriate file permissions. Do not set a fixed `PORT` here; each
   slot supplies its own port.
2. Create `/etc/gatekeeperd/deploy.env` with deployment-only settings:

   ```env
   DEPLOY_APP_ENV_FILE=/etc/gatekeeperd/gatekeeperd.env
   DEPLOY_GATEWAY_URL=https://gateapi.example.net
   DEPLOY_SOAK_SECONDS=30
   DEPLOY_NGINX_DRAIN_TIMEOUT_SECONDS=620
   ```

   `DEPLOY_GATEWAY_URL` must be the public origin served by the Gatekeeperd Nginx vhost.
   This file contains no application secrets and must be readable by the account that
   runs `deploy.sh`.
3. Ensure `/etc/nginx/conf.d/*.conf` is included inside Nginx's `http` block. Create
   `/etc/nginx/conf.d/gatekeeperd-upstream.conf` with the current single-process target:

   ```nginx
   upstream gatekeeperd_backend {
       server 127.0.0.1:8080;
       keepalive 32;
   }
   ```

4. In the Gatekeeperd reverse-proxy vhost, change
   `proxy_pass http://localhost:8080;` to `proxy_pass http://gatekeeperd_backend;`.
   Check and gracefully reload Nginx:

   ```bash
   sudo nginx -t && sudo systemctl reload nginx
   ```

5. Run one manual deployment to migrate the current release into a blue/green slot and
   verify the setup:

   ```bash
   ./deploy.sh --force
   ```

   This starts the incoming release in a second slot, checks it directly, switches Nginx
   to it, and verifies the public readiness URL before stopping the legacy service. The
   legacy process stays available until Nginx's old workers drain. If any check fails,
   the request route remains on the old process. After this setup, automatic deployments
   use the same blue/green flow.

The slot systemd unit inherits the legacy service's user, group, and supplementary groups.
Scheduled worker loops use PostgreSQL advisory locks, so only one overlapping instance
runs each scheduled operation at a time. Keep schema changes backward-compatible across
the overlap: add new columns/tables first, deploy code that supports both shapes, then
remove obsolete schema in a later release.

## Running gatekeeperd on the host (for nginx management)

The `/api/admin/nginx/*` endpoints need host access to:
- write `/etc/nginx/sites-available` and `/etc/nginx/sites-enabled`
- run `nginx -t` and `systemctl reload nginx`

The simplest setup is to run **gatekeeperd on the host (systemd)** and run client apps in Docker.
Gatekeeperd does not need to run as root. Provision write access to the two nginx directories for the
systemd service account once during host setup. For example, when the account is `gatekeeperd` and
POSIX ACL tools are installed:

```bash
sudo setfacl -m u:gatekeeperd:rwx /etc/nginx/sites-available /etc/nginx/sites-enabled
sudo setfacl -m d:u:gatekeeperd:rwx /etc/nginx/sites-available /etc/nginx/sites-enabled
```

Replace `gatekeeperd` with the `User=` configured for `gatekeeperd.service`. If the systemd unit
uses filesystem sandboxing, also allow these directories with `ReadWritePaths=`. The application
uses local `nginx/sites-available` and `nginx/sites-enabled` paths in DEV mode; no host nginx
permissions or sudo are needed for local development.

The admin logs page reads the bootstrap and both blue/green Gatekeeperd units from the systemd journal; the Gatekeeperd service account must be allowed to read that journal. The Nginx error-log view reads `/var/log/nginx/error.log`; ensure the service account can read that file (on Ubuntu, membership in `adm` normally provides access).

### Certbot certificate management

Install Certbot on the host and deploy using the current `deploy.sh`. The CI artifact
includes the checksum-verified, restricted helper. During deployment, the script installs
it as root at `/usr/local/sbin/gatekeeperd-certbot` and grants only the Gatekeeperd
systemd service account permission to invoke it without a password. The helper validates
the requested domain/email and supports only certificate install, renewal, and removal.

After deployment, the Certificates page shows whether Certbot management is ready and
the server-side setup issue if it is not. If a server was deployed before the helper was
included in CI artifacts, download the latest `deploy.sh` using the command above and
run it again.
The Gatekeeperd reverse proxy must allow a 10-minute `proxy_read_timeout` for certificate
renewal requests; update the existing site config and reload nginx after changing it.

### Build artifact sources

- GitHub Actions uploads a jar artifact named `gatekeeperd-all-jar` containing:
  - `gatekeeperd-all.jar`
  - `gatekeeperd-all.jar.sha256`
  - `gatekeeperd-certbot`
  - `gatekeeperd-certbot.sha256`
  - `gatekeeperd-deploy-tools.tar.gz` and its SHA-256 checksum

### Install jar on VPS

Run the downloaded `deploy.sh` on the VPS. It selects a specific successful workflow
run, reports its source commit, validates the downloaded checksums, and promotes the
release through blue/green systemd slots behind the stable Nginx upstream. A source
checkout is not needed on the server.

### Automatic deployment from successful builds

After deploying a build that includes the deployment checker, `deploy.sh` installs
and enables a systemd timer. It checks GitHub every five minutes for the newest
successful `build.yml` run on `main`. When that run's commit differs from
`/opt/gatekeeperd/source-commit.txt`, it deploys that exact workflow run, verifies
the artifact checksums, starts the inactive slot, verifies `/api/health/ready`, switches
Nginx gracefully, and verifies the public route before recording success. A failed
candidate check leaves or restores the previous slot as the Nginx upstream. A
systemd reload failure while installing the separate auto-deploy timer is reported
as a warning and does not mark a healthy application deployment as failed. A failed
application commit is held back until a newer successful build appears.

The timer needs no inbound port or GitHub webhook. It runs on the VPS and uses the
GitHub CLI credentials already configured there. The first rollout requires one
manual deployment using the updated `deploy.sh`; after that, pushes to `main` deploy
automatically when CI succeeds. If root cannot read the existing `gh` authentication,
the timer stays disabled and `deploy.sh` prints the setup issue. Check it with:

```bash
sudo systemctl status gatekeeperd-auto-deploy.timer
sudo journalctl -u gatekeeperd-auto-deploy.service -n 50 --no-pager
```

To stop automatic deployments:

```bash
sudo systemctl disable --now gatekeeperd-auto-deploy.timer
```

The checker deploys the most recent successful `main` build. It does not deploy pull
request builds or tags. A commit whose deployment failed is not retried automatically;
deploy a newer successful commit or run `deploy.sh` manually to retry it.

`GET /api/version` identifies the latest held revision in `latestSuccessfulCommit` and
reports `deploymentHoldReason` as `deployment_failed` or `rolled_back`. Review the
failed attempt first through the admin deployment diagnostics endpoint or the host
journal. To intentionally retry a revision held after rollback, run
`sudo /opt/gatekeeperd/deploy.sh --force` from an operator shell; `--force` bypasses
the rollback hold for that attempt, and a successful deployment clears the hold.
Prefer deploying a newer successful build after an unsuccessful rollout. Run the
command through `sudo` so the configured sudo logging policy records the operator action.

## Verify deployment

Check the public readiness endpoint:

```bash
curl -fsS https://gateapi.example.com/api/health/ready
```

Inspect the deployment checker and application journal when a rollout or readiness check fails:

```bash
sudo systemctl status gatekeeperd-auto-deploy.timer
sudo journalctl -u gatekeeperd-auto-deploy.service -n 50 --no-pager
sudo journalctl -u gatekeeperd -n 100 --no-pager
```

The full environment template is [`.env.example`](../.env.example). The [backend system guide](backend-system-guide.md#21-failure-behavior-and-operational-diagnosis) lists first checks for common database, Redis, nginx, payment, and Scribed failures.
