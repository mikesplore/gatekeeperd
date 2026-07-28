# Gatekeeperd — Project Index

## Overview

Gatekeeperd is a **Ktor-based payment gating engine** for Docker-hosted client projects. It acts as a Traefik ForwardAuth middleware, intercepting incoming requests and returning either a 200 OK (active/paying clients) or a 402 Payment Required paywall (overdue/non-paying clients). Payment processing is handled via Paystack webhooks. Redis provides fast caching of project gating status, PostgreSQL stores project/payment/audit data long-term.

---

## Architecture

```
                ┌──────────────┐
                │   Internet   │
                └──────┬───────┘
                       │
                  ┌────▼────┐
                  │  Traefik │  (reverse proxy, TLS termination)
                  │  v3.1    │
                  └────┬────┘
                       │
             ┌─────────▼──────────┐
             │  /api/gate/check   │  ← ForwardAuth middleware
             │  (Gatekeeperd)     │
             └─────────┬──────────┘
                       │
             ┌─────────▼──────────┐
             │  Client Container  │  ← only reached if gate returns 200
             │  (app/vm/site)     │
             └────────────────────┘
```

## Source Layout

```
src/main/kotlin/com/gatekeeper/
├── Application.kt                 # Entry point — wires all plugins, routes, and startup jobs
├── config/
│   └── AppConfig.kt               # Reads .env / system env → typed properties
├── api/
│   └── ErrorResponse.kt           # Uniform error + payment-required response types
├── plugins/                       # Ktor plugins installed at startup
│   ├── Serialization.kt           # ContentNegotiation + kotlinx.json
│   ├── Monitoring.kt              # CallLogging + StatusPages (global error handler)
│   ├── Security.kt                # CORS, DefaultHeaders, JWT auth
│   ├── Database.kt                # HikariCP pool + Exposed init + schema creation
│   ├── Redis.kt                   # JedisPool singleton
│   └── Routing.kt                 # Health check + Docker container admin endpoints
├── docker/
│   ├── DockerService.kt           # Wraps docker-java for container/network management
│   └── DockerModels.kt            # ContainerInfo, NetworkInfo, GateResult (sealed class)
├── gate/
│   ├── GateService.kt             # Core gating logic (Redis→Postgres→FAIL_MODE)
│   ├── GateRoutes.kt              # GET /api/gate/check endpoint
│   └── PaywallTemplates.kt        # HTML + JSON blocked-project responses
├── auth/
│   └── AuthRoutes.kt              # POST /api/auth/login (BCrypt + JWT)
├── admin/
│   └── ProjectAdminRoutes.kt      # JWT-protected CRUD + block/unblock
└── db/
    ├── tables/
    │   ├── Projects.kt            # projects table
    │   ├── Payments.kt            # payments table
    │   ├── AuditLog.kt            # audit_log table
    │   └── Users.kt               # users table (admin accounts)
    └── repositories/
        ├── ProjectRepository.kt   # + create/update/updateStatus/findPastDue + Redis invalidation
        ├── PaymentRepository.kt   # payment CRUD + markSuccess
        └── AuditRepository.kt     # audit log queries
```

## Key Data Flow — Gate Check

```
GET /api/gate/check?project={slug}
         │
         ▼
  ┌──────────────────┐
  │ Redis cache hit?  │──Yes──► Return cached status
  └────────┬─────────┘          (active → 200, blocked → 402)
           │ No
           ▼
  ┌──────────────────┐
  │ Postgres query    │──Found──► Cache in Redis (TTL 60s)
  │ by slug           │          Return status (200 / 402)
  └────────┬─────────┘
           │ Not found
           ▼
  ┌──────────────────┐
  │ Both unreachable? │──Yes──► Apply FAIL_MODE env var
  └──────────────────┘          (open → 200, closed → 402)
                                Log CRITICAL alert
```

## Redis Key Scheme

| Key | Value | TTL |
|-----|-------|-----|
| `project:status:{slug}` | `"active"` or `"blocked"` | 60s |

Short TTL ensures self-healing if cache invalidation is missed. Explicit deletes on admin/webhook status changes provide instant propagation.

## Dependencies

| Category | Library | Purpose |
|----------|---------|---------|
| Server | Ktor (Netty) | HTTP framework |
| DB | Exposed + PostgreSQL | ORM + persistence |
| Cache | Jedis (Redis) | Status caching |
| Docker | docker-java | Container/network management |
| Auth | JWT (auth0) + jbcrypt | Admin API auth |
| Payments | Paystack API (via Ktor Client) | Payment link generation + webhook handling |
| Config | dotenv-kotlin | Environment variable loading |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/gatekeeper` | Postgres JDBC URL |
| `DB_USER` | `gatekeeper` | DB user |
| `DB_PASSWORD` | `changeme` | DB password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `JWT_SECRET` | `changeme-use-a-long-random-string` | HMAC256 secret |
| `JWT_ISSUER` | `gatekeeperd` | JWT issuer claim |
| `JWT_AUDIENCE` | `gatekeeperd-admin` | JWT audience claim |
| `PAYSTACK_SECRET_KEY` | `sk_live_xxx` | Paystack API secret |
| `PAYSTACK_PUBLIC_KEY` | `pk_live_xxx` | Paystack API public key |
| `DOCKER_SOCKET` | `unix:///var/run/docker.sock` | Docker daemon socket |
| `GATEKEEPER_INTERNAL_NETWORK` | `gatekeeper-internal` | Docker network for inter-container comms |
| `DEFAULT_GRACE_PERIOD_DAYS` | `3` | Days past due_date before auto-block |
| `FAIL_MODE` | `open` | `open` → allow traffic on outage; `closed` → block |

## Phase Status

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | Project setup, deps, infra (Docker Compose) | ✅ Done |
| 1 | Foundation + Docker integration (Ktor bootstrap, DockerService, networking) | ✅ Done |
| 2 | **Gatekeeper Core** (gate/check, Redis caching, PaywallTemplates, FAIL_MODE) | ✅ Done |
| 3 | **Data & Auth** (full DB schema, repositories, JWT auth, admin CRUD) | ✅ Done |
| 4 | **Paystack & Automation** (payment links, webhooks, auto-blocker, notifications) | ✅ Done |
