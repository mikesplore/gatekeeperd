# AGENTS.md

## Project purpose

Gatekeeperd is a Kotlin/Ktor payment-gating backend for client applications hosted on a VPS. A reverse proxy (currently nginx, alternatively Traefik) calls Gatekeeperd to decide whether traffic may reach a client app. Active projects are allowed through; blocked or overdue projects receive a payment-required response/paywall. Paystack webhooks activate projects after payment.

The backend is paired with a separate `gatekeeperd-frontend` React admin dashboard. This repository contains the API, gating engine, persistence, payment integration, Docker integration, nginx management, and background jobs.

## Repository map

- `src/main/kotlin/com/gatekeeper/Application.kt` — application entry point, plugin wiring, initial admin seeding, and job startup.
- `src/main/kotlin/com/gatekeeper/config` — typed configuration loaded from environment variables.
- `src/main/kotlin/com/gatekeeper/plugins` — Ktor serialization, monitoring, security/JWT, database, Redis, and common routing setup.
- `src/main/kotlin/com/gatekeeper/api` — shared API errors, validation, and DTOs.
- `src/main/kotlin/com/gatekeeper/auth` — admin login and current-user endpoints.
- `src/main/kotlin/com/gatekeeper/admin` — JWT-protected project, payment, and nginx administration routes.
- `src/main/kotlin/com/gatekeeper/gate` — public gate checks, paywall rendering, and payment initiation/callback routes.
- `src/main/kotlin/com/gatekeeper/paystack` — Paystack client, transaction models, webhook handling, and project payment logic.
- `src/main/kotlin/com/gatekeeper/docker` — Docker Java API integration and container/image wizard support.
- `src/main/kotlin/com/gatekeeper/nginx` — nginx configuration generation, validation, certificates, and reload support.
- `src/main/kotlin/com/gatekeeper/db/tables` — Exposed table definitions for users, projects, payments, payment events, and audit logs.
- `src/main/kotlin/com/gatekeeper/db/repositories` — database access and persistence operations.
- `src/main/kotlin/com/gatekeeper/scheduler` — auto-blocking and payment reconciliation background jobs.
- `src/main/resources/application.conf` — Ktor deployment configuration; default port is `8080`.
- `src/test/kotlin` — Ktor/server, validator, Docker wizard, and nginx service tests.
- `docs/` — authoritative API, architecture, nginx, deployment, and project-index documentation.

## Stack and conventions

- Kotlin `2.4.0`, JVM toolchain `21`, Ktor `3.5.0`, and Gradle Kotlin DSL.
- PostgreSQL via HikariCP and JetBrains Exposed.
- Redis via Jedis; gate status cache keys are `project:status:{slug}` with a 60-second TTL.
- JWT with HMAC256 protects `/api/admin/*` and admin profile routes.
- Paystack is the payment provider; webhook signature verification is required for payment confirmation.
- Docker access is through `DOCKER_SOCKET` (normally `/var/run/docker.sock`).
- JSON uses kotlinx.serialization. Keep API DTOs serializable and preserve the documented response shapes in `docs/API.md`.
- Validate external input through `InputValidators` and return the project’s uniform error format rather than ad-hoc error bodies.
- Keep public gate routes unauthenticated, except the Paystack webhook which must validate its signature. Admin routes require the `auth-jwt` provider.

## Important runtime behavior

- `GET /api/health` is the unauthenticated liveness endpoint.
- `GET /api/gate/auth` is intended for nginx `auth_request` and must use `200`/`403` semantics; nginx does not handle `402` as a normal auth denial.
- `GET /api/gate/check` is intended for Traefik ForwardAuth and uses `200`/`402` semantics.
- `GET /api/gate/paywall` renders the HTML blocked-project paywall. `GET /api/gate/pay` starts Paystack checkout; the callback returns the browser to the client project.
- A successful Paystack webhook activates the project and clears its due date/cache. Reversal events re-block the project and restore the reversal date.
- `FAIL_MODE=open` allows traffic when both Redis and PostgreSQL are unavailable; `FAIL_MODE=closed` blocks it. Treat this as a deliberate availability/security choice.
- The initial admin is seeded only when the users table is empty, using `ADMIN_EMAIL` and `ADMIN_PASSWORD`; the password must be at least 8 characters.
- Auto-blocking uses `due_date` plus `DEFAULT_GRACE_PERIOD_DAYS`. A project with a cleared due date is not tracked by the auto-blocker until an admin sets a new due date.
- Docker and nginx integrations may be unavailable in local/test environments; startup is designed to degrade gracefully where documented.

## Environment and local development

1. Copy `.env.example` to `.env` and provide database, Redis, JWT, and (when testing payments) Paystack values.
2. Run PostgreSQL and Redis locally or through the project’s deployment infrastructure.
3. Start the service with `./gradlew run`.

Important variables include `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `PAYSTACK_SECRET_KEY`, `PAYSTACK_PUBLIC_KEY`, `GATEKEEPER_PUBLIC_URL`, `CORS_ALLOWED_ORIGINS`, `DOCKER_SOCKET`, `GATEKEEPER_INTERNAL_NETWORK`, `DEFAULT_GRACE_PERIOD_DAYS`, and `FAIL_MODE`. Never commit real secrets or local `.env` values.

## Commands and verification

Use the `rtk` command as the shell-command prefix, per `/home/mike/.codex/RTK.md`. Examples:

```bash
rtk ./gradlew test
rtk ./gradlew build
rtk ./gradlew run
rtk git status --short
```

The CI workflow runs `./gradlew --no-daemon test buildFatJar` on Java 21 and publishes `build/libs/gatekeeperd-all.jar` plus its SHA-256 file. Run the relevant tests after Kotlin/API changes, and run the full test/build command before handing off broad changes.

## Commit convention

Commit completed changes as part of the work unless the user explicitly asks not to commit. Before committing:

- run the relevant tests
- run `rtk git diff --check`
- inspect `rtk git status --short`
- stage only files belonging to the requested change
- leave unrelated existing modifications unstaged

Use a concise imperative commit message that describes the completed change.

## Documentation and deployment references

- `docs/README.md` is the project architecture/index and environment-variable reference.
- `docs/API.md` is the REST API contract.
- `docs/nginx-client-gating.md` documents client-site gating and the nginx `auth_request` setup.
- `docs/nginx-reverse-proxy.md` documents exposing Gatekeeperd itself behind nginx/SSL.
- `docs/staging-deployment.md` documents VPS/container deployment.
- `docs/backend-development-plan.md` is historical planning material; prefer current source and the other docs when they disagree.

## Change guidance

- Preserve existing user changes; do not modify unrelated `.idea` or generated/build files.
- For endpoint changes, update `docs/API.md` and any relevant nginx/frontend flow documentation.
- For configuration changes, update `.env.example` and `docs/README.md`.
- Be careful with payment, authentication, Docker-socket, nginx-file, and database changes: they affect production infrastructure and should include focused tests and explicit error handling.
- Do not use destructive git commands or expose secrets in logs, tests, documentation, or command output.
