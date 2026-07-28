# Gatekeeper Backend Engine — Implementation Plan (Ktor)

**Purpose of this document:** a build-ready spec. Any engineer or coding agent should be able to implement each phase directly from this without needing to ask clarifying questions.

---

## 0. Project Setup

### 0.1 Repo structure
```
gatekeeperd/
├── build.gradle.kts
├── settings.gradle.kts
├── docker-compose.yml
├── Dockerfile
├── .env.example
├── src/
│   ├── main/
│   │   ├── kotlin/com/gatekeeper/
│   │   │   ├── Application.kt
│   │   │   ├── plugins/
│   │   │   │   ├── Serialization.kt
│   │   │   │   ├── Routing.kt
│   │   │   │   ├── Security.kt
│   │   │   │   ├── Database.kt
│   │   │   │   ├── Redis.kt
│   │   │   │   └── Monitoring.kt
│   │   │   ├── docker/
│   │   │   │   ├── DockerService.kt
│   │   │   │   └── DockerModels.kt
│   │   │   ├── gate/
│   │   │   │   ├── GateRoutes.kt
│   │   │   │   ├── GateService.kt
│   │   │   │   └── PaywallTemplates.kt
│   │   │   ├── db/
│   │   │   │   ├── tables/
│   │   │   │   │   ├── Projects.kt
│   │   │   │   │   ├── Payments.kt
│   │   │   │   │   ├── AuditLog.kt
│   │   │   │   │   └── Users.kt
│   │   │   │   └── repositories/
│   │   │   │       ├── ProjectRepository.kt
│   │   │   │       ├── PaymentRepository.kt
│   │   │   │       └── AuditRepository.kt
│   │   │   ├── auth/
│   │   │   │   ├── JwtConfig.kt
│   │   │   │   └── AuthRoutes.kt
│   │   │   ├── admin/
│   │   │   │   └── ProjectAdminRoutes.kt
│   │   │   ├── paystack/
│   │   │   │   ├── PaystackClient.kt
│   │   │   │   ├── PaystackWebhookRoutes.kt
│   │   │   │   └── PaystackModels.kt
│   │   │   └── scheduler/
│   │   │       └── AutoBlockerJob.kt
│   │   └── resources/
│   │       ├── application.conf
│   │       └── logback.xml
│   └── test/kotlin/com/gatekeeper/...
```

### 0.2 Dependencies (`build.gradle.kts`)
```kotlin
val ktor_version = "2.3.12"
val exposed_version = "0.53.0"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-server-auth:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages:$ktor_version")
    implementation("io.ktor:ktor-server-cors:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")            // for calling Paystack API
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")

    implementation("com.github.docker-java:docker-java:3.4.0")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.0")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("redis.clients:jedis:5.1.5")

    implementation("org.mindrot:jbcrypt:0.4")                          // password hashing
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("io.ktor:ktor-server-tests:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
}
```

### 0.3 Environment variables (`.env.example`)
```
DB_URL=jdbc:postgresql://postgres:5432/gatekeeper
DB_USER=gatekeeper
DB_PASSWORD=changeme
REDIS_HOST=redis
REDIS_PORT=6379
JWT_SECRET=changeme-use-a-long-random-string
JWT_ISSUER=gatekeeperd
JWT_AUDIENCE=gatekeeperd-admin
PAYSTACK_SECRET_KEY=sk_live_xxx
PAYSTACK_PUBLIC_KEY=pk_live_xxx
DOCKER_SOCKET=unix:///var/run/docker.sock
GATEKEEPER_INTERNAL_NETWORK=gatekeeper-internal
DEFAULT_GRACE_PERIOD_DAYS=3
FAIL_MODE=open   # 'open' or 'closed' — behavior when Redis/Postgres unreachable
```

### 0.4 `docker-compose.yml` (core infra)
```yaml
version: "3.9"
services:
  traefik:
    image: traefik:v3.1
    command:
      - "--providers.docker=true"
      - "--providers.docker.exposedbydefault=false"
      - "--entrypoints.web.address=:80"
      - "--entrypoints.websecure.address=:443"
      - "--certificatesresolvers.le.acme.httpchallenge=true"
      - "--certificatesresolvers.le.acme.httpchallenge.entrypoint=web"
      - "--certificatesresolvers.le.acme.email=you@example.com"
      - "--certificatesresolvers.le.acme.storage=/letsencrypt/acme.json"
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - letsencrypt:/letsencrypt
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

  gatekeeperd:
    build: .
    env_file: .env
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    depends_on:
      - postgres
      - redis
    networks:
      - gatekeeper-internal
    # NOT exposed to Traefik directly for public routing except the admin dashboard subdomain, and NOT publicly reachable on the gate-check port

networks:
  gatekeeper-internal:
    external: false

volumes:
  pgdata:
  letsencrypt:
```

**Security note:** `gatekeeperd`'s `/api/gate/check` endpoint must only be reachable from Traefik on the internal Docker network — never bind it to a host port, and never give it a public Traefik router. Only the `/api/admin/*` and `/api/paystack/webhook` routes get a public Traefik router (on a subdomain like `admin.yourdomain.com`), protected by JWT / signature verification respectively.

---

## Phase 1: Foundation & Docker Integration (Days 1–2)

### 1.1 Ktor bootstrap
`Application.kt` wires plugins in this order: Serialization → CallLogging → StatusPages (global error handler → JSON error body) → CORS (admin routes only) → Authentication (JWT) → Routing.

`StatusPages` should catch:
- `SerializationException` → 400 with `{"error":"invalid_request"}`
- `NotFoundException` (custom) → 404
- Generic `Throwable` → 500, logged with stack trace, generic body to client (never leak internals)

### 1.2 DockerService
Wraps `docker-java`'s `DockerClient`, built once at startup from `DOCKER_SOCKET`.

Methods to implement:
```kotlin
interface DockerService {
    fun listContainers(all: Boolean = true): List<ContainerSummaryDto>
    fun getContainer(containerNameOrId: String): ContainerSummaryDto?
    fun startContainer(containerNameOrId: String)
    fun stopContainer(containerNameOrId: String, timeoutSeconds: Int = 10)
    fun restartContainer(containerNameOrId: String)
    fun pullImage(image: String, tag: String = "latest")
    fun listNetworks(): List<String>
    fun createNetworkIfMissing(name: String)
    fun containerHealth(containerNameOrId: String): String // "running" | "exited" | "unknown"
}
```
Important: **for the gating mechanism itself, do NOT stop/start containers.** Blocking is done purely at the Traefik/Gatekeeper response layer (see Phase 2) — this keeps unblocking instantaneous (no container cold-start delay) and keeps the app's in-memory state intact. `DockerService` is for: onboarding new projects (pulling images, verifying container exists/is running), admin dashboard container status display, and an optional "hard stop" admin action for cases beyond payment gating (e.g. decommissioning a project entirely).

### 1.3 Networking
On startup, call `createNetworkIfMissing(GATEKEEPER_INTERNAL_NETWORK)`. Document in onboarding runbook that every new client container must join this network, and its Traefik router must reference the `gatekeeper` forwardAuth middleware (see 2.1).

### 1.4 Deliverable for Phase 1
- `gatekeeperd` boots, connects to Docker socket, `GET /api/admin/containers` (JWT-protected, stub auth ok for now) returns live container list from the VPS.

---

## Phase 2: The Gatekeeper Core (Days 3–4)

### 2.1 Traefik wiring (per client project)
Example labels for a client container in its own `docker-compose.yml` or `docker run`:
```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.acme-corp.rule=Host(`acmecorp.com`)"
  - "traefik.http.routers.acme-corp.entrypoints=websecure"
  - "traefik.http.routers.acme-corp.tls.certresolver=le"
  - "traefik.http.routers.acme-corp.middlewares=gatekeeper-acme-corp@docker"
  - "traefik.http.middlewares.gatekeeper-acme-corp.forwardauth.address=http://gatekeeperd:8080/api/gate/check?project=acme-corp"
  - "traefik.http.middlewares.gatekeeper-acme-corp.forwardauth.trustForwardHeader=true"
  - "traefik.http.middlewares.gatekeeper-acme-corp.forwardauth.authResponseHeadersRegex=^X-Gate-.*"
networks:
  - gatekeeper-internal
```
`project=acme-corp` must match the `slug` column in the `projects` table exactly.

### 2.2 `/api/gate/check` endpoint
```
GET /api/gate/check?project={slug}
```
Logic (`GateService.check(slug: String): GateResult`):
1. Try Redis key `project:status:{slug}`.
   - Hit → use cached value (`active` | `blocked`).
   - Miss → query Postgres `projects` table by `slug`; if not found → treat as `blocked` with a generic "unknown project" response (fail closed for unknown slugs — this is a misconfiguration, not a payment case); if found → write value to Redis with TTL (e.g. 60s) and proceed.
2. If Postgres AND Redis both unreachable → apply `FAIL_MODE` env var:
   - `open` → return `active` (200), log a CRITICAL alert
   - `closed` → return `blocked` (502-style gate response), log CRITICAL alert
3. Return result to route handler.

Route handler behavior — **this is what Traefik's ForwardAuth actually receives**:
- `active` → HTTP `200 OK`, empty body. Traefik proceeds to route the original request to the client container.
- `blocked` → Traefik does **not** forward to the backend; it returns the ForwardAuth response verbatim to the original client. So the Gatekeeper response body/status IS what the end user or API consumer sees:
  - `project.type == "frontend"` → `402 Payment Required`, `Content-Type: text/html`, body = rendered paywall page (see 2.3) including the project's Paystack payment link
  - `project.type == "backend"` → `402 Payment Required`, `Content-Type: application/json`, body:
    ```json
    {
      "error": "payment_required",
      "message": "Access to this API is suspended pending payment.",
      "payment_link": "https://paystack.com/pay/xxxx",
      "contact": "you@example.com"
    }
    ```

### 2.3 PaywallTemplates
Simple server-rendered HTML (no templating engine needed — Kotlin `buildString` or raw `.html` string with placeholders is fine at this scale). Must include: client-facing message, your contact email, and the Paystack payment link button. Keep it a single static-ish template with `{{payment_link}}` and `{{project_name}}` substitution — don't over-engineer this.

### 2.4 Redis integration
`Redis.kt` sets up a `JedisPool` from env vars, exposed as a Ktor application attribute/DI singleton. Key scheme:
- `project:status:{slug}` → `"active"` or `"blocked"`, TTL 60s (short TTL as a safety net so stale cache self-heals even if invalidation is ever missed)
- On any admin/webhook status change, **explicitly delete** this key (don't wait for TTL) so unblock/block is instant.

### 2.5 Deliverable for Phase 2
- Two test projects (one `frontend`, one `backend`) routed through Traefik with the middleware attached.
- Toggling a row's `status` column in Postgres directly + flushing the Redis key manually shows the live behavior change within seconds — proves the mechanism before wiring the dashboard/webhooks in later phases.

---

## Phase 3: Data & Auth (Days 5–6)

### 3.1 Database schema (Exposed table definitions + equivalent raw SQL for reference)

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'admin',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projects (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                TEXT UNIQUE NOT NULL,
    name                TEXT NOT NULL,
    domain              TEXT NOT NULL,
    container_name      TEXT NOT NULL,
    type                TEXT NOT NULL CHECK (type IN ('frontend','backend')),
    status              TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','blocked','manual_block')),
    client_name         TEXT,
    client_email        TEXT,
    paystack_customer_code TEXT,
    amount_due          NUMERIC(12,2),
    currency            TEXT NOT NULL DEFAULT 'NGN',
    due_date            DATE,
    grace_period_days   INT NOT NULL DEFAULT 3,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id          UUID NOT NULL REFERENCES projects(id),
    paystack_reference  TEXT UNIQUE NOT NULL,
    amount              NUMERIC(12,2) NOT NULL,
    status              TEXT NOT NULL,
    paid_at             TIMESTAMPTZ,
    raw_webhook_payload JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID REFERENCES projects(id),
    action      TEXT NOT NULL CHECK (action IN ('blocked','unblocked','payment_received','manual_override','project_created','project_updated')),
    actor       TEXT NOT NULL, -- 'system' or user id/email
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_slug ON projects(slug);
CREATE INDEX idx_projects_due_date ON projects(due_date) WHERE status = 'active';
CREATE INDEX idx_payments_project_id ON payments(project_id);
CREATE INDEX idx_audit_log_project_id ON audit_log(project_id);
```

Use Exposed's `Table` objects mirroring this exactly; run migrations via a simple `SchemaUtils.createMissingTablesAndColumns()` on startup for this scale (or introduce Flyway if you want proper migrations later — not required for v1).

### 3.2 Repositories
`ProjectRepository`, `PaymentRepository`, `AuditRepository` — standard CRUD + specific query methods:
- `ProjectRepository.findBySlug(slug)`
- `ProjectRepository.updateStatus(id, status, actor, reason)` → **must** also write an `audit_log` row in the same DB transaction, and the caller must invalidate the Redis key immediately after commit.
- `ProjectRepository.findPastDue(asOf: LocalDate)` → for the auto-blocker job (Phase 4)

### 3.3 JWT Auth
`JwtConfig.kt`: HS256, secret from `JWT_SECRET` env var, issuer/audience from env, 24h expiry (configurable). Single `admin` role is sufficient for v1 — don't build RBAC now.

```
POST /api/auth/login
Body: {"email": "...", "password": "..."}
Response: {"token": "eyJ..."}
```
Password hashing via `jbcrypt`. Seed the first admin user via a startup script or one-time CLI command (`./gradlew run --args="create-admin email pass"`) rather than an open registration endpoint — this system should never have public signup.

All `/api/admin/*` routes require `authenticate("auth-jwt")`. The `/api/gate/check` route must NOT require auth (Traefik calls it directly, and it's not internet-reachable anyway per the network design). The `/api/paystack/webhook` route uses signature verification instead of JWT (Phase 4).

### 3.4 Admin CRUD endpoints (`ProjectAdminRoutes.kt`)
```
GET    /api/admin/projects              → list all, with status, client, due_date
GET    /api/admin/projects/{slug}       → single project detail + payment history + audit log
POST   /api/admin/projects              → create project record (does NOT deploy the container — that's a manual/devops step; this just registers it in Gatekeeper)
PATCH  /api/admin/projects/{slug}       → update fields (amount_due, due_date, client info, etc.)
POST   /api/admin/projects/{slug}/block    → manual_block, requires {"reason": "..."} in body
POST   /api/admin/projects/{slug}/unblock  → manual override to active, requires {"reason": "..."}
GET    /api/admin/projects/{slug}/audit    → audit log for the project
```

### 3.5 Deliverable for Phase 3
- Full CRUD dashboard-backing API functional, JWT-protected, tested with a real admin login.
- Manual block/unblock via API immediately reflected in live gate-check behavior (Redis invalidation confirmed working).

---

## Phase 4: Paystack & Automation (Days 7–8)

### 4.1 Generating payment links
`PaystackClient.kt` wraps Ktor's `HttpClient(CIO)` calling Paystack's REST API (`https://api.paystack.co`), auth header `Authorization: Bearer {PAYSTACK_SECRET_KEY}`.

For each project needing payment, generate a link via Paystack's **Initialize Transaction** endpoint (`POST /transaction/initialize`) with `amount` (in kobo, i.e. `amount_due * 100`), `email` (client_email), and `metadata` containing `{"project_slug": "acme-corp"}` — this metadata round-trips back in the webhook and is how you match payment → project without guessing from email alone.

Store the returned `reference` on a pending `payments` row (`status = 'pending'`) before sending the link to the client, so the webhook has something to match against.

### 4.2 Webhook handler
```
POST /api/paystack/webhook
```
Steps, in order — do not skip or reorder:
1. Read raw request body as string (needed for signature check — do NOT parse JSON first).
2. Compute HMAC-SHA512 of the raw body using `PAYSTACK_SECRET_KEY`, compare (constant-time comparison) against the `x-paystack-signature` header. **Reject with 401 if mismatch, log the attempt.**
3. Only after verification passes, parse JSON body.
4. Handle `event == "charge.success"`:
   - Extract `data.reference`, `data.amount`, `data.metadata.project_slug`, `data.status`
   - Look up `payments` row by `paystack_reference`. If not found, look up project by `metadata.project_slug` as fallback and create the payment row.
   - **Idempotency check:** if that payment row's `status` is already `success`, return 200 immediately without reprocessing (Paystack retries webhooks; duplicates must be safe no-ops).
   - In a single DB transaction: mark payment `success`, set `paid_at`, set project `status = 'active'`, write audit_log row (`action = 'payment_received'`, actor = `'system'`).
   - After commit: delete the Redis key `project:status:{slug}` so the next request re-caches as active immediately.
5. Always return `200 OK` quickly (Paystack expects fast ack) — do any slow/optional work (e.g. sending you a notification) after responding or in a background coroutine, not blocking the webhook response.
6. For unhandled event types, return `200 OK` with no action (never error on unknown-but-valid Paystack events).

### 4.3 Auto-blocker job
`AutoBlockerJob.kt`, launched from `Application.kt` at startup using `kotlinx-coroutines`:
```kotlin
fun startAutoBlockerJob(scope: CoroutineScope, projectRepo: ProjectRepository, redis: RedisService) {
    scope.launch {
        while (isActive) {
            runCatching {
                val overdue = projectRepo.findPastDue(LocalDate.now()) // due_date + grace_period_days < today AND status == 'active'
                overdue.forEach { project ->
                    projectRepo.updateStatus(project.id, "blocked", actor = "system", reason = "auto-block: payment overdue")
                    redis.delete("project:status:${project.slug}")
                    // trigger notification here (email/Slack) — see 4.4
                }
            }.onFailure { logger.error("AutoBlockerJob run failed", it) }
            delay(24.hours) // run once daily; consider a fixed time-of-day trigger instead of a rolling interval in production
        }
    }
}
```
Note: `findPastDue` query should be `due_date IS NOT NULL AND due_date + grace_period_days < CURRENT_DATE AND status = 'active'`.

### 4.4 Notifications
Minimal v1: a `NotificationService` interface with one implementation (email via SMTP, or a Slack/Telegram webhook — pick whichever you already use) firing on: `project_blocked`, `payment_received`, `webhook_signature_failed`. Don't over-build this — a single outbound webhook call per event is enough for v1.

### 4.5 Deliverable for Phase 4
- End-to-end test: create a project with `amount_due` and a real Paystack test-mode payment link, pay it in Paystack's test mode, confirm webhook fires, signature verifies, project flips to `active`, Redis cache clears, and a blocked test project becomes reachable within seconds without any manual step.
- Auto-blocker verified by manually setting a project's `due_date` in the past and confirming next job run blocks it and logs the audit entry.

---

## 5. Testing Checklist (apply across all phases)
- [ ] `/api/gate/check` returns 200 for active project, 402 (correct content-type per project type) for blocked
- [ ] Unknown project slug → blocked, logged as misconfiguration
- [ ] Redis down + Postgres up → gate-check still works (falls through to DB)
- [ ] Both Redis and Postgres down → behavior matches `FAIL_MODE`
- [ ] Webhook with invalid signature → 401, no DB changes
- [ ] Webhook replayed twice with same reference → processed once (idempotent)
- [ ] Manual block/unblock reflected in live traffic within 1–2 seconds
- [ ] Auto-blocker only touches projects actually past `due_date + grace_period_days`
- [ ] JWT-protected routes reject missing/expired/invalid tokens
- [ ] `/api/gate/check` is not reachable from outside the Docker internal network (verify via `curl` from the VPS host directly, not just from inside a container)

## 6. Explicitly Out of Scope for v1 (don't build these yet)
- Multi-admin RBAC / permissions tiers
- Non-Paystack payment providers
- Automatic container deploy/provisioning from the dashboard (onboarding a project still requires you to deploy the container + set Traefik labels manually, then register it via the admin API)
- Database migrations tooling (Flyway/Liquibase) — fine to rely on `SchemaUtils` at this scale
- Multi-VPS / multi-host support — this plan assumes one Docker host
