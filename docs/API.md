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
- Default admin is seeded on first startup if no users exist.
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
    "currency": "NGN",
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
  "currency": "NGN",
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
- Only `charge.success` events are processed. All other events return 200 with no action.
- On success: marks payment as `success`, sets project to `active`, writes audit log, deletes Redis cache.
- Must respond quickly (Paystack retries on timeout). Slow work should be done in background.

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

5. **CORS:** CORS is configured with `anyHost()` for development. For production, restrict allowed origins in `Security.kt`.

6. **Content types:** Frontend projects receive HTML paywalls. Backend/API projects receive JSON. Use the `type` field from the project to determine which format to expect.