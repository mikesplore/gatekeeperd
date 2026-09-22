# Gatekeeperd — API Documentation

Base URL: `http://localhost:8080` (development)  
Content-Type: `application/json` unless noted otherwise

---

## Authentication

### POST /api/auth/login
Get a JWT token for admin endpoints.

**Request:**
```json
{
  "email": "admin@gatekeeper.local",
  "password": "admin123"
}
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**Notes:**
- On first startup, if no users exist, an admin is created from `ADMIN_EMAIL` and `ADMIN_PASSWORD` in `.env`.
- If those env vars are missing on a fresh database, no admin user is created and login will fail until you set them and restart.
- Token expires in 24 hours.
- Include token in `Authorization: Bearer <token>` header for protected routes.

### GET /api/auth/me
Get the authenticated admin user's profile (JWT required).

**Response:**
```json
{
  "email": "admin@gatekeeper.local",
  "role": "admin",
  "createdAt": "2026-07-28T12:00:00"
}
```

---

## Public Endpoints (No Auth)

### GET /api/health
Health check.

**Response:**
```json
{
  "status": "ok"
}
```

### GET /api/health/live
Process liveness check. Returns `200 OK` when the application process is running.

### GET /api/health/ready
Dependency readiness check. Returns `200 OK` only when PostgreSQL and Redis are reachable; otherwise returns `503 Service Unavailable` with dependency booleans.

### GET /api/gate/check?project={slug}
Gate check endpoint called by Traefik ForwardAuth. Never expose this to the public internet directly.

**Responses:**
- `200 OK` — project is active, Traefik forwards the request to the client container
- `402 Payment Required` — project is blocked
  - For `frontend` projects: `Content-Type: text/html`, body is a paywall page
  - For `backend` projects: `Content-Type: application/json`, body:
    ```json
    {
      "error": "payment_required",
      "message": "Access to this API is suspended pending payment.",
      "timestamp": "2026-07-28T12:00:00",
      "payment_link": "https://paystack.com/pay/xxxx",
      "contact": "support@gatekeeper.local"
    }
    ```
- `402` with JSON for unrecognized slugs (fail-closed):
  ```json
  {
    "error": "unknown_project",
    "message": "The requested project is not recognized.",
    "timestamp": "2026-07-28T12:00:00"
  }
  ```

**Notes:**
- This endpoint must NOT be internet-reachable. Only Traefik on the internal Docker network should call it.
- Redis cache TTL is 60 seconds. Admin actions explicitly delete the cache for instant propagation.

### GET /api/gate/auth?project={slug}
Lightweight gate check for **nginx `auth_request` only**. Returns empty body.

**Responses:**
- `200 OK` — project is active; nginx should proxy to the client app
- `403 Forbidden` — project is blocked or unknown; pair with `error_page 403 = @paywall` and serve `/api/gate/check` from a named location for the paywall body

**Why this exists:** nginx `auth_request` treats `402` as an internal error and returns **500** to the client. Only `401` and `403` are valid deny codes for the auth subrequest. Use `/api/gate/auth` for the subrequest and `/api/gate/check` for the paywall response.

See `docs/nginx-client-gating.md` for a full nginx example.

### GET /api/gate/paywall?project={slug}
HTML payment page for blocked clients. Shows project name, domain, amount due, due date, and a **Pay Now** button.

**Response:** `402 Payment Required`, `Content-Type: text/html`

### GET /api/gate/pay?project={slug}
Public payment initiation for a suspended project. Creates a Paystack checkout session and redirects the browser to Paystack.

**Response:** `302` redirect to Paystack, or `4xx/5xx` JSON error if Paystack is not configured.

**Requires:** project is blocked, `amount_due` and `client_email` set, `PAYSTACK_SECRET_KEY` and `GATEKEEPER_PUBLIC_URL` in env.

### GET /api/gate/payment/callback?project={slug}&reference={ref}
Paystack browser return URL after payment. Redirects the browser to the project domain. Project activation is handled by the Paystack webhook.

**Note:** when a payment is confirmed, Gatekeeper clears the project's `due_date` and sets the project back to `active`. That stops overdue tracking until an admin assigns a new due date.

### Customer frontend integration

Set `GATEKEEPER_FRONTEND_URL` to the deployed customer frontend origin. The backend does not render customer screens; it supplies the data and redirects the browser to that frontend.

#### GET /api/customer/projects/{slug}/status
Returns customer-safe access status, amount due, due date, payment history, receipt URLs, payment URL, portal URL, and support URL.

#### GET /api/customer/projects/{slug}/payments/{paymentId}/receipt
Returns receipt data for a successful payment. The frontend can render or print it.

#### GET /api/customer/projects/{slug}/portal
Redirects to `${GATEKEEPER_FRONTEND_URL}/portal/{slug}`.

#### GET /api/customer/projects/{slug}/payment-success?reference={ref}
Redirects to `${GATEKEEPER_FRONTEND_URL}/payment/success` with project and reference query parameters.

#### GET /api/customer/projects/{slug}/reminder
Returns an email-ready reminder payload containing the recipient, subject, message, and payment URL. Email delivery remains a frontend/notification-provider concern.

#### POST /api/customer/projects/{slug}/support
Creates a persisted support request. Body:
```json
{"name":"Jane Doe","email":"jane@example.com","message":"I need help restoring access."}
```

---

## Admin Endpoints (JWT Required)

All endpoints below require `Authorization: Bearer <token>` header.

### GET /api/admin/projects
List all projects with their status, client info, and due dates.

**Response:**
```json
[
  {
    "id": "uuid",
    "slug": "acme-corp",
    "name": "Acme Corp",
    "domain": "acme.com",
    "containerName": "acme-container",
    "type": "backend",
    "status": "active",
    "blockReason": null,
    "deploymentMode": "developer_hosted",
    "serviceMode": "development",
    "lifecycleStatus": "active",
    "clientName": "John Doe",
    "clientEmail": "john@acme.com",
    "paystackCustomerCode": "CUS_xxx",
    "amountDue": 5000.00,
    "currency": "KES",
    "dueDate": "2026-08-01",
    "gracePeriodDays": 3,
    "createdAt": "2026-07-28T12:00:00",
    "updatedAt": "2026-07-28T12:00:00"
  }
]
```

### GET /api/admin/projects/{slug}
Get single project details + payment history + audit log.

Project responses include independent `deploymentMode`, `serviceMode`, `lifecycleStatus`, `status`, and `blockReason` fields. Use the dedicated transfer and archive operations for lifecycle changes.

**Response:**
```json
{
  "project": { /* project object as above */ },
  "payments": [
    {
      "id": "uuid",
      "projectId": "uuid",
      "paystackReference": "ref_xxx",
      "amount": 5000.00,
      "status": "success",
      "paidAt": "2026-07-28T12:00:00",
      "rawWebhookPayload": "...",
      "createdAt": "2026-07-28T11:00:00"
    }
  ],
  "audit_log": [
    {
      "id": "uuid",
      "projectId": "uuid",
      "action": "payment_received",
      "actor": "system",
      "reason": "payment_received via webhook",
      "createdAt": "2026-07-28T12:00:00"
    }
  ]
}
```

### POST /api/admin/projects
Create a new project registration.

**Request:**
```json
{
  "slug": "acme-corp",
  "name": "Acme Corp",
  "domain": "acme.com",
  "containerName": "acme-container",
  "type": "backend",
  "clientName": "John Doe",
  "clientEmail": "john@acme.com",
  "amountDue": 5000.00,
  "currency": "KES",
  "dueDate": "2026-08-01",
  "gracePeriodDays": 3
}
```

**Response:** `201 Created` with the project object.

**Notes:**
- `slug` must be unique and lowercase; it is used in Traefik labels and gate checks.
- `type` must be `"frontend"` or `"backend"`.
- `containerName` must be either `name` or `name:port`. If a port is provided, nginx wizard can use it as the upstream host port.
- Gatekeeper enforces a **container-first** flow: the referenced Docker container must already exist (otherwise `400 container_not_found`).
- Creating a project does NOT deploy the container — that is a manual DevOps step. This just registers it in Gatekeeper.

### GET /api/admin/projects/wizard/context
Wizard helper: list Docker containers (for a dropdown) and currently-used project slugs (to avoid collisions).

**Response:**
```json
{
  "containers": [
    {
      "id": "abc123def456",
      "name": "acw",
      "image": "mikesplore/acw:latest",
      "state": "running",
      "ports": "9921->8080/tcp",
      "suggestedSlug": "acw"
    }
  ],
  "existingProjectSlugs": ["acw"]
}
```

### PATCH /api/admin/projects/{slug}
Update project fields.

**Request (any subset):**
```json
{
  "name": "New Name",
  "amountDue": 6000.00,
  "dueDate": "2026-08-15",
  "clientEmail": "newemail@acme.com"
}
```

**Response:** Updated project object.

**Notes:**
- If `containerName` is updated, Gatekeeper validates that the referenced Docker container exists.

### POST /api/admin/projects/{slug}/adjustments
Append a financial adjustment without overwriting the project’s original charge or payment history.

**Request:**
```json
{
  "type": "ADDITIONAL_CHARGE",
  "amount": 500.00,
  "reason": "Added reporting feature"
}
```

`type` must be `ADDITIONAL_CHARGE` or `DISCOUNT`; `amount` must be greater than zero. The response includes the actor, adjustment, old balance, and new balance. The operation writes a detailed `project_adjustment` audit entry.

### DELETE /api/admin/projects/{slug}
Archive a project (soft delete). Sets `deleted_at`, blocks gating, and **preserves** payments, payment events, and audit log for reporting.

**Response:** `204 No Content`

**Notes:**
- Does not stop or remove the client container — that remains a manual DevOps step.
- Best-effort nginx cleanup is attempted (`sites-available/sites-enabled` removal + reload) so orphan nginx configs don't continue pointing at archived slugs.
- The project is unlinked from its container by replacing `containerName` with an `archived-{slug}` placeholder.
- Clears the Redis gate cache; the slug behaves as unknown to nginx/Traefik after archive.
- The slug stays reserved while archived (cannot create a new project with the same slug).
- Writes an audit log entry with action `project_archived`.

### POST /api/admin/projects/{slug}/block
Manually block a project.

**Request:**
```json
{
  "reason": "Client requested suspension"
}
```

**Response:**
```json
{
  "status": "blocked",
  "slug": "acme-corp"
}
```

**Notes:**
- Immediately invalidates Redis cache so the gate starts returning 402 within seconds.
- Writes an `audit_log` entry with action `blocked`.

### POST /api/admin/projects/{slug}/unblock
Manually unblock a project (set back to active).

**Request:**
```json
{
  "reason": "Payment received manually"
}
```

**Response:**
```json
{
  "status": "active",
  "slug": "acme-corp"
}
```

**Notes:**
- Immediately invalidates Redis cache so the gate starts returning 200 within seconds.
- Writes an `audit_log` entry with action `unblocked`.

### POST /api/admin/projects/{slug}/payment/initialize
Generate a Paystack payment link for a project.

**Request:**
```json
{
  "email": "john@acme.com"
}
```

**Response:**
```json
{
  "payment_link": "https://paystack.com/pay/abc123"
}
```

**Notes:**
- Uses the project's `amount_due` and `client_email` from the database if not overridden.
- Creates a pending `payments` row with the Paystack reference.
- The client pays via the returned link. After successful payment, Paystack sends a webhook to `/api/paystack/webhook`, which activates the project.

### GET /api/admin/projects/{slug}/audit
Get audit log for a project.

**Response:**
```json
[
  {
    "id": "uuid",
    "projectId": "uuid",
    "action": "payment_received",
    "actor": "system",
    "reason": "payment_received via webhook",
    "createdAt": "2026-07-28T12:00:00"
  }
]
```

### GET /api/admin/audit
Get the most recent audit log entries across all projects (JWT required).

**Query parameters:**
- `limit` (optional, default `100`, max `500`) — number of entries to return

**Response:** Same array format as project audit log above.

### GET /api/admin/payments
List all payments across projects with filters and pagination (JWT required).

### POST /api/admin/projects/{slug}/payments/cash
Record a cash payment received by an administrator (JWT required). Partial payments are accepted, provided they do not exceed the project's remaining balance and use its currency. Successful capture contributes to the project balance; the project activates when the cumulative successful payments reach the configured amount due.

Request body:

```json
{
  "amount": 500,
  "currency": "KES",
  "paidAt": "2026-09-20T10:30:00",
  "receiptNumber": "CASH-1001",
  "notes": "Received in person"
}
```

The response contains the generated cash reference and `status: "success"`. Reusing the same receipt number is rejected rather than creating a duplicate payment.

**Query parameters (all optional):**
- `status` — filter by `gateway_status` (`pending`, `success`, `failed`, `abandoned`, `reversed`)
- `project_slug` — filter to one project
- `from`, `to` — ISO date range on `paid_at` (falls back to `created_at` when pending)
- `limit` (default `100`, max `500`)
- `offset` (default `0`)

**Response:**
```json
{
  "payments": [
    {
      "id": "uuid",
      "projectId": "uuid",
      "projectName": "Acme Corp",
      "projectSlug": "acme-corp",
      "paystackReference": "ref_xxx",
      "amount": 5000.00,
      "gatewayStatus": "success",
      "verifiedVia": "webhook",
      "paidAt": "2026-07-28T12:00:00",
      "createdAt": "2026-07-28T11:00:00"
    }
  ],
  "total": 1,
  "limit": 100,
  "offset": 0
}
```

### GET /api/admin/projects/overdue
Active projects past their due date, sorted by days overdue descending (JWT required).

**Response:**
```json
[
  {
    "slug": "acme-corp",
    "name": "Acme Corp",
    "clientName": "John Doe",
    "clientEmail": "john@acme.com",
    "dueDate": "2026-07-20",
    "daysOverdue": 8,
    "gracePeriodDays": 3,
    "willAutoBlockOn": "2026-07-23",
    "amountDue": 5000.00
  }
]
```

## Nginx Management Endpoints (JWT Required)

### GET /api/admin/dashboard/summary

Returns one authenticated dashboard payload containing project status counts, payment status counts, revenue totals, integration outbox counts, Nginx site counts, and runtime metrics.

### GET /api/admin/nginx/config/{slug}

Returns the live `sites-available/{slug}` file as text, file metadata, enabled-link state, and parsed `server`/`location` blocks. This is the primary inspection endpoint for diagnosing Nginx without SSH.

The response also includes SHA-256 fingerprints and `drifted`. Drift is true when the live file differs from the last configuration successfully applied or rolled back through Gatekeeperd.

### GET /api/admin/nginx/diagnostics

Runs `nginx -t` on the host and returns the exit code, timestamp, validity, and complete command output.

`POST /api/admin/nginx/test` performs the same validation on demand.

### Block preview and apply

`POST /api/admin/nginx/config/{slug}/blocks/{index}/preview` returns the complete proposed file without writing it. The request body is `{ "blockIndex": 0, "content": "..." }`.

`POST /api/admin/nginx/config/{slug}/blocks/{index}/apply` replaces only the selected block, runs `nginx -t`, reloads Nginx, and restores the previous file if validation or reload fails.

`GET /api/admin/nginx/config/{slug}/versions` lists available configuration backups. `POST /api/admin/nginx/config/{slug}/rollback/{backup}` restores a selected backup, validates it, reloads Nginx, and restores the current version if anything fails.

`POST /api/admin/nginx/rollback/{slug}` is a convenience wrapper: it resolves the latest backup and delegates to the same selected-backup rollback implementation. Both endpoints return the same response shape and use the same validation, activation, reload, hash, and audit flow.

Successful block updates and rollbacks are recorded in the project audit log.

## Deployment operations

Registry credentials are managed through `GET /api/admin/registries`, `PUT /api/admin/registries/{registry}`, and `DELETE /api/admin/registries/{registry}`. Passwords are AES-256-GCM encrypted at rest and never returned. The deployment worker performs registry-scoped `docker login --password-stdin` before push/pull operations.

Deployment administration is JWT-protected:

- `POST /api/admin/deployments` queues a GitHub-to-container deployment. The request accepts `repository`, `gitRef`, `registry` (`docker.io` or a registry host), `imageName`, `imageTag`, optional `containerName`, published `hostPort`/`containerPort`, `network`, `restartPolicy`, and optional `projectSlug`.
- `GET /api/admin/deployments` and `GET /api/admin/deployments/{id}` expose lifecycle state, logs, commit SHA, image digest, and failure details.
- `GET /api/admin/deployments/{id}/audit` exposes deployment-worker audit records.
- `POST /api/admin/deployments/{id}/cancel`, `/retry`, and `/rollback` control the job lifecycle. Rollback uses the persisted previous container image and performs the same health and published-port checks before replacing the active container.
- `POST /api/admin/system/prune?dryRun=true&imagePrefix=owner/image` performs reference-aware Docker image cleanup. It never runs `docker system prune`; active container images and the current/previous deployment images are preserved. The response reports candidate image sizes and reclaimed bytes, and every cleanup is written to the audit trail. Omit `dryRun` or set it to `false` to remove candidates.
- `POST /api/integrations/github/webhook` accepts signed GitHub events. `X-GitHub-Delivery` is required and is persisted for idempotency; duplicate deliveries are acknowledged without queueing another deployment.

After a successful deployment, the linked project container name is synchronized. Nginx handoff remains explicit through `POST /api/admin/nginx/enable/{slug}`; it validates the running container and published port before writing and reloading the site configuration.

These endpoints manage nginx site configurations for client projects. They require `nginx` CLI and `systemctl` access on the host.

### GET /api/admin/nginx/wizard/context/{slug}
Wizard helper: fetch project + nginx context to drive a step-by-step UI (status, container hints, certificate options).

**Response:**
```json
{
  "slug": "acw",
  "domain": "acw.example.com",
  "containerName": "acw-container:9921",
  "nginxEnabled": false,
  "configuredContainerName": "acw-container",
  "configuredPort": 9921,
  "dockerContainerHealth": "running",
  "dockerPublishedHostPorts": [9921],
  "installedCertificates": ["example.com"],
  "resolvedCertificateDomain": "example.com"
}
```

### POST /api/admin/nginx/wizard/validate/{slug}
Wizard helper: validate nginx enable inputs and return a config preview without applying changes.

**Request:** same as `POST /api/admin/nginx/enable/{slug}`

**Response:** same shape as `POST /api/admin/nginx/enable/{slug}`, but `message` indicates no changes were applied.

### GET /api/admin/nginx/status/{slug}
Check if a project has an nginx site configured and enabled, and whether SSL is set up.

**Response:**
```json
{
  "enabled": true,
  "configPath": "/etc/nginx/sites-available/acw",
  "enabledPath": "/etc/nginx/sites-enabled/acw",
  "port": 9921,
  "sslEnabled": true,
  "certificateDomain": "example.com",
  "domain": "acw.mikesplore.me"
}
```

**Notes:**
- `enabled` is `true` only if both the sites-available file exists AND the symlink in sites-enabled exists.
- `sslEnabled` is `true` if Gatekeeper can find a usable certificate for the project's `domain` (either a direct match, or a parent domain certificate).
- `port` is extracted from the project's `containerName` field (format `name:port`).

### POST /api/admin/nginx/enable/{slug}
Generate and enable an nginx site config for a project. Validates that the project's container is running before creating the config.

**Request:**
```json
{
  "port": 9921,
  "upstreamScheme": "http",
  "certificateDomain": "example.com",
  "sslCertificatePath": "/etc/letsencrypt/live/example.com/fullchain.pem",
  "sslCertificateKeyPath": "/etc/letsencrypt/live/example.com/privkey.pem",
  "requireSsl": false
}
```

- `port` is optional if `containerName` already contains a port (e.g. `myapp:9921`). If Docker is available and the container publishes exactly one host port, Gatekeeper can also infer the port automatically.
- `upstreamScheme` is optional. If omitted, Gatekeeper infers `https` for port `443` and `http` for other ports.
- `certificateDomain` is optional. If provided, it selects an installed certificate under `/etc/letsencrypt/live/{certificateDomain}/`.
- `sslCertificatePath` and `sslCertificateKeyPath` are optional. If both are provided, they override all other certificate selection.
- If no certificate selection is provided, Gatekeeper tries to reuse an installed certificate for the project's `domain` or its parent domains (e.g. reuse `example.com` for `acw.example.com`).
- `requireSsl` is optional. If `true`, the request fails when no certificate is found. If `false` (default), the site is enabled without SSL (port 80) when no certificate is found.

**Response:**
```json
{
  "success": true,
  "message": "Nginx site enabled and reloaded successfully",
  "config": "server {\n    listen 443 ssl;\n    ...\n}",
  "appPort": 9921,
  "sslEnabled": true,
  "certificateDomain": "example.com"
}
```

**Notes:**
- Validates that the project's Docker container is running before creating the config (works even when gatekeeperd runs in Docker).
- If Docker reports published ports for the container, Gatekeeper also checks that the expected upstream host port is published.
- If Docker is unavailable or the project doesn't encode a container name, Gatekeeper falls back to a fast TCP connect probe on `127.0.0.1:{port}`.
- Creates a file in `sites-available/{slug}` and a symlink in `sites-enabled/{slug}`.
- Runs `nginx -t` and `systemctl reload nginx`. If reload fails, the site is disabled and an error is returned.
- The generated config follows the standard gatekeeperd pattern with `auth_request`, paywall named location, and `/api/gate/` bypass.
- If the upstream app listens on `443`, the generated config uses `proxy_pass https://127.0.0.1:443` unless `upstreamScheme` overrides it.

### POST /api/admin/nginx/disable/{slug}
Disable (unlink) an nginx site without removing the config file.

**Notes:**
- This endpoint does not require an existing project record; it can be used to clean up orphan nginx configs by slug.

**Response:**
```json
{
  "success": true,
  "message": "Nginx site disabled and reloaded successfully"
}
```

### POST /api/admin/nginx/remove/{slug}
Remove an nginx site completely (both sites-available file and sites-enabled symlink).

**Notes:**
- This endpoint does not require an existing project record; it can be used to clean up orphan nginx configs by slug.

**Response:**
```json
{
  "success": true,
  "message": "Nginx site removed and reloaded successfully"
}
```

## SSL Certificate Management Endpoints (JWT Required)

These endpoints manage Let's Encrypt certificates via `certbot`.

### GET /api/admin/nginx/certificate/list
List installed certificates found under `/etc/letsencrypt/live`.

**Response:**
```json
{
  "certificates": [
    {
      "certificateDomain": "example.com",
      "certificatePath": "/etc/letsencrypt/live/example.com/fullchain.pem",
      "privateKeyPath": "/etc/letsencrypt/live/example.com/privkey.pem",
      "certificateExpiresAt": "2026-10-22T00:00:00Z",
      "certificateDaysRemaining": 29,
      "renewalStatus": "active"
    }
  ]
}
```

The endpoint reads the installed certificate's expiry and synchronizes `expires_at` and `renewal_status` in the `certificates` table. `renewalStatus` is `active`, `expired`, or `unknown` when the certificate file cannot be parsed.

### POST /api/admin/nginx/certificate/install
Install an SSL certificate for a domain using certbot's nginx plugin.

**Request:**
```json
{
  "domain": "example.com",
  "email": "admin@example.com"
}
```

**Response:**
```json
{
  "domain": "example.com",
  "installed": true,
  "certificatePath": "/etc/letsencrypt/live/example.com/fullchain.pem",
  "privateKeyPath": "/etc/letsencrypt/live/example.com/privkey.pem"
}
```

**Notes:**
- Requires `certbot` to be installed on the host.
- Runs the root-owned `gatekeeperd-certbot` helper through non-interactive `sudo` so Certbot can write to its system directories. See the [staging deployment guide](staging-deployment.md#allowing-gatekeeperd-to-manage-lets-encrypt-certificates) for setup.
- The certificate paths follow the standard Let's Encrypt layout.

### POST /api/admin/nginx/certificate/remove/{domain}
Remove an SSL certificate for a domain.

**Response:**
```json
{
  "domain": "example.com",
  "installed": false,
  "certificatePath": null,
  "privateKeyPath": null
}
```

### GET /api/admin/nginx/certificate/status/{domain}
Check whether an SSL certificate is installed for a domain.

**Response:**
```json
{
  "domain": "example.com",
  "installed": true,
  "certificatePath": "/etc/letsencrypt/live/example.com/fullchain.pem",
  "privateKeyPath": "/etc/letsencrypt/live/example.com/privkey.pem"
}
```

---

### GET /api/admin/revenue
Revenue summary from successful payments (JWT required).

**Query parameters:**
- `period` — `month` (only supported value for now)
- `months` (default `6`) — number of months in the chart series

**Response:**
```json
{
  "totalThisMonth": 15000.00,
  "totalLastMonth": 9000.00,
  "currency": "KES",
  "byMonth": [
    { "month": "2026-02", "amount": 3000.00 },
    { "month": "2026-07", "amount": 15000.00 }
  ]
}
```

---

## Docker Admin Endpoints (JWT Required)

These endpoints manage Docker containers, networks, and images on the host. They require Docker socket access and return `503 Service Unavailable` if Docker is not available.

### POST /api/admin/images/status
Wizard helper: check whether an image is available locally before attempting container creation.

**Request:**
```json
{
  "image": "nginx:latest"
}
```

**Response:**
```json
{
  "image": "nginx:latest",
  "exists": true
}
```

### POST /api/admin/containers/create
Create and start a new Docker container with custom configuration.

**Request:**
```json
{
  "name": "my-app",
  "projectSlug": "my-app",
  "image": "nginx:latest",
  "ports": {
    "8080": 80,
    "8081": 8080
  },
  "env": {
    "DB_HOST": "postgres",
    "API_KEY": "secret123"
  },
  "network": "gatekeeper-internal",
  "volumes": [
    {
      "hostPath": "/data/app",
      "containerPath": "/app/data",
      "readOnly": false
    }
  ],
  "restartPolicy": "unless-stopped",
  "pullImage": true,
  "pullViaCli": false
}
```

- `name` (required unless `projectSlug` provided): Container name
- `projectSlug` (optional): If `name` is omitted/blank, Gatekeeper will auto-name the container using the normalized slug
- `image` (required): Docker image reference (e.g., `nginx:latest` or `nginx`)
- `ports` (optional): Map of host ports to container ports (e.g., `{"8080": 80}`)
- `env` (optional): Environment variables as key-value pairs
- `network` (optional): Docker network to connect to (default: `"bridge"`)
- `volumes` (optional): List of volume mounts
- `restartPolicy` (optional): Docker restart policy (`"no"`, `"always"`, `"unless-stopped"`, `"on-failure"`)
- `pullImage` (optional): Pull image before creating container (default: `true`)
- `pullViaCli` (optional): Pull via `docker pull` CLI to reuse host Docker Hub auth (default: `false`)

**Response:** `201 Created`
```json
{
  "id": "abc123def456",
  "name": "my-app",
  "status": "Up 2 seconds",
  "ports": "8080->80/tcp, 8081->8080/tcp"
}
```

**Notes:**
- Validates image availability first (pulls if missing when `pullImage=true`)
- Validates port availability before creating container (returns `409 Conflict` if ports are in use)
- Container is started immediately after creation
- Returns full container info including port mappings

### GET /api/admin/containers/wizard/context
Wizard helper: fetch Docker container-creation context in one request (networks + internal network info).

**Response:**
```json
{
  "internalNetwork": "gatekeeper-internal",
  "internalNetworkExists": true,
  "networks": ["bridge", "gatekeeper-internal"]
}
```

### POST /api/admin/containers/wizard/ports/check
Wizard helper: check whether host ports are already in use on the Docker host.

**Request:**
```json
{
  "hostPorts": [8080, 8081]
}
```

**Response:**
```json
{
  "ok": true,
  "conflicts": []
}
```

### POST /api/admin/containers/wizard/validate
Wizard helper: validate and normalize a `CreateContainerRequest` without applying changes (no pull, no create).

**Request:** same shape as `POST /api/admin/containers/create`

**Response:**
```json
{
  "success": true,
  "message": "Validated successfully (no changes applied)",
  "normalizedRequest": {
    "name": "my-app",
    "image": "nginx:latest",
    "ports": {
      "8080": 80
    },
    "env": {},
    "network": "bridge",
    "volumes": [],
    "restartPolicy": "unless-stopped",
    "pullImage": true,
    "pullViaCli": false
  },
  "imageExists": true,
  "willPullImage": false,
  "networkExists": true,
  "willCreateInternalNetworkIfMissing": false,
  "portConflicts": [],
  "warnings": []
}
```

### GET /api/admin/containers
List all Docker containers.

**Query parameters:**
- Uses `all=true` internally (includes stopped containers)

**Response:**
```json
[
  {
    "id": "abc123def456",
    "name": "acme-container",
    "image": "nginx:latest",
    "status": "Up 2 hours",
    "state": "running",
    "ports": "8080->80/tcp",
    "created": 1722163200
  }
]
```

### GET /api/admin/containers/{name}
Get details for a single container by name or ID prefix.

### POST /api/admin/containers/{name}/start
Start a stopped container.

**Response:**
```json
{
  "status": "started",
  "container": "acme-container"
}
```

### POST /api/admin/containers/{name}/stop
Stop a running container.

**Response:**
```json
{
  "status": "stopped",
  "container": "acme-container"
}
```

### POST /api/admin/containers/{name}/restart
Restart a container.

**Response:**
```json
{
  "status": "restarted",
  "container": "acme-container"
}
```

### POST /api/admin/containers/{name}/delete
Delete a container permanently.

**Response:**
```json
{
  "status": "deleted",
  "container": "acme-container"
}
```

**Notes:**
- Uses `force=true` by default to stop running containers before deletion
- Container is removed from Docker host

### GET /api/admin/containers/{name}/health
Get container health/running state.

**Response:**
```json
{
  "container": "acme-container",
  "health": "running"
}
```

Possible `health` values: `"running"`, `"exited"`, `"unknown"`.

### GET /api/admin/networks
List Docker networks.

**Response:**
```json
[
  {
    "id": "abc123",
    "name": "gatekeeper-internal",
    "driver": "bridge",
    "scope": "local"
  }
]
```

### POST /api/admin/images/pull
Pull a Docker image from a registry.

**Request:**
```json
{
  "image": "nginx",
  "tag": "latest",
  "pullViaCli": false
}
```

**Response:**
```json
{
  "status": "pulled",
  "image": "nginx:latest"
}
```

**Notes:**
- For private Docker Hub images, set `pullViaCli=true` (or `DOCKER_PULL_VIA_CLI=true`) so pulls use `docker pull` and can reuse the host's Docker auth.

### POST /api/admin/images/delete
Delete a Docker image from the local Docker host.

**Request:**
```json
{
  "image": "nginx",
  "tag": "latest",
  "force": true
}
```

**Response:**
```json
{
  "status": "deleted",
  "image": "nginx:latest"
}
```

**Notes:**
- `force` (optional, default: `true`): Force remove image even if containers are using it
- Image is removed from Docker host

---

## Webhooks (No JWT, Signature Required)

### M-Pesa

`POST /api/mpesa/pay?project={slug}&phone={msisdn}` starts an M-Pesa STK Push and returns `202` with a pending provider reference. `POST /api/mpesa/callback` receives the Daraja callback. M-Pesa payments are stored and reconciled through the same payment application and reconciliation service as Paystack.

Required configuration: `MPESA_CONSUMER_KEY`, `MPESA_CONSUMER_SECRET`, `MPESA_SHORT_CODE`, `MPESA_PASSKEY`, `MPESA_CALLBACK_URL`, and `MPESA_ENVIRONMENT` (`sandbox` or `production`).

### POST /api/paystack/webhook
Paystack webhook handler. Verifies HMAC-SHA512 signature via `x-paystack-signature` header.

**Headers:**
- `x-paystack-signature`: HMAC-SHA512 of raw request body using `PAYSTACK_SECRET_KEY`

**Request body (example):**
```json
{
  "event": "charge.success",
  "data": {
    "reference": "ref_abc123",
    "amount": 500000,
    "status": "success",
    "metadata": {
      "project_slug": "acme-corp"
    }
  }
}
```

**Responses:**
- `200 OK` — processed successfully
- `200 OK` with `{"status":"already_processed"}` — idempotent duplicate
- `401 Unauthorized` — signature missing or invalid

**Notes:**
- Handles `charge.success`, `charge.failed`, `charge.reversed`, and `transfer.reversed`.
- Every webhook is logged to `payment_events` before business logic runs.
- Webhook events track `received`, `processed`, or `failed` processing status, attempt count, error text, and processing timestamps.
- Administrators can inspect recent events with `GET /api/admin/payment-events?status=failed`.
- On `charge.success`: marks payment success, clears the project's `due_date`, activates project, writes audit log, invalidates Redis cache.
- On `charge.failed`: records failure; does **not** change project status.
- On reversal: re-blocks the project if the reversed payment had been successful and restores `due_date` to the reversal day.
- A reconciliation job also verifies stale pending payments via Paystack's verify API (`RECONCILIATION_STALE_MINUTES`, `RECONCILIATION_INTERVAL_MINUTES`).
- Must respond quickly (Paystack retries on timeout).

---

## Error Format

All error responses return JSON with a consistent shape:

```json
{
  "error": "error_code",
  "message": "Human-readable description",
  "timestamp": "2026-07-28T12:00:00"
}
```

Payment-required responses for blocked backend projects extend this format:

```json
{
  "error": "payment_required",
  "message": "Access to this API is suspended pending payment.",
  "timestamp": "2026-07-28T12:00:00",
  "payment_link": "https://paystack.com/pay/xxxx",
  "contact": "support@gatekeeper.local"
}
```

Common error codes:
- `invalid_request` — malformed JSON or missing fields
- `missing_slug` — required path parameter missing
- `missing_project_slug` — gate check missing `project` query parameter
- `missing_container_name` — Docker route missing container name
- `project_not_found` — slug does not exist
- `container_not_found` — Docker container not found
- `docker_unavailable` — Docker socket not reachable
- `invalid_credentials` — wrong email/password
- `unauthorized` — missing or invalid JWT
- `user_not_found` — authenticated user no longer exists
- `missing_signature` / `invalid_signature` — webhook auth failure
- `paystack_error` — Paystack API call failed
- `internal_server_error` — unhandled server error
- `payment_required` — gate blocked the project (backend JSON paywall)
- `unknown_project` — unrecognized slug on gate check (fail-closed)

---

## Frontend Integration Notes

1. **Login flow:** Store the JWT token in memory or localStorage. Attach it as `Authorization: Bearer` to all admin API calls.

2. **Gate check (client-side):** If you are building a frontend that needs to check its own status, call `/api/gate/check?project={slug}` directly. If it returns 200, proceed. If 402, show the response body (HTML or JSON) to the user.

3. **Payment initialization:** Call `POST /api/admin/projects/{slug}/payment/initialize` with the client's email. Redirect the user to the returned `payment_link`. After payment, the webhook will activate the project automatically.

4. **Polling for status:** After a payment is completed, poll `/api/admin/projects/{slug}` or `/api/gate/check?project={slug}` every few seconds to see the status change from `blocked` to `active`. The webhook typically processes within 1-5 seconds.

5. **CORS:** Set `CORS_ALLOWED_ORIGINS` in `.env` to a comma-separated list of frontend origins (include the scheme), e.g. `https://gatekeeperd.mikesplore.me`. The API must be rebuilt and redeployed after changing this.

6. **Content types:** Frontend projects receive HTML paywalls. Backend/API projects receive JSON. Use the `type` field from the project to determine which format to expect.
### POST /api/auth/logout
Revokes the current JWT until its natural expiry. The endpoint requires the current admin token.

### POST /api/auth/2fa/setup

Requires an admin JWT. Generates a pending authenticator enrollment and returns the `otpauth://` URI, manual secret, and one-time recovery codes. The enrollment expires after 10 minutes.

### POST /api/auth/2fa/enable

Requires an admin JWT. Body: `{ "code": "123456" }`. Enables 2FA only after validating the pending enrollment code.

### POST /api/auth/2fa/verify

Accepts `{ "challengeToken": "...", "code": "123456" }` after password login when 2FA is enabled. The code may be a TOTP or unused recovery code.

### POST /api/auth/2fa/disable

Requires an admin JWT. Body: `{ "currentPassword": "...", "code": "123456" }`.

### POST /api/auth/2fa/recovery-codes/regenerate

Requires an admin JWT and the current password plus TOTP or recovery code. Returns a new one-time recovery-code batch and invalidates the previous batch.

### POST /api/auth/password
Changes the authenticated admin password. Requires `currentPassword` and a `newPassword` of at least 8 characters.
### GET /api/admin/metrics
Returns in-process operational counters for authenticated administrators, including gate checks, fail-open/closed events, and webhook processing outcomes. Counters reset when the process restarts.

`POST /api/admin/nginx/rollback/{slug}` restores the most recent backed-up nginx site configuration, validates it, and reloads nginx.

### Phase 4 operator endpoints

- `GET /api/admin/projects/{slug}/health` — aggregated project, container, nginx, certificate, and readiness status.
- `POST /api/admin/projects/bulk/block` and `/bulk/unblock` — apply access changes to multiple projects.
- `GET /api/admin/audit/export?action=...&actor=...` — filtered CSV audit export.
- `POST /api/admin/payment-events/{id}/replay` — replay a stored failed webhook through the idempotent payment handlers.
- nginx status responses include certificate expiry timestamp and remaining days when the certificate can be parsed.
### Unmatched routes

Unmatched API routes return JSON instead of a browser-generated error page:

```json
{
  "error": "route_not_found",
  "message": "No route matches GET /api/example",
  "requestId": "..."
}
```

Dashboard write operations are also available to authenticated administrators:

- `PATCH /api/admin/dashboard/sites/{slug}` updates desired Site render fields and activates the generated configuration through nginx validation and reload.
- `DELETE /api/admin/dashboard/sites/{slug}` removes the Site record and its deployed nginx artifacts.
- `DELETE /api/admin/dashboard/dead-configs/{filename}` requires `{"confirm":true}` and moves the orphaned file to a timestamped backup.
- `POST /api/admin/dashboard/customers` creates a customer.
- `PATCH /api/admin/dashboard/projects/{id}` assigns or clears `customerId`.
