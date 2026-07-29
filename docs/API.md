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
Paystack browser return URL after payment. Shows a thank-you page telling the client their site will be back online shortly. Project activation is handled by the Paystack webhook.

**Note:** when a payment is confirmed, Gatekeeper clears the project's `due_date` and sets the project back to `active`. That stops overdue tracking until an admin assigns a new due date.

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
- Creating a project does NOT deploy the container — that is a manual DevOps step. This just registers it in Gatekeeper.

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

### DELETE /api/admin/projects/{slug}
Archive a project (soft delete). Sets `deleted_at`, blocks gating, and **preserves** payments, payment events, and audit log for reporting.

**Response:** `204 No Content`

**Notes:**
- Does not stop or remove the client container — that remains a manual DevOps step.
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

These endpoints manage Docker containers and networks on the host. They require Docker socket access and return `503 Service Unavailable` if Docker is not available.

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
    "ports": "80->8080/tcp",
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
  "tag": "latest"
}
```

**Response:**
```json
{
  "status": "pulled",
  "image": "nginx:latest"
}
```

---

## Webhooks (No JWT, Signature Required)

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
