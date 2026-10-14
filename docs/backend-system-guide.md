# Gatekeeperd Backend System Guide

This guide describes the backend as implemented in this repository. It is intended for developers and operators who need to understand how a request or background task moves through the system, what state is persisted, which external services participate, and where failures are surfaced.

Use [API.md](API.md) for exact endpoint parameters and response schemas, [.env.example](../.env.example) for the environment template, and [staging-deployment.md](staging-deployment.md) for host deployment procedures. This guide explains how those pieces fit together.

## Table of contents

1. [System purpose and boundaries](#1-system-purpose-and-boundaries)
2. [Architecture at a glance](#2-architecture-at-a-glance)
3. [Startup, configuration, and process lifecycle](#3-startup-configuration-and-process-lifecycle)
4. [Core domain model](#4-core-domain-model)
5. [HTTP request pipeline and API groups](#5-http-request-pipeline-and-api-groups)
6. [Authentication and authorization](#6-authentication-and-authorization)
7. [Payment gating and access decisions](#7-payment-gating-and-access-decisions)
8. [Payments and provider callbacks](#8-payments-and-provider-callbacks)
9. [Customer-facing API and Scribed integration](#9-customer-facing-api-and-scribed-integration)
10. [Project and service administration](#10-project-and-service-administration)
11. [Deployment system](#11-deployment-system)
12. [Stable host-port allocation and blue/green cutover](#12-stable-host-port-allocation-and-bluegreen-cutover)
13. [Nginx and certificate management](#13-nginx-and-certificate-management)
14. [Docker management](#14-docker-management)
15. [Background workers and reconciliation](#15-background-workers-and-reconciliation)
16. [Persistence and database migrations](#16-persistence-and-database-migrations)
17. [Security and secret handling](#17-security-and-secret-handling)
18. [Logging, metrics, and tracing](#18-logging-metrics-and-tracing)
19. [Build, deployment, and version reporting](#19-build-deployment-and-version-reporting)
20. [Configuration reference](#20-configuration-reference)
21. [Failure behavior and operational diagnosis](#21-failure-behavior-and-operational-diagnosis)
22. [Implementation caveats and known gaps](#22-implementation-caveats-and-known-gaps)
23. [Repository map](#23-repository-map)
24. [Migration history](#24-migration-history)
25. [Related documentation](#25-related-documentation)

---

## 1. System purpose and boundaries

Gatekeeperd is a Kotlin/Ktor backend for payment status, customer billing workflows, and administration of applications hosted on a VPS. It has two related responsibilities:

- **Payment gate:** answer whether a project's or service's traffic should be passed to its application. A reverse proxy such as nginx or Traefik asks Gatekeeperd; Gatekeeperd does not replace that proxy.
- **Host management:** optionally build, launch, monitor, and switch Docker-based deployments, and manage nginx sites and certificates on the same host.

The payment gate and deployment runtime are separate. A Docker container can be running while its project is blocked. Conversely, a project's payment status can be active while its application deployment is unhealthy. The gate uses billing/access state; deployment reconciliation reports runtime and gateway health separately.

The backend is paired with the React dashboard in `/home/mike/Development/gatekeeperd-frontend`. Customer-facing portal screens are also supplied by a frontend; Gatekeeperd exposes customer-safe API data and redirects to the configured frontend URL rather than implementing a full customer website.

External systems in the current implementation include PostgreSQL, Redis, Docker Engine, nginx and Certbot on self-hosted deployments, Paystack, M-Pesa Daraja, Scribed, GitHub, and optionally Resend and an OTLP collector.

## 2. Architecture at a glance

```text
Browser / customer                 Admin dashboard
        │                                  │
        ├── client application              ├── /api/auth/*
        │        │                          └── /api/admin/*
        │        ▼                                  │
        │  nginx auth_request / Traefik              │ JWT
        │        │                                  ▼
        └────────┴────────────────────────── Gatekeeperd API
                                                   │
                       ┌───────────────────────────┼──────────────────┐
                       ▼                           ▼                  ▼
                 PostgreSQL                     Redis           Docker Engine
                 durable state                 cache/auth      deployment/runtime
                       │                                              │
                       ├── Paystack / M-Pesa                         └── nginx
                       ├── Scribed                                      stable upstreams
                       └── GitHub App/webhooks
```

Ktor route functions handle HTTP input and output. There are three broad implementation styles in the repository:

1. Newer payment, customer, and gate flows use application/use-case services registered in Koin, with domain interfaces and Exposed adapters.
2. Many administration flows call repository objects directly from route handlers. The migration toward a uniform service/repository architecture is incomplete; do not assume every feature follows the same layering.
3. Host operations such as Docker and nginx call system tools and files directly behind services, often with fakes or injected runners in unit tests.

## 3. Startup, configuration, and process lifecycle

### 3.1 Process entry point

`Application.kt` is the process entry point. Normal startup runs the `serve` command and starts Ktor Netty on `PORT` or `8080`, listening on `0.0.0.0`.

The application module runs startup in this order:

1. Log that configuration has been loaded without logging its secret values; check the configured port pool against Linux's ephemeral range and warn on overlap.
2. Install JSON serialization, request monitoring, and PostgreSQL/Flyway.
3. If `NGINX_DEPLOYMENT_UPSTREAMS_ENABLED=true`, bootstrap the per-service nginx upstream include directory and inspect `nginx -T` to confirm the configured include directory is included inside `http {}`. A missing or unwritable directory or missing nginx include fails startup. If disabled, startup logs that deployment cutovers require nginx configuration.
4. Initialize Redis and perform a startup PING. Redis unavailability is logged; it does not by itself stop application startup.
5. Register Koin dependencies, JWT security, common routes, gate/payment/customer/admin routes, and integration routes.
6. Seed the initial administrator if and only if `users` is empty and the initial admin values are valid.
7. Start payment reconciliation, overdue blocking, integration outbox delivery, and deployment worker coroutines.

Docker initialization is attempted as routing is configured. If the Docker socket is not usable, Docker-related routes return service-unavailable errors; the rest of the application can continue. Nginx upstream bootstrap is stricter when enabled, as noted above.

On application shutdown, Ktor hooks close PostgreSQL and Redis pools. The application also closes payment clients, Koin, and telemetry. Docker clients created for a request/job are closed by their owning code paths.

### 3.2 Environment loading and precedence

`AppConfig` reads process environment variables first and falls back to `.env` using dotenv. `.env` is optional as a file, but database credentials, Redis host, JWT secret, and `GATEKEEPERD_SELF_DOMAIN` are required by typed configuration. The `.env` lookup is relative to the process working directory, so a systemd unit must run from the directory containing the intended file or supply variables through its environment file.

Boolean and numeric values are parsed during configuration initialization. Invalid required settings or invalid port ranges fail early with an explanation. Optional integration credentials can be blank; the related feature then reports that it is not configured or unavailable.

### 3.3 Operational CLI commands

The same JAR supports operational commands before starting the server:

| Command | Behavior |
|---|---|
| `serve` (default) | Start Ktor service. |
| `migrate` | Run Flyway migrations then validate. |
| `migrate baseline` | Create a Flyway baseline at configured schema version. This is for existing databases, not ordinary startup. |
| `health-check` | Check PostgreSQL, Redis, and migration table; exit nonzero if not ready. |
| `backfill [sites-available] [--dry-run]` | Inspect nginx site files and reconcile discovered site records; default path comes from config. |
| `service-backfill --dry-run` | Report projects missing a default service; this command currently supports report-only operation. |

The HTTP liveness/readiness endpoints are described in [API.md](API.md#public-endpoints-no-auth).

## 4. Core domain model

### 4.1 Customer, project, and service

- A **customer** is a person or organization that owns projects and has contact/billing information.
- A **project** is the durable billing and access boundary. It has a unique slug, display name, project type (`frontend` or `backend`), payment/access status, block reason, due date, grace period, currency and billing data, and lifecycle/deployment descriptors.
- A **service** is a runtime within a project. It has stable identity, name, optional domain, default-service flag, and service-scoped access state. A project may contain multiple services. Older/single-service APIs select its default service where documented.
- A **site** is a gateway/nginx configuration associated with a project and optionally a service. It records domain, upstream mode and port, TLS/certificate settings, whether payment gating is enabled, bypass paths, custom nginx locations, and reconciliation state.

Project payment blocks apply to every service. Service access blocks affect only the matching service. Deployment health is not translated into a payment block.

Project `status`, project `blockReason`, structured `blockReasonCode`, and note are related but distinct. Service access has its own status/reason fields. Lifecycle and deployment mode are not interchangeable with payment status.

### 4.2 Runtime and payment records

- A **deployment configuration** stores the desired source/image, registry, ports, network, restart policy, volumes, environment references, readiness settings, and environment name.
- A **deployment execution** is the immutable resolved input snapshot used for one attempt, including selected environment/credential versions and resolved environment source metadata.
- A **deployment** is the durable lifecycle record for an attempt and resulting runtime. It stores status, runtime container identity, port mappings/bind IPs, image/network/runtime snapshots, cutover state, and links to the prior deployment it replaces.
- A **deployment job** is the worker queue/progress record. It stores its current step, bounded progress logs, claim token, commit/digest, cancellation and error state.
- A **deployment port allocation** persistently reserves pool ports by service, environment, slot, and container port.
- A **payment** records amount, project/service association, provider, provider reference, status, authorization URL, and verification/paid timestamps.
- A **payment event** is the durable receipt of a provider callback or reconciliation event. It records deduplication, payload, processing status/attempts, and failure detail.
- A **project adjustment** records administrative balance changes; these are audited separately from gateway payment transactions.

Environment variables are versioned. Service-level values override values imported from the selected shared environment set. Secret values are encrypted and are not returned by configuration reads. Deployment history refers to immutable secret and shared-set versions, making it possible to establish which version was used for an execution.

### 4.3 Other durable records

PostgreSQL also stores users, password-reset tokens, certificates, registry/provider credentials, GitHub App installation/webhook state, notifications, support requests, audit events, and the Scribed integration outbox. The database is the system of record for these items; Redis is not their durable replacement.

## 5. HTTP request pipeline and API groups

`configureMonitoring` assigns or preserves a validated `X-Request-ID`, adds it to response headers and logging MDC, creates an OpenTelemetry span, and installs API request logging and common error handling. Unhandled exceptions become the standard error response. Invalid JSON serialization/deserialization is handled centrally. Not-found invoice routes receive a specific invoice-unavailable error.

JSON serialization uses kotlinx.serialization. Most API errors use the common `{error, message, timestamp}` shape. Endpoint-specific response bodies and statuses are documented in [API.md](API.md).

| Route area | Auth | Main responsibilities | Main source files |
|---|---|---|---|
| `/api/health*`, `/api/version` | Public | Liveness, dependency readiness, locally recorded deployment version. | `plugins/Routing.kt`, `deployment/DeploymentVersionService.kt` |
| `/api/auth/*` | Login/reset public; profile and security token-protected | Login, refresh, logout/revocation, password/TOTP, profile and admin user management. | `auth/AuthRoutes.kt` |
| `/api/gate/*` | Public | Proxy access decision, paywall, checkout initiation, payment callback. | `gate/GateRoutes.kt`, payment presentation routes |
| `/api/customer/*` | Public/customer-safe | Project portal/status/payment receipts/support workflows. | `customer/CustomerRoutes.kt` |
| `/api/paystack/*`, `/api/mpesa/*` | Provider callbacks | Signed Paystack webhook, M-Pesa initiation/callback. | `feature/payment/presentation/*` |
| `/api/admin/projects*`, project setup | Admin JWT | Customer/project management, project setup, deployment/environment operations. | `admin/ProjectAdminRoutes.kt`, `admin/ProjectSetupAdminRoutes.kt` |
| `/api/admin/projects/{id}/services*` | Admin JWT | Service settings, active runtime, environment, access, and service billing. | `admin/ServiceAdminRoutes.kt` |
| `/api/admin/payments*` and payment events | Admin JWT | Payment history, revenue, cash entries, verification, event replay. | `feature/payment/presentation/PaymentAdminRoutes.kt` |
| `/api/admin/nginx*`, certificates | Admin JWT | Site config lifecycle, custom blocks, migration/repair, certificate inventory and renewal. | `admin/NginxAdminRoutes.kt`, `nginx/*` |
| `/api/admin/containers*`, networks, volumes, images | Admin JWT | Docker inventory and container wizard actions. | `plugins/Routing.kt`, `admin/ProjectAdminRoutes.kt`, `docker/*` |
| `/api/admin/logs`, audit, operations | Admin JWT | Recent application logs, audit history, dashboards, notification state, health and drift. | `admin/LogAdminRoutes.kt`, `ProjectAdminRoutes.kt`, `OperationsAdminRoutes.kt` |
| `/api/admin/integrations*`, GitHub | Admin JWT; GitHub callbacks separately verified | Scribed outbox, GitHub App setup/webhook activity. | `admin/IntegrationAdminRoutes.kt`, `integrations/*` |
| `/api/admin/registries*`, provider credentials | Admin JWT | Registry and provider configuration. | `admin/RegistryAdminRoutes.kt` |

`/api/admin/*` authentication is installed as a JWT provider. Some additional role checks exist within user management routes, but the shared JWT provider currently accepts only the `admin` role; see [known gaps](#22-implementation-caveats-and-known-gaps).

## 6. Authentication and authorization

### 6.1 Password login and tokens

Passwords are stored as BCrypt hashes. Login applies rate limits keyed by email and client address using Redis. A correct login either issues a short-lived 2FA challenge or creates an HMAC256 JWT plus opaque refresh token. Refresh-token hashes are keys in Redis; Redis storage is required for login, refresh, and revocation, so those flows fail closed when Redis cannot be reached.

JWTs include subject email, issuer, audience, `role`, a unique JWT ID (`jti`), issued-at, and a 24-hour expiry. The `auth-jwt` validation checks signature, issuer/audience, role, and the Redis revocation list. If Redis cannot be queried for revocation, authentication returns a service-unavailable error rather than accepting a token without the check.

### 6.2 Two-factor authentication

TOTP secrets are encrypted at rest. Recovery codes are stored as hashes and are single-use. Login with 2FA first returns a challenge; `/api/auth/2fa/verify` accepts a TOTP or recovery code. Failed attempts are counted and temporarily locked in Redis. User profile/security routes include setup, enable, disable, and recovery-code regeneration flows. Disabling 2FA requires the current password and a valid TOTP/recovery code.

### 6.3 Password recovery

Password reset tokens are random and only their hashes are persisted. Tokens have expiry and used timestamps. Email delivery depends on configured Resend credentials and reset URL. If delivery is not configured, password recovery cannot complete through email; an operator must use the available operational/admin path.

### 6.4 Authorization boundary

Public gate, payment initiation, callback, health and customer-safe endpoints do not require admin JWTs. Management APIs require `auth-jwt`.

There is a current mismatch: user-management APIs allow creation of `admin`, `operator`, and `viewer` records, but the shared JWT validator rejects any JWT whose role is not `admin`. Login itself can issue a token using the stored non-admin role, but calls to protected endpoints then fail authentication. Non-admin dashboard access therefore is not currently functional. Do not treat these role choices as implemented least-privilege roles yet.

## 7. Payment gating and access decisions

### 7.1 Proxy integration contracts

- **nginx `auth_request`:** call `GET /api/gate/auth`. It returns an empty `200` when allowed and empty `403` when blocked or unknown. nginx `auth_request` expects `401`/`403` denials; returning `402` directly from the subrequest can become an nginx 500.
- **Traefik ForwardAuth:** call `GET /api/gate/check`. It returns `200` when allowed and `402` when blocked. For browser requests, the endpoint can return HTML; for API clients it returns structured JSON.
- **Paywall:** `/api/gate/paywall` renders the blocked page; `/api/gate/pay` begins checkout. These routes are expected to be explicitly bypassed by the site gate so blocked customers can view the paywall and pay.

Full nginx examples and required bypass locations are in [nginx-client-gating.md](nginx-client-gating.md).

### 7.2 Effective access rule

The effective decision for a resolved target is blocked if the project status is not active or the selected service access status is not active. Project-level payment status applies to all services. A service block only applies to the service resolved from the requested domain/site. A configured payment reason takes precedence when both project and service are blocked.

The gate cache key prefix is `project:status:` and entries last 60 seconds. The Redis entry stores only effective `active` or `blocked` state. When a blocked cached entry is found, Gatekeeperd re-resolves the target from PostgreSQL to build current project/service-specific paywall details. Unknown cached states are not treated as active.

When cache lookup fails, the service queries PostgreSQL, returns the current decision, and attempts a best-effort cache write. A cache write failure does not change a successful database-backed decision. When both Redis and PostgreSQL are unavailable, `FAIL_MODE=open` allows traffic and records a critical log/metric; `FAIL_MODE=closed` blocks it. An unknown `FAIL_MODE` defaults closed. A missing project/site is unknown and denied by the proxy-facing endpoints; it is not treated as a database outage.

### 7.3 Cache invalidation and propagation

Administrative status changes and successful payment effects invalidate relevant project/site cache keys. Otherwise changes can remain cached for up to 60 seconds. Redis is a cache for gate state but is required storage for authentication state; those are intentionally different failure policies.

## 8. Payments and provider callbacks

### 8.1 Payment architecture

The payment feature is grouped under `feature/payment`:

- `domain/model` defines payments, event state, balances, and available methods.
- `domain/gateway` defines the provider boundary.
- `domain/repository` defines payment/event persistence contracts.
- `domain/usecase` coordinates initiation, provider callbacks, verification, reconciliation, dashboards, cash entries, and replay.
- `data/provider` implements Paystack and M-Pesa/Daraja clients and payload decoders.
- `data/persistence` implements Exposed repositories and adapters for project/service balances and payment effects.
- `presentation` exposes public, provider callback, and admin routes.

Koin wires the providers, repositories, adapters, and use cases in `plugins/DependencyInjection.kt`.

### 8.2 Checkout lifecycle

1. A blocked customer requests checkout through `/api/gate/pay` or M-Pesa initiation through `/api/mpesa/pay`.
2. `InitiatePayment` validates project and optional service selection, available amount, currency/provider availability, and payment constraints. Multi-service billing requires a service selection where no unambiguous project-level balance exists.
3. The provider gateway creates a checkout or payment request. Gatekeeperd stores a payment record with provider reference, amount and pending state before returning a redirect or pending response.
4. Paystack returns the browser to the callback route. This browser return is not the authoritative payment signal: Gatekeeperd verifies the reference with Paystack and records the outcome. The webhook is the normal asynchronous source of confirmation.
5. The provider event is deduplicated and processed; successful payment effects update access/billing state and invalidate gate cache. Reversals can restore a block/reversal due date.
6. Scheduled reconciliation verifies stale pending records with the provider and passes outcomes through the same event-processing path.

For project-scoped billing, a successful payment clears the project due date and activates it only once the remaining project balance is zero or less; partial payments are recorded without prematurely reactivating the project. For a service-scoped invoice, the service's automatic payment block is cleared when the successful amount covers the outstanding service balance. A reversal currently applies a project-level block with reason `payment_reversed` and sets its due date to the reversal day.

### 8.3 Paystack webhook integrity and idempotency

`POST /api/paystack/webhook` reads the raw request body and validates `x-paystack-signature` using HMAC-SHA512 and the configured Paystack secret before parsing the event. Invalid/missing signatures are rejected. The event handler persists and deduplicates the event and records processing status. A duplicate event is acknowledged as duplicate; processing failures are logged and acknowledged to Paystack with an outcome body so event retries do not blindly repeat side effects. Admin event inspection/replay and transaction reconciliation are separate recovery mechanisms.

The raw callback payload is persisted for event audit/replay. Treat this as sensitive operational data and restrict access to admin tools and database backups.

### 8.4 M-Pesa

M-Pesa initiation accepts project, optional service, phone, and amount, and returns an accepted/pending response with a reference. `POST /api/mpesa/callback` decodes the Daraja callback and delegates payment handling. Configuration includes consumer credentials, short code, passkey, environment, and callback URL. The M-Pesa client is closed at application shutdown. Provider availability is derived from configured readiness; payment methods can differ by currency.

### 8.5 Cash entries, adjustments, reconciliation, and admin views

Admin payment APIs expose listing, revenue/dashboard data, project payment history, provider verification, event search/replay, and cash payment recording. Cash/manual payment records go through a use case and are audited. Project adjustments are separate billing records and should not be confused with provider payment rows. Detailed request/response contracts are in [API.md](API.md).

## 9. Customer-facing API and Scribed integration

The customer API returns safe project billing/access state, payment history, receipts and portal/payment links. It supports receipt retrieval and support request persistence. Portal/payment-success links redirect to the configured `GATEKEEPER_FRONTEND_URL`; Gatekeeperd does not send email itself unless the corresponding notification integration is configured.

Scribed is a separate invoice and document/notification service. Gatekeeperd uses it for invoice lookup, service invoice balances, suspension notifications, payment notices/receipt workflows, and related integration calls. When checking service invoice state, a genuine not-found response is treated as “no service invoice”; transport errors and other non-success responses are logged and do not fabricate an amount due.

Outbound events that must survive a Gatekeeperd restart are written to `integration_outbox` with a unique idempotency key. The outbox worker leases/claims pending or retryable events, delivers them, marks successes delivered, and schedules retry on failure. This avoids making a successful local payment/status transaction depend on an immediate Scribed request.

Customer support requests and outbox records are durable PostgreSQL state. The customer APIs do not give customers general admin access.

## 10. Project and service administration

Project setup is intentionally staged:

1. Create the project and associate a customer.
2. Configure a source/runtime (repository or prebuilt image), registry, network, ports, volumes, readiness, and deployment environment.
3. Save credential and environment versions. Sensitive values are write-only in responses.
4. Save domain/gateway intent separately.
5. Queue deployment explicitly.
6. Create or enable the nginx site independently when its certificate and desired route are ready.

Saving configuration does not imply a deployment has run. A completed runtime deployment does not automatically enable a new nginx site. Existing enabled deployment-managed sites can be switched by worker cutover. `autoDeploy` configuration is for project repository automation/webhook flows; it is separate from the VPS-level GitHub Actions timer that updates Gatekeeperd itself.

Services have their own domains, runtime configuration, environment, deployment history and optional site. Project-wide access blocks still override each service. Admin service APIs handle service setup, access state, environment versions, active deployment/runtime summaries, and service billing. Project transfers, archive/delete, adjustments, and block/unblock changes are audit logged.

## 11. Deployment system

### 11.1 Desired configuration and immutable execution snapshot

An admin deployment request queues durable work. The desired configuration is resolved into a deployment execution snapshot before the worker acts. It captures repository/ref or prebuilt image, registry credential reference, environment and secret-set versions, volumes, network, readiness policy and trigger source. Environment values for an execution are reconstructed from the referenced immutable versions; changing a later environment version does not rewrite a past execution.

### 11.2 Worker lifecycle

`DeploymentWorker` runs as a coroutine and claims one queued job at a time. It uses a claim token to prevent another worker attempt from concurrently mutating that same job, and a PostgreSQL advisory lock keyed by service/environment to serialize deployments for that runtime. It recovers interrupted cutovers on startup and periodically repairs stale running jobs.

For a repository deployment, the worker clones the configured ref (using a GitHub App installation token when available, falling back to anonymous clone for public repositories), captures the resolved commit, builds and pushes the image, then pulls an immutable image identity on the host. For a prebuilt image, it pulls the requested image directly. It handles registry credentials, networks, cancellation, progress logs and candidate cleanup.

The candidate is created with resolved environment values, volumes, restart policy and required port mappings. Readiness uses configured/container readiness rules and bounded retries/timeouts. Image-declared TCP ports are discovered; when a managed route needs a primary port, an explicit container port is used or the image must expose exactly one unambiguous TCP port. Additional exposed TCP ports can also be mapped and checked. A successful readiness result is persisted before gateway cutover.

Lifecycle records distinguish queued/building/starting/health-checking/ready/active/superseded/failed/cancelled/rolled-back. “Ready” means the candidate passed runtime checks; it does not yet mean it serves the production gateway. Progress output is persisted on the deployment job and exposed through admin deployment APIs.

### 11.3 Runtime adoption and rollback

An already-running Docker container can be adopted into a project/service as an active deployment. The record captures its existing runtime and published mappings. During a later cutover, Gatekeeperd requires enough immutable image/runtime information to restore the old container safely. If an adopted prior runtime is missing or lacks required restore metadata, Gatekeeperd refuses to retire it rather than risk losing the currently serving app.

Failed candidates are cleaned or retained according to the failure stage. A cutover candidate that is healthy but fails nginx validation/probe can remain available for retry. Previous runtime snapshots support restoration if retiring the old runtime fails. Failed/cancelled/superseded runtime history is retained in the database even if its container is removed.

### 11.4 Worker recovery

On startup, the worker inspects deployment records with pending cutover state. It compares the persisted target include checksum with the current include and checks whether the candidate container is running. It either reloads a valid target and requeues continuation, restores the saved previous include, or marks the deployment `repair_required` when the live file matches neither persisted version or required recovery state is missing. This recovery takes the service/environment deployment lock. The nginx/deployment reconciliation endpoint itself is report-only; repair is an explicit administrative operation.

Stale claimed/running jobs are examined on a periodic loop. Recovery respects service/environment locking and claim ownership; it does not blindly launch a second candidate while another worker still owns the job.

## 12. Stable host-port allocation and blue/green cutover

### 12.1 Port ownership

Gatekeeperd-managed deployment ports come from `DEPLOYMENT_HOST_PORT_RANGE` (default `32000-40000`). Startup validates the range and warns if it overlaps Linux's `net.ipv4.ip_local_port_range`. Port allocation checks:

- persisted `deployment_port_allocations` uniqueness;
- actual host TCP listeners;
- Docker-published ports discovered from Docker inventory.

Managed host ports bind to `127.0.0.1`, so nginx can reach them locally without publishing them on every host interface. The database stores stable ports per service/environment/slot/container-port. Blue/green deployment chooses the slot not currently active. A bind race can still occur after allocation; Docker bind failure causes a bounded reallocation attempt from the configured pool.

Adopted containers keep their already-published host port in the active deployment record; Gatekeeperd does not retroactively reserve that port in the managed pool. If that port is within the configured pool it is observed as occupied during allocation. Adopted ports bound to `0.0.0.0` remain publicly exposed until that runtime is retired; exposure metadata is retained for the dashboard/runtime view.

### 12.2 Nginx route model

For deployment-managed sites, the site's nginx server config uses a stable upstream name derived from the service UUID: `gk_<service UUID without hyphens>`. The include file is stored by service ID and contains the active loopback port. Deployments rewrite this include, not the site server block; the site config remains stable across releases.

`upstream_kind` separates deployment-managed routes from explicit-port routes. Existing `EXPLICIT_PORT` sites that receive a managed deployment use their explicit upstream port as the primary **container** port on their first managed cutover, then are converted to deployment-managed routing. `DOCKER_DISCOVERY` routes resolve the attached runtime through Docker discovery. The internal host-pool cutover applies only to deployment-managed sites; developer-hosted explicit upstreams continue to use their configured explicit port path.

### 12.3 Cutover order and recovery safety

For a deployment-managed route, the worker:

1. Confirms candidate is running and probes its loopback host port with the site's Host header.
2. Persists previous runtime restoration metadata and pending nginx include content/checksum.
3. Acquires the shared `nginx-host-mutation` lock, atomically writes the candidate include, runs `nginx -t`, reloads nginx, and probes the route through local nginx using the site Host header and TLS/SNI where applicable. A failed validation restores previous include before returning; failed reload/probe attempts to restore the previous target and reload.
4. Persists the new active route and releases the host mutation lock.
5. Waits `DEPLOYMENT_DRAIN_DELAY_SECONDS` (default 5, 0-300 allowed), reacquires the shared lock for a post-drain probe, and restores the old route if the candidate is not healthy.
6. Only after successful post-drain verification stops and removes the prior runtime. If retirement fails, it attempts to restore the old runtime and gateway route.

The nginx host lock is shared with nginx site/config/certificate operations so host mutations do not interleave. It is held over the bounded include/test/reload/probe sequence, but not during the drain wait.

## 13. Nginx and certificate management

### 13.1 Desired state and files

PostgreSQL stores desired nginx site settings and the certificate inventory, while actual nginx config and symlinks live on disk. Gatekeeperd marks files it owns, validates inputs, creates backups/version hashes, tests candidate configs with `nginx -t`, and reloads nginx only after validation. Existing manually managed files are not silently overwritten. The configured self-domain list protects Gatekeeperd's own API vhost from site discovery, mutation, and cleanup.

Site options include `DOCKER_DISCOVERY` or `EXPLICIT_PORT` upstream resolution, `DEPLOYMENT_MANAGED` classification, TLS mode, auto-resolved or explicit certificate paths, gate enabled state, bypass paths, and user-defined custom location blocks. Core auth-request/paywall locations are rendered by Gatekeeperd and are not deleted by custom-block operations. Custom locations are validated and applied as per-site additions.

Deployment-managed sites use the stable upstream include in the nginx `http {}` context. Bootstrap uses `nginx -T`, verifies include configuration, and checks the directory is writable before serving when deployment upstreams are enabled. Production must have nginx load the exact configured `*.conf` directory inside `http {}`.

### 13.2 Host mutation lock and rollback

Operations that alter nginx site files, symlinks, upstream includes, or certificate references take the PostgreSQL advisory lock `nginx-host-mutation`. Site activation follows candidate write → `nginx -t` → enable/symlink as needed → reload. Failed validation/reload restores the previous file/symlink/include where possible and reports the failure. Nginx test/reload commands run through non-interactive `sudo`; the Gatekeeperd service account therefore needs narrowly scoped sudo permissions on the host.

### 13.3 Certificates

Certificate inventory is derived from files under the configured Certbot live directory and tracked in PostgreSQL with issuance/expiry/renewal attempt status. Site configurations can resolve an exact-domain certificate or a suitable parent-domain certificate, or use explicit certificate paths. Certificate deletion checks whether sites still reference the certificate.

Renewal is invoked through the root-owned `/usr/local/sbin/gatekeeperd-certbot` helper and a restricted sudoers rule installed by the deployment script. The helper invokes Certbot for a requested domain; it does not itself promise that DNS, challenge reachability, renewal configuration, or nginx certificate loading are valid. Nginx config test/reload and expiry inspection are part of the subsequent management flow. Check both Gatekeeperd operation errors and system logs for Certbot/sudo details.

## 14. Docker management

`DockerService` connects to the configured Docker socket. Admin APIs support container inventory/detail, health and logs, start/stop/restart, image pull, network management, volume management, and guided creation. The wizard validates input and host port availability before creating containers. Database presets create persistent named volumes and credentials; Redis authentication is optional. Containers on the configured internal Docker network can reach each other by container name and service port. Host processes use loopback plus the container's published host port.

Deployment candidates are created by the deployment worker rather than through the generic container wizard. Their mappings are port-pool allocations when managed by Gatekeeperd. Docker image pulls may use the Java Docker client or the host CLI (`DOCKER_PULL_VIA_CLI`) to reuse host registry credentials.

Optional `DOCKER_ALLOWED_REGISTRIES` and `DOCKER_ALLOWED_VOLUME_ROOTS` restrict which image registries and host bind-mount roots the API accepts. Empty allowlists preserve legacy unrestricted behavior. Docker cleanup/pruning excludes active and otherwise retained images as required by the cleanup operation.

## 15. Background workers and reconciliation

| Worker | Initial delay / interval | Responsibility | Failure approach |
|---|---|---|---|
| Auto blocker | Starts after about 10 seconds; default every 60 minutes. | Blocks project invoices past due plus project grace period; checks Scribed service invoice balances and auto-blocks overdue services. Sends Scribed notices. | Logs failures; run loop uses exponential backoff capped by configured interval. |
| Payment reconciliation | Starts after about 1 minute; default every 15 minutes. | Rechecks provider transactions that have remained pending past `RECONCILIATION_STALE_MINUTES` (default 30). | Durable payment events and retryable later runs. |
| Integration outbox | Starts immediately; 60-second interval. | Claims Scribed notification/integration events, delivers, marks delivered or retryable failure. | Durable status, attempt count, backoff/lease in PostgreSQL. |
| Deployment worker | Starts on application startup; polls about once per second. | Claims and processes queued deployments, recovers interrupted cutovers on startup, checks stale running jobs about once per minute. | Persisted job state, claim token, advisory locks and recovery snapshots. |

Scheduled workers use `runWorkerLoop`, which takes a PostgreSQL advisory lock for the worker name, records run/success/failure metrics, logs errors and applies bounded exponential backoff. The deployment worker instead uses job claim ownership and service/environment locks for the parts that mutate one runtime.

Nginx site reconciliation and deployment reconciliation compare persisted state to actual nginx/Docker state and surface drift/errors. The deployment reconciliation report does not automatically modify the host. Use a specific admin repair/migration action to change state.

## 16. Persistence and database migrations

### 16.1 Storage choices

- **PostgreSQL:** source of truth for projects, services, payments/events, user accounts, deployment configuration/executions/runtimes, environment versions, port reservations, sites/certificates, audit, notifications, support requests, and outbox deliveries.
- **Redis:** short-lived gate cache, login rate-limit counters, 2FA challenge/lock state, refresh-token hashes, and JWT revocations. It is required for protected auth operations but optional for ordinary cache reads.
- **Filesystem:** nginx site/config backups, per-service upstream include files, Certbot certificates, operational deployment marker files, and optionally GitHub App private key. These are host resources, not PostgreSQL rows.
- **Docker Engine:** actual container/image/network/volume runtime state. Gatekeeperd persists enough metadata to compare and recover but Docker is the authority for what is currently running.

### 16.2 Database setup

PostgreSQL uses HikariCP with a 10-connection maximum, 2 minimum idle connections, 5-second connection timeout, and Exposed transactions. Startup runs Flyway migration then validation. Fresh databases use `DB_MIGRATION_BASELINE_VERSION=0`. Existing databases must be baselined at their current schema version as documented in the migration guide; do not replay the fresh-schema migration over an already-created production schema.

Flyway migrations are in `src/main/resources/db/migration`. V1 is the consolidated current schema for fresh installations; subsequent numbered migrations evolve that schema. The migration runner includes `migrate`, `baseline`, and `validate` operations.

### 16.3 Transaction and concurrency controls

Repositories use Exposed transactions for database state. PostgreSQL advisory locks (`DistributedLock`) coordinate named workers and host mutations across processes. Locks are derived from a stable hash of their logical key. In tests without an initialized database, the lock utility uses process-local locks.

The outbox, payment event dedupe key, deployment claim token, unique host-port allocation and service/environment locks each protect a different race; none should be removed as “duplicate” protection without analyzing its specific invariant.

## 17. Security and secret handling

- Require TLS at the public reverse proxy for dashboard/customer/provider traffic.
- Keep `.env`, JWT secret, provider secrets, database password, Scribed integration secret/API token, and GitHub credentials out of source control and logs.
- Admin JWT uses HMAC256 and Redis-backed revocation. Refresh tokens are random, stored hashed, rotated on refresh, and revocable.
- Paystack webhooks validate the raw-body HMAC signature before processing.
- Passwords use BCrypt; TOTP secrets are encrypted; recovery and reset tokens are stored as hashes.
- Deployment environment secret values are encrypted using the configured `DEPLOYMENT_SECRETS_KEY`, and API reads do not return secret values. Secret versions preserve which configuration was used by each run.
- Registry credentials are referenced by ID/scope in deployment configuration rather than copied into ordinary responses.
- Nginx inputs (hostnames, site identifiers, cert paths, custom locations, upstream ports) are validated before being used in generated config or shell operations. Nginx and Certbot privileged operations use non-interactive restricted sudo rules.
- Docker socket access is effectively host-root capability. Restrict operating-system access to the socket and use registry/volume allowlists where appropriate.
- Public `/api/version` discloses deployment commit identifiers/status but not credentials or artifacts. Public health exposes liveness/readiness booleans only.
- Avoid logging passwords, tokens, env values, callback secrets, or raw authorization headers. Payment webhook raw bodies are persisted for event investigation and should be access-controlled.

## 18. Logging, metrics, and tracing

Application logs use Logback and SLF4J. Ktor API calls are logged; each request has a correlation ID (`X-Request-ID`) that is returned and added to MDC. Use that ID to correlate a dashboard/API request with backend logs. The admin logs endpoint provides recent process logs for the dashboard; it is a view of application logs, not a long-term database log archive.

`Metrics` maintains application counters for gate decisions, webhooks, worker runs and failures, and related operations. OpenTelemetry creates request and blocking-operation spans; export is optional through OTLP configuration. Metrics/traces are process-local unless an external observability exporter/collector is configured.

For production debugging, inspect the service journal as well as the dashboard logs. Docker, nginx, sudo, and Certbot may emit details outside Gatekeeperd's application logger.

## 19. Build, deployment, and version reporting

CI uses Java 21 and runs `./gradlew --no-daemon test buildFatJar`. It publishes `gatekeeperd-all.jar`, SHA-256 checksums, Certbot helper and deployment-tool archive. `deploy.sh` downloads an exact successful GitHub Actions artifact, verifies checksums, installs the JAR and helper/configuration, restarts the systemd service, checks `/api/health/live`, and restores the prior JAR if restart/liveness verification fails.

After the first deployment with the current tools, deploy script installs a VPS systemd timer that checks the latest successful `build.yml` run on `main` every five minutes using the VPS GitHub CLI authentication. It deploys a new successful commit automatically. This is host software delivery, separate from application container deployments managed from the dashboard.

The timer writes:

- `/opt/gatekeeperd/source-commit.txt` — running artifact source SHA, updated after a successful JAR rollout;
- `/opt/gatekeeperd/latest-successful-build.txt` — latest build SHA and UTC check time;
- `/opt/gatekeeperd/auto-deploy-failed-commit.txt` — a commit whose rollout failed and should not be retried endlessly.

`GET /api/version` reads these local files, reports `up_to_date`, `update_available`, `deployment_failed`, `check_stale` or `unknown`, and does not query GitHub per API request. A GitHub connectivity failure can leave old metadata; status becomes stale after 15 minutes.

The backend project’s AGENTS instructions require Gradle builds to run on the host with permission. Do not use a custom Gradle installation or alternate Gradle cache for verification. The frontend is built in its own repository with the configured Node/NVM toolchain.

## 20. Configuration reference

See [.env.example](../.env.example) for all values and deployment comments. The main groups are:

| Group | Variables | Purpose |
|---|---|---|
| Database | `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_MIGRATION_BASELINE_VERSION` | PostgreSQL connection and existing-schema baseline. |
| Redis | `REDIS_HOST`, `REDIS_PORT` | Cache and authentication-state store. Use a reachable Docker service name when Gatekeeperd itself is in Docker, or a published loopback/host address when it runs on the host. |
| Admin auth | `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` | JWT configuration and one-time initial admin seed. |
| Payment | `PAYSTACK_SECRET_KEY`, `PAYSTACK_PUBLIC_KEY`, `MPESA_*`, `GATEKEEPER_PUBLIC_URL`, `SUPPORT_CONTACT_EMAIL` | Provider credentials, callback addresses and paywall contact. |
| Customer/integrations | `GATEKEEPER_FRONTEND_URL`, `SCRIBED_*`, `RESEND_*`, `PASSWORD_RESET_URL` | Portal redirects, Scribed sync/notifications and password-reset email. |
| GitHub | `GITHUB_APP_*`, `GITHUB_WEBHOOK_SECRET` | Private repository clone, deployment webhook and repository integration. |
| Deployment | `DEPLOYMENT_HOST_PORT_RANGE`, `DEPLOYMENT_DRAIN_DELAY_SECONDS`, `DEPLOYMENT_STALE_MINUTES`, `DEPLOYMENT_SECRETS_KEY` | Stable host-port pool, cutover drain, job stale threshold and encrypted env storage. |
| Docker | `DOCKER_SOCKET`, `DOCKER_PULL_VIA_CLI`, `DOCKER_ALLOWED_REGISTRIES`, `DOCKER_ALLOWED_VOLUME_ROOTS`, `GATEKEEPER_INTERNAL_NETWORK` | Host runtime access and optional allowlists. |
| Nginx | `NGINX_DEPLOYMENT_UPSTREAMS_ENABLED`, `NGINX_UPSTREAM_INCLUDE_DIR`, `NGINX_SITES_AVAILABLE`, `NGINX_SITES_ENABLED`, `NGINX_SSL_CERT_PATH`, `GATEKEEPERD_SELF_DOMAIN` | Nginx integration, paths, stable include, and self-site exclusion. |
| Gate/workers | `DEFAULT_GRACE_PERIOD_DAYS`, `FAIL_MODE`, `AUTOBLOCKER_INTERVAL_MINUTES`, `RECONCILIATION_STALE_MINUTES`, `RECONCILIATION_INTERVAL_MINUTES` | Payment timing, outage policy, and worker cadence. |
| Cross-cutting | `CORS_ALLOWED_ORIGINS`, `PORT`, OTLP environment | Dashboard origins, HTTP listener and telemetry export. |

Configuration values are read once into `AppConfig` at startup. Most changes require a service restart. Changing port range must be coordinated with host listeners and existing database allocations.

## 21. Failure behavior and operational diagnosis

| Symptom | Likely layer | First checks |
|---|---|---|
| Process exits before listening, database/Flyway error | Configuration or PostgreSQL/schema | systemd journal; `DB_URL`, credentials, connectivity, Flyway baseline/history. |
| Startup fails mentioning nginx include directory or `nginx -T` | Nginx deployment-upstream bootstrap | Directory ownership/writability; `nginx -T`; confirm exact `*.conf` include is inside `http {}`; check NOPASSWD sudo command access. For local non-nginx development set `NGINX_DEPLOYMENT_UPSTREAMS_ENABLED=false`. |
| Health live works but readiness is 503 | PostgreSQL, Redis, or migrations | `/api/health/ready`; verify Redis from Gatekeeperd's own network namespace and credentials/TLS topology; inspect DB/migration state. |
| Login responds 503 | Redis-backed auth storage | Redis DNS/route/port from Gatekeeperd process/container, pool logs, and required refresh-token storage. A successful startup ping does not prove every later auth operation succeeds. |
| Login seems to work then dashboard returns 401 | JWT role/expiry/revocation | Confirm token role is `admin`; Redis revocation lookup must work. Current operator/viewer issue is documented below. |
| Gate is stale after admin/payment action | Redis gate cache | Verify invalidation logs and key `project:status:{slug}`; normal cache TTL is 60 seconds. |
| Candidate healthy but site still serves previous version | Deployment cutover/gateway | Inspect deployment status and cutover state; stable upstream include and checksum; nginx error log; `nginx -t`; local probe and host mutation lock; reconciliation report. |
| Candidate keeps changing public port | Not expected for managed deployments | Inspect persisted deployment port map, `deployment_port_allocations`, actual Docker mapping, and whether the site is deployment-managed or adopted/explicit. Managed routes use persisted pool ports; adoption preserves existing mappings. |
| Certificate renewal fails | Certbot / DNS / host mutation | Admin operation result, Gatekeeperd logs, `journalctl` sudo/Certbot records, DNS/challenge reachability, certificate files and nginx validation. The helper command log alone may not contain Certbot's full diagnostic. |
| Payment callback is received but status unchanged | Provider event processing | Verify signature/config, payment event status/error, provider reference, reconciliation, dedupe, and `payment_events`/`payments` rows. Browser redirect alone does not activate a project. |
| Scribed notification not delivered | Integration outbox or Scribed | Inspect outbox status/attempt/error, Scribed URL/token/secret, Scribed journal and admin outbox replay endpoint. |

For application-only logs use dashboard Logs or `journalctl -u gatekeeperd`. For container/service/system failures also inspect `docker logs`, `journalctl -u nginx`, nginx error logs, and Certbot/sudo journal entries.

## 22. Implementation caveats and known gaps

These observations are based on repository code, not an inspection of a production database or VPS:

1. **Non-admin user roles do not work for dashboard authentication.** User management accepts `operator` and `viewer`, but the shared `auth-jwt` validator rejects every role other than `admin`. Login can issue their token, then protected APIs reject it. A role/permission matrix and frontend route behavior are not implemented for those roles.
2. **Route layering is mixed.** Koin-based use cases exist for gate, customer and payment workflows, while several older admin APIs call repositories directly. Changes should follow local conventions but avoid assuming a universal domain boundary.
3. **Nginx and Docker are host-side dependencies.** Unit tests use fakes/temp dirs for most logic, but real sudoers, nginx include placement, systemd behavior, Docker networking, DNS, Certbot validation and file ownership still need server-side verification.
4. **Gate `FAIL_MODE` applies only when both the Redis cache path and PostgreSQL lookup fail.** A missing project is deliberately denied; Redis-only failure falls back to PostgreSQL.
5. **The public version endpoint reports the timer's latest known successful build, not an instantaneous query to GitHub.** If the timer cannot run or GitHub is unavailable, the result becomes stale/unknown rather than being a guaranteed live CI query.
6. **External Scribed and payment-provider outcomes depend on their own availability and configuration.** Outbox/reconciliation reduce transient coupling, but cannot guarantee a third-party service eventually accepts an event.

This guide is a source-based architecture explanation. Production-specific database contents, host service unit overrides, DNS, nginx state and credentials must be verified on the deployed host; they cannot be inferred from this checkout.

## 23. Repository map

```text
src/main/kotlin/com/gatekeeper/
├── Application.kt                  process entry point, startup wiring, seed, workers
├── admin/                           project, deployment, nginx, logs, operations routes
├── api/                             common errors, validation, DTOs
├── auth/                            login, refresh, 2FA, profile, user administration
├── config/                          env/.env parsing and validation
├── customer/                        customer application service and customer APIs
├── db/
│   ├── tables/                      Exposed table declarations and enums
│   └── repositories/                PostgreSQL access and durable state operations
├── deployment/                      job worker, lifecycle, port pool, recovery, drift
├── docker/                           Docker client, wizard, cleanup and models
├── feature/payment/
│   ├── domain/                       payment models, ports, repositories and use cases
│   ├── data/                         Exposed persistence adapters and provider clients
│   └── presentation/                 Paystack, M-Pesa and admin payment routes
├── gate/                             access decisions, proxy routes and paywall templates
├── integrations/                     GitHub App/webhooks, Scribed and Resend clients
├── nginx/                            site generation, validation, certs, includes, recovery
├── plugins/                          Ktor, database, Redis, auth, metrics, DI, telemetry
├── scheduler/                        auto-blocker, payment/outbox jobs and worker support
└── security/                         encryption, TOTP and recovery-code helpers

src/main/resources/
├── application.conf                  Ktor defaults
├── logback.xml                       logging config
└── db/migration/                     Flyway V1..V21 schema evolution
```

Tests are under `src/test/kotlin/com/gatekeeper`, organized by feature. Nginx/Docker/deployment tests primarily use fakes, pure policy helpers, and temporary directories; database integration tests have separate container/test support.

## 24. Migration history

The current migration files document how the schema developed. V1 supplies the consolidated current base for fresh installations; later entries are retained as incremental evolution for databases already tracked by Flyway.

| Version | Main schema change |
|---|---|
| V1 | Current-schema baseline for a fresh database. |
| V2 | Unique customer and user email constraints. |
| V3 | Registry image deployment support. |
| V4 | Stable service identity. |
| V5 | Service identity on runtime records. |
| V6 | Deployment lifecycle per service. |
| V7 | Versioned environment sets. |
| V8 | Sites associated with services. |
| V9 | Service-scoped access status. |
| V10 | Service registry credential reference. |
| V11 | Structured access block reasons. |
| V12 | Service-scoped payment records. |
| V13 | Service-scoped adjustments. |
| V14 | Stable default-service identity. |
| V15 | Separate deployment readiness from gateway cutover. |
| V16 | Service domains and explicit nginx sites. |
| V17 | Durable runtime restoration snapshots. |
| V18 | Deployment-job claim ownership. |
| V19 | Deployment host-port pool allocations. |
| V20 | Nginx site upstream-kind classification. |
| V21 | Custom nginx locations. |

Migration SQL is authoritative for exact constraints and data transformations. Before changing a persisted invariant, update the Exposed table, migration, repositories, API contract and focused tests together.

## 25. Related documentation

- [API contract](API.md)
- [Client nginx gating](nginx-client-gating.md)
- [Nginx reverse proxy for Gatekeeperd](nginx-reverse-proxy.md)
- [Deployment pipeline details](deployment-pipeline.md)
- [VPS staging and deployment](staging-deployment.md)
- [Project index and environment variables](README.md)
