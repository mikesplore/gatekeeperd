# Gatekeeperd — Project Index

## Overview

Gatekeeperd is a **Ktor-based payment gating engine** for client projects on a VPS. It does not replace your reverse proxy — nginx or Traefik stays in front. Gatekeeperd answers one question per request: **should this traffic reach the client app?**

- **Active / paying** → `200 OK`, proxy forwards to the client container or process
- **Blocked / overdue** → `402 Payment Required` with HTML paywall (browser) or JSON (API clients)
- **Payment via Paystack** → webhook activates the project; Redis cache invalidated instantly

Project access status is separate from `blockReason`, which records why access is blocked (for example `manual`, `overdue`, or `payment_reversed`).

PostgreSQL stores projects, payments, audit log, and admin users. Redis caches gate status (60s TTL). Paystack handles checkout and payment confirmation.

---

## Architecture

### With nginx (current production pattern)

```
Internet
    │
    ▼
nginx (client domain, e.g. acw.example.com)
    │
    ├─ location / → auth_request /gatekeeper-auth-acw
    │     └─ GET /api/gate/auth?project={slug}             (403 = deny)
    │                          Gatekeeperd
    │
    ├─ on deny → @gatekeeper_paywall_acw → /api/gate/paywall?project={slug}
    │                                               (402 HTML paywall)
    │
    ├─ /api/gate/* proxied to Gatekeeperd                 (Pay Now, callback bypass auth_request)
    │
    └─ on allow → client app (e.g. localhost:9921)
```

See [nginx-client-gating.md](nginx-client-gating.md) for a full site config.

### With Traefik (alternative)

Traefik ForwardAuth calls `/api/gate/check?project={slug}` — returns `200` or `402` with paywall body directly.

---

## Client payment flow

1. Client visits site → nginx blocks → paywall page (project name, amount due, due date)
2. **Pay Now** → `GET /api/gate/pay?project={slug}` → redirect to Paystack checkout
3. After payment → browser redirects to the project domain from `/api/gate/payment/callback`
4. Paystack webhook → `POST /api/paystack/webhook` → project set to `active` and `due_date` cleared
5. With `due_date = null`, the auto-blocker stops tracking the project until an admin sets a new due date
6. If the payment is later reversed, Gatekeeper re-blocks the project and restores `due_date` to that reversal day

No admin action required to generate payment links for blocked clients.

---

## Source layout

```
src/main/kotlin/com/gatekeeper/
├── Application.kt              # Entry point, plugin wiring, initial admin seed
├── config/
│   └── AppConfig.kt            # .env / environment → typed config
├── api/
│   ├── ErrorResponse.kt        # Uniform error + payment-required JSON
│   ├── InputValidators.kt      # Slug, email, container name, date validation
│   └── dto/ApiDtos.kt          # Serializable admin API response types
├── plugins/
│   ├── Serialization.kt        # kotlinx.serialization JSON
│   ├── Monitoring.kt           # CallLogging + StatusPages
│   ├── Security.kt             # CORS, JWT auth
│   ├── Database.kt             # HikariCP + Exposed schema
│   ├── Redis.kt                # Jedis pool
│   └── Routing.kt              # Health + JWT Docker admin routes
├── gate/
│   ├── GateService.kt          # Redis → Postgres → FAIL_MODE logic
│   ├── GateResult.kt           # Active / Blocked / Unknown
│   ├── GateRoutes.kt           # /api/gate/* public gating + payment routes
│   ├── PaywallInfo.kt          # Paywall display model
│   └── PaywallTemplates.kt     # HTML paywall
├── auth/
│   └── AuthRoutes.kt           # POST /api/auth/login, GET /api/auth/me
├── admin/
│   └── ProjectAdminRoutes.kt   # JWT CRUD, block/unblock, delete, payment init
├── paystack/
│   ├── PaystackClient.kt       # Initialize + verify transactions
│   ├── PaystackModels.kt       # Request/response DTOs
│   ├── PaystackWebhookRoutes.kt
│   └── ProjectPaymentService.kt
├── docker/
│   ├── DockerService.kt
│   ├── DockerModels.kt
│   └── PullImageRequest.kt
├── scheduler/
│   └── AutoBlockerJob.kt         # Auto-block past due_date + grace period
└── db/
    ├── tables/                 # projects, payments, audit_log, users
    └── repositories/
```

---

## API surface

| Prefix | Auth | Purpose |
|--------|------|---------|
| `/api/health` | None | Liveness check |
| `/api/auth/login` | None | Admin JWT login |
| `/api/auth/me` | JWT | Admin profile |
| `/api/gate/auth` | None | nginx auth_request subrequest (200/403) |
| `/api/gate/check` | None | Traefik ForwardAuth / JSON gate check (200/402) |
| `/api/gate/paywall` | None | HTML paywall page |
| `/api/gate/pay` | None | Start Paystack checkout (suspended projects only) |
| `/api/gate/payment/callback` | None | Post-payment redirect |
| `/api/paystack/webhook` | HMAC signature | Payment confirmation → unblock |
| `/api/admin/*` | JWT | Projects, Docker, audit, payments |

Full reference: [API.md](API.md)

---

## Frontend wizard flows (recommended)

The admin dashboard should use a **wizard-like flow** for Docker container creation and nginx enablement so that later steps are not attempted when earlier prerequisites fail.

### Docker container creation wizard

1. **Network dropdown**: `GET /api/admin/containers/wizard/context` (or `GET /api/admin/networks`)
2. **Image check**: `POST /api/admin/images/status` (if missing → `POST /api/admin/images/pull`)
3. **Ports step**: `POST /api/admin/containers/wizard/ports/check`
4. **Validate + normalize**: `POST /api/admin/containers/wizard/validate` (no changes applied)
5. **Create container**: `POST /api/admin/containers/create` (includes restart policy, env vars, volume mounts)

For private Docker Hub images, set `pullViaCli=true` (or `DOCKER_PULL_VIA_CLI=true`) so pulls use `docker pull` and can reuse the host's Docker auth.

### Project creation wizard (container-first)

Gatekeeper enforces a container-first flow: you should create/start the Docker container **before** creating the project record.

1. **Container dropdown + slug hints**: `GET /api/admin/projects/wizard/context`
2. **Create project**: `POST /api/admin/projects` (`containerName` must exist; format `name` or `name:port`)

### Nginx enable wizard

1. **Context + options**: `GET /api/admin/nginx/wizard/context/{slug}` (cert list + inferred port hints)
2. **Validate + preview**: `POST /api/admin/nginx/wizard/validate/{slug}` (returns config preview; no changes applied)
3. **Apply**: `POST /api/admin/nginx/enable/{slug}`

---

## Gate check data flow

```
GET /api/gate/check?project={slug}
         │
         ▼
  Redis cache hit?  ──Yes──► active → 200, blocked → 402
         │ No
         ▼
  Postgres by slug  ──Found──► cache in Redis (60s), return status
         │ Not found
         ▼
  Redis + Postgres both down?  ──► FAIL_MODE (open → 200, closed → 402)
```

**Redis key:** `project:status:{slug}` → `"active"` | `"blocked"`, TTL 60s. Explicit delete on block/unblock/webhook/delete.

---

## Environment variables

Copy [.env.example](../.env.example) to `.env`. Required vars have no safe defaults.

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_URL` | Yes | Postgres JDBC URL |
| `DB_USER` | Yes | Postgres user |
| `DB_PASSWORD` | Yes | Postgres password |
| `REDIS_HOST` | Yes | Redis host |
| `REDIS_PORT` | No | Default `6379` |
| `JWT_SECRET` | Yes | HMAC256 signing secret |
| `JWT_ISSUER` | No | Default `gatekeeperd` |
| `JWT_AUDIENCE` | No | Default `gatekeeperd-admin` |
| `ADMIN_EMAIL` | First boot | Initial admin email (only when users table is empty) |
| `ADMIN_PASSWORD` | First boot | Initial admin password (min 8 chars) |
| `PAYSTACK_SECRET_KEY` | Payments | Paystack secret key |
| `PAYSTACK_PUBLIC_KEY` | Payments | Paystack public key |
| `GATEKEEPER_PUBLIC_URL` | Payments | Public HTTPS base URL, e.g. `https://gateapi.example.com` |
| `SUPPORT_CONTACT_EMAIL` | No | Shown on paywall pages |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated admin dashboard origins |
| `DOCKER_SOCKET` | No | Default `unix:///var/run/docker.sock` |
| `GATEKEEPER_INTERNAL_NETWORK` | No | Default `gatekeeper-internal` |
| `DEFAULT_GRACE_PERIOD_DAYS` | No | Default `3` |
| `FAIL_MODE` | No | `open` or `closed` |
| `AUTOBLOCKER_INTERVAL_MINUTES` | No | Auto-block scan interval; default `60` |

**Paystack dashboard (not env vars):**
- Webhook: `{GATEKEEPER_PUBLIC_URL}/api/paystack/webhook`
- Browser callback is set per transaction by the app: `{GATEKEEPER_PUBLIC_URL}/api/gate/payment/callback?project={slug}`

---

## Deployment

| Step | Where | Command |
|------|-------|---------|
| Build image | Dev machine | `./build.sh` |
| Push to registry | Dev machine | `./push.sh` |
| Deploy | VPS | `./deploy.sh` (pull + start postgres, redis, gatekeeperd) |
| Fresh DB | VPS | `./deploy.sh --fresh` |

Gatekeeperd API is typically exposed at a subdomain (e.g. `gateapi.example.com`) via nginx — see [nginx-reverse-proxy.md](nginx-reverse-proxy.md). Client sites get gating via [nginx-client-gating.md](nginx-client-gating.md).

Details: [staging-deployment.md](staging-deployment.md)

---

## Related repos

| Repo | Role |
|------|------|
| **gatekeeperd** (this) | Backend API + gating engine |
| **gatekeeperd-frontend** | React admin dashboard (Vercel) |

---

## Docs index

| File | Contents |
|------|----------|
| [API.md](API.md) | REST endpoints, request/response shapes |
| [nginx-client-gating.md](nginx-client-gating.md) | Gate client apps behind nginx |
| [nginx-reverse-proxy.md](nginx-reverse-proxy.md) | Expose gatekeeperd API behind nginx + SSL |
| [staging-deployment.md](staging-deployment.md) | VPS deploy workflow |
| [backend-development-plan.md](backend-development-plan.md) | Original phased build plan (historical) |

---

## Phase status

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | Project setup, deps, infra | ✅ Done |
| 1 | Docker integration, admin container API | ✅ Done |
| 2 | Gate core (check, Redis, paywall, FAIL_MODE) | ✅ Done |
| 3 | DB schema, JWT auth, admin CRUD | ✅ Done |
| 4 | Paystack, webhooks, auto-blocker | ✅ Done |
| 5 | nginx gating, self-service paywall, input validation | ✅ Done |
