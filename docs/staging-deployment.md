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
`main`. The first host check needs the deployment scripts and slot template alongside
`deploy.sh`; a source checkout is not needed after bootstrap. Install **Java 21**,
**nginx**, **GitHub CLI (`gh`)**, **curl**, and **git** for the initial bootstrap on the
host first; the preflight checks runtime/deployment tools but does not install them.

```bash
git clone --depth 1 https://github.com/mikesplore/gatekeeperd.git /tmp/gatekeeperd-bootstrap
cd /tmp/gatekeeperd-bootstrap
sudo gh auth login   # or configure GH_CONFIG_DIR in /etc/gatekeeperd/auto-deploy.env
sudo ./deploy.sh --check    # prerequisite checks + idempotent host prep (no deploy)
sudo ./deploy.sh            # blue/green deploy when --check passes
```

Before the first successful `--check`, copy application secrets to
`/etc/gatekeeperd/gatekeeperd.env` (see [`.env.example`](../.env.example)) and point
your Gatekeeperd nginx vhost at `proxy_pass http://gatekeeperd_backend;` (see
[nginx reverse proxy](nginx-reverse-proxy.md)). The preflight step can create
`/etc/gatekeeperd/deploy.env` from a template and an empty `gatekeeperd-upstream.conf`
stub when those files are missing; set `DEPLOY_GATEWAY_URL` in `deploy.env` to your
public API origin, then run `sudo ./deploy.sh --check` again until it succeeds.

The script downloads a specific workflow artifact and checks its JAR, Certbot helper,
and deployment-tool checksums. Systemd deployments use blue/green slots: the inactive
slot starts on a separate localhost port, passes dependency readiness and a stability
soak, then Nginx switches traffic with a graceful reload. The previous slot remains
running as a rollback target. If the candidate fails before or after cutover, traffic
stays on or returns to the previous slot; the script never restarts the active slot to
test a new release. It records the active commit in
`/opt/gatekeeperd/source-commit.txt` and preserves the current and previous slot releases.
Host preflight failures exit with code 10 or 11 and do **not** set the auto-deploy
failed-commit latch.

### One-time systemd blue/green setup

Keep the current `gatekeeperd.service` running for the first migration when possible.

1. Copy the application environment to `/etc/gatekeeperd/gatekeeperd.env` (readable by
   the `gatekeeperd` service user; do not set a fixed `PORT` in this file).
2. Ensure `/etc/nginx/conf.d/*.conf` is included in the `http` block and the Gatekeeperd
   vhost uses `proxy_pass http://gatekeeperd_backend;`.
3. Run `sudo ./deploy.sh --check`. It creates missing host files (for example
   `deploy.env`, upstream stub, slot unit template) where safe, then reports anything
   you must still fix manually (usually `DEPLOY_GATEWAY_URL` or nginx include wiring).
4. Deploy once to complete legacy → blue/green migration:

   ```bash
   sudo ./deploy.sh --force
   ```

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
