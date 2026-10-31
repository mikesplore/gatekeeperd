# Deployment smoke test

This external API smoke test targets a running local or staging Gatekeeperd instance. It logs in, creates or reuses a dedicated customer, creates a project and service, and makes three real deployments:

1. Deploy the public `postgres:17-alpine` image with `POSTGRES_PASSWORD`; expect it to become active.
2. Remove `POSTGRES_PASSWORD`; the official image exits, and the previous active deployment must remain active.
3. Restore the password; expect deployment 3 to become active.

No image build, registry account, or image environment variables are needed. Gatekeeperd pulls the official image from Docker Hub. If `DOCKER_ALLOWED_REGISTRIES` is configured, `docker.io` must be allowed. The image exposes PostgreSQL on port 5432; the smoke test does not connect to or store customer data in it.

The script deletes its test service (including its deployment containers and domain) and archives its project. The API has no customer-delete endpoint, so the dedicated smoke-test customer is reused across runs. Pass `--keep-resources` to leave the project and service for investigation.

## Run against local or staging

Requires Python 3, a reachable Gatekeeperd instance, Docker access on that instance, and an enabled deployment worker.

```bash
export GATEKEEPER_SMOKE_TARGET=local # or staging
export GATEKEEPER_SMOKE_BASE_URL=http://localhost:8080
export GATEKEEPER_SMOKE_ADMIN_EMAIL=admin@example.net
export GATEKEEPER_SMOKE_ADMIN_PASSWORD='...'
# Optional; otherwise the script generates a random throwaway value.
export GATEKEEPER_SMOKE_DB_PASSWORD='smoke-password-123'

python3 scripts/deployment-smoke/deployment-smoke.py
```

Preflight prints each required variable as `SET` or `NOT SET`, with an example for each. It never echoes the admin password or database password. It also states the expected password setup for each deployment.

Use `GATEKEEPER_SMOKE_TARGET=local` with `http://localhost:8080` for local development. Remote targets must use HTTPS. The script accepts only `local` or `staging` and refuses hostnames containing `prod`.

The deployment wait timeout defaults to four minutes. Override it with `--timeout 360`.

## Optional Nginx checks

Pass `--nginx` to test TLS validation, config preview, HTTP-only enable, repeated enable, and that the site stays enabled after the deliberately failed deployment. The test also verifies the domain is released during service cleanup.

This option changes and reloads Nginx on the target. Use a disposable staging Nginx instance. It does not deliberately corrupt a live Nginx config or force a reload failure; those fault-injection cases require an isolated Nginx sandbox.

```bash
python3 scripts/deployment-smoke/deployment-smoke.py --nginx
```
