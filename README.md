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
bootstrap `gatekeeperd.service` must be active on port `8080`. This is true for both
an upgrade and a brand-new VPS: on a new server, create the bootstrap service using the
first downloaded JAR. The deployment script refuses the first cutover if Nginx still
points at `8080` but that service is stopped. Check this before deploying:

```bash
sudo systemctl status gatekeeperd.service --no-pager -l
sudo cat /etc/nginx/conf.d/gatekeeperd-upstream.conf
sudo nginx -t
```

If the upstream is still `127.0.0.1:8080`, restore the legacy service before the
first migration. On an existing installation this starts its existing unit. On a
brand-new server, install the first JAR at `/opt/gatekeeperd/gatekeeperd-all.jar` and
create a bootstrap unit instead:

```bash
RUN_ID="$(gh run list --repo mikesplore/gatekeeperd --workflow build.yml --branch main --status success --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
test -n "$RUN_ID"
ARTIFACT_DIR="$(mktemp -d)"
gh run download "$RUN_ID" --repo mikesplore/gatekeeperd --name gatekeeperd-all-jar --dir "$ARTIFACT_DIR"
printf '%s  %s\n' "$(cat "$ARTIFACT_DIR/gatekeeperd-all.jar.sha256")" "$ARTIFACT_DIR/gatekeeperd-all.jar" | sha256sum --check
sudo install -d -o root -g root -m 0755 /opt/gatekeeperd
sudo install -o root -g root -m 0755 "$ARTIFACT_DIR/gatekeeperd-all.jar" /opt/gatekeeperd/gatekeeperd-all.jar
sudo tee /etc/systemd/system/gatekeeperd.service >/dev/null <<'EOF'
[Unit]
Description=Gatekeeperd bootstrap service
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/gatekeeperd
EnvironmentFile=/etc/gatekeeperd/gatekeeperd.env
ExecStart=/usr/bin/java -jar /opt/gatekeeperd/gatekeeperd-all.jar -port=8080
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now gatekeeperd.service
sudo systemctl is-active gatekeeperd.service
curl -i --max-time 5 http://127.0.0.1:8080/api/health/live
```

Replace `User=ubuntu` with the account that owns the app environment and has the
required Docker/nginx access. The deployment script copies that account and group
configuration to the blue/green slot unit. Once the first slot passes readiness and
Nginx cuts over, the bootstrap service is stopped; future deploys use blue/green slots.

Do not stop the service currently targeted by Nginx. After the first successful
blue/green deployment, Nginx targets port `18081` or `18082`, and the deployment
script manages the inactive slot on subsequent releases.

### 3. Deploy the successful build

Wait for the `build` workflow for the intended `main` commit to pass. The VPS does not
need a source checkout. Authenticate GitHub CLI with permission to read workflow
artifacts, then download only the deployment script:

```bash
gh auth status --hostname github.com
curl -fsSLo deploy.sh https://raw.githubusercontent.com/mikesplore/gatekeeperd/main/deploy.sh
chmod +x deploy.sh
./deploy.sh --force
```

`deploy.sh` downloads and verifies the latest successful CI artifact. `--force` allows
an intentional retry of a previously failed or rolled-back commit. The script prints
progress during artifact download, service readiness, stability checks, and Nginx
worker draining. No repository checkout or source-code pull is needed on the VPS.

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

### Fresh application install after a failed migration

This reset replaces Gatekeeperd application releases and slot state while preserving
the PostgreSQL/Redis data, Nginx configuration/certificates, and the environment files:

- `/etc/gatekeeperd/gatekeeperd.env`
- `/etc/gatekeeperd/deploy.env`

Do not run this if you intend to erase customer or payment data. Run on the VPS after
the intended `main` build has succeeded, with GitHub CLI authenticated. The only
Gatekeeperd files fetched from GitHub are the deployment script and CI artifact. First
confirm that the legacy unit uses `/opt/gatekeeperd/gatekeeperd-all.jar` and the
preserved application environment file:

```bash
sudo systemctl cat gatekeeperd.service
sudo cat /etc/nginx/conf.d/gatekeeperd-upstream.conf
```

Then download and verify the successful CI JAR. This temporary copy lets the legacy
service serve port `8080`, which the first blue/green migration requires:

```bash
set -euo pipefail
RUN_ID="$(gh run list --repo mikesplore/gatekeeperd --workflow build.yml --branch main --status success --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
test -n "${RUN_ID}"
ARTIFACT_DIR="$(mktemp -d)"
gh run download "${RUN_ID}" --repo mikesplore/gatekeeperd --name gatekeeperd-all-jar --dir "${ARTIFACT_DIR}"
printf '%s  %s\n' "$(cat "${ARTIFACT_DIR}/gatekeeperd-all.jar.sha256")" "${ARTIFACT_DIR}/gatekeeperd-all.jar" | sha256sum --check
```

If a deployment is still running, interrupt it with Ctrl+C and wait for the shell
prompt. Stop Gatekeeperd services, then clear the runtime directory and generated
slot/auto-deploy files. The existing service unit is removed and recreated as a
temporary bootstrap unit for the fresh JAR on port `8080`:

```bash
sudo systemctl disable --now gatekeeperd-auto-deploy.timer 2>/dev/null || true
sudo systemctl disable --now gatekeeperd-slot@blue.service gatekeeperd-slot@green.service 2>/dev/null || true
sudo systemctl stop gatekeeperd.service 2>/dev/null || true
sudo find /opt/gatekeeperd -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
sudo rm -f /etc/systemd/system/gatekeeperd-slot@.service \
  /etc/systemd/system/gatekeeperd.service \
  /etc/gatekeeperd/slots/blue.env /etc/gatekeeperd/slots/green.env
sudo rm -f /opt/gatekeeperd/deploy.sh /opt/gatekeeperd/gatekeeperd-slot@.service.in \
  /opt/gatekeeperd/gatekeeperd-auto-deploy /usr/local/sbin/gatekeeperd-certbot \
  /etc/systemd/system/gatekeeperd-auto-deploy.service \
  /etc/systemd/system/gatekeeperd-auto-deploy.timer /etc/gatekeeperd/auto-deploy.env
sudo systemctl daemon-reload
sudo install -d -o root -g root -m 0755 /opt/gatekeeperd
sudo install -o root -g root -m 0755 "${ARTIFACT_DIR}/gatekeeperd-all.jar" /opt/gatekeeperd/gatekeeperd-all.jar
sudo tee /etc/systemd/system/gatekeeperd.service >/dev/null <<'EOF'
[Unit]
Description=Gatekeeperd bootstrap service
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/gatekeeperd
EnvironmentFile=/etc/gatekeeperd/gatekeeperd.env
ExecStart=/usr/bin/java -jar /opt/gatekeeperd/gatekeeperd-all.jar -port=8080
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
```

Start and verify the legacy service before asking `deploy.sh` to migrate it:

```bash
sudo systemctl enable --now gatekeeperd.service
sudo systemctl status gatekeeperd.service --no-pager -l
curl -i --max-time 5 http://127.0.0.1:8080/api/health/live
```

Confirm Nginx still points at the legacy port and its configuration is valid, then
download the deployment script if it is not already present and deploy the same
successful CI build:

```bash
curl -fsSLo deploy.sh https://raw.githubusercontent.com/mikesplore/gatekeeperd/main/deploy.sh
chmod +x deploy.sh
sudo nginx -t
./deploy.sh --force
```

If `gatekeeperd.service` does not start or Nginx points somewhere other than `8080`,
stop here and inspect the service journal and upstream config. Do not delete the
database, Redis data, environment files, or Nginx configuration to work around that
precondition. Remove the temporary artifact directory when the deployment succeeds:

```bash
rm -rf -- "${ARTIFACT_DIR}"
```

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
