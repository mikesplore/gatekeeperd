# Gatekeeperd

Gatekeeperd controls access to client applications based on payment status. Nginx or
Traefik checks Gatekeeperd before forwarding traffic; successful Paystack payments
restore access. The admin dashboard is maintained in the separate
`gatekeeperd-frontend` repository.

## First VPS install

The VPS needs an existing PostgreSQL database and Redis instance, plus an application
environment file with their connection details and the required JWT/payment settings.
The setup script provisions the Gatekeeperd runtime, Java, Docker, Nginx, TLS, systemd,
downloads the successful CI artifact, checks application readiness, and performs the
first blue/green deployment. It preserves PostgreSQL/Redis data, the supplied app env
file, existing Nginx configuration, and TLS certificates. It does not pull a source
checkout.

On the VPS, create `/etc/gatekeeperd/gatekeeperd.env` and set at least:

```text
DB_URL=jdbc:postgresql://DATABASE_HOST:5432/DATABASE_NAME
DB_USER=DATABASE_USER
DB_PASSWORD=DATABASE_PASSWORD
REDIS_HOST=REDIS_HOST
REDIS_PORT=6379
JWT_SECRET=REPLACE_WITH_A_LONG_RANDOM_SECRET
ADMIN_EMAIL=admin@example.com
ADMIN_PASSWORD=REPLACE_WITH_A_STRONG_PASSWORD
```

Add the Paystack and Scribed settings required by your installation. The setup script
sets the public URL and production Nginx settings from the hostname. Keep this file
secret and never commit it. PostgreSQL and Redis may be on this VPS or managed elsewhere.

Authenticate `gh` on the VPS with permission to read Actions artifacts, then run:

```bash
gh auth login
curl -fsSLo prepare-vps.sh https://raw.githubusercontent.com/mikesplore/gatekeeperd/main/scripts/prepare-vps.sh
chmod +x prepare-vps.sh
./prepare-vps.sh --domain api.example.com --reset-runtime
```

The script uses `SUPPORT_CONTACT_EMAIL` or `ADMIN_EMAIL` for the TLS contact. If the
public URL is already in the env file, `--domain` can be omitted.

For an existing installation that must be cleared and rebuilt, add
`--reset-runtime`. It removes the old Gatekeeperd runtime, systemd units, deploy
settings, managed Nginx site/upstream files, the dedicated Gatekeeperd TLS
certificate, and other files under `/etc/gatekeeperd`, preserving only
`/etc/gatekeeperd/gatekeeperd.env`. Shared TLS certificates and PostgreSQL/Redis data
are preserved. The script then recreates the service and Nginx setup. This causes
downtime during the reset and requires DNS to point at the VPS for a dedicated TLS
certificate to be issued again.
Use `--help` for options. For normal upgrades after setup, download and run
`deploy.sh`; do not run the preparation script as an upgrade tool.

If setup stops at readiness, the script prints the health response and the command to
inspect the bootstrap journal. Resolve the reported database, Redis, migration, or
configuration issue before retrying.

## Local development

Copy `.env.example` to `.env`, configure reachable PostgreSQL and Redis instances,
then run:

```bash
./gradlew run
```

To build and smoke-test the deployment JAR locally, run `./gradlew test buildFatJar`
and then `./scripts/deploy-local.sh`. It listens on port `18081`, waits for
`/api/health/ready`, and logs to `build/deploy-local.log`.

## Documentation

- [Staging and VPS deployment](docs/staging-deployment.md)
- [Backend architecture and environment variables](docs/README.md)
- [API reference](docs/API.md)
- [Nginx client gating](docs/nginx-client-gating.md)
- [Nginx reverse proxy](docs/nginx-reverse-proxy.md)
