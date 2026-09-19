# Gatekeeperd — Architecture and Product Review

This document records a static review of the current Gatekeeperd backend. It describes risks, improvement opportunities, and potential product features. It is a planning document, not a claim that the items below have already been implemented.

## Executive summary

Gatekeeperd has a strong foundation as a payment-triggered access switch:

- Ktor API with JWT-protected administration routes
- PostgreSQL persistence through Exposed
- Redis-backed gate-status caching
- Paystack checkout, webhook, and reconciliation support
- Docker container management
- nginx configuration and client-site gating
- background auto-blocking and audit logging

The highest-value next work is payment correctness, state consistency, database migrations, authentication hardening, and production observability. The current system is not yet a complete billing platform or a fully hardened infrastructure control plane.

## Confirmed or likely breakages

### Payment confirmation does not fully validate payment ownership

`PaymentService.applySuccessfulPayment()` uses the project slug and amount supplied by the payment flow but does not fully verify that:

- the received amount matches the project amount due
- the currency matches the project currency
- the payment record's project matches the project identified by webhook metadata

This creates a payment-integrity risk if records or metadata become inconsistent. A successful payment should be matched against the locally stored payment reference and project association, with amount and currency checked before activation.

Recommended tests:

- correct reference, project, amount, and currency
- wrong project metadata
- wrong payment amount
- wrong currency
- duplicate webhook delivery
- payment reference already associated with another project

### Nullable project fields cannot be cleared through updates

Project updates only apply nullable fields when the value is non-null. This makes it impossible to intentionally clear fields such as `due_date`, `amount_due`, `client_email`, or `client_name` through the normal update API.

Use an update DTO that distinguishes “field not supplied” from “field supplied as null,” or provide explicit clear operations.

### Cache invalidation is not guaranteed

Database status changes, audit writes, and Redis invalidation occur in separate operations. A process failure or Redis error can leave a stale gate decision until the cache expires.

The current 60-second TTL limits the window but does not eliminate the consistency problem. Consider versioned cache entries, a status timestamp, and a reliable event/outbox mechanism for invalidation.

### Auto-blocking polls too frequently

`AutoBlockerJob` currently loops every 30 seconds and contains a TODO indicating that this is temporary. This creates unnecessary database work and may produce repeated status/audit operations.

Use a production interval such as 5–15 minutes or hourly, and make the update conditional on the project still being active.

### Schema management is not migration-based

Startup uses Exposed `SchemaUtils.createMissingTablesAndColumns`. This is convenient during early development but is not a safe long-term production migration strategy. It does not provide versioned changes, controlled data migrations, or reliable rollback.

Introduce Flyway or Liquibase before the schema evolves further.

### Authentication protections are minimal

The admin API currently has 24-hour JWTs but no visible support for:

- login rate limiting
- account lockout or suspicious-login detection
- refresh-token rotation
- token revocation
- password reset/change flows
- meaningful role/permission enforcement

Add rate limiting and a more complete session lifecycle before exposing the admin API broadly.

### Callback and webhook timing can confuse users

The browser callback verifies a transaction but does not itself activate the project; activation depends on the webhook or reconciliation job. A customer can therefore pay successfully and briefly be shown the paywall again before asynchronous processing completes.

The callback should use the same idempotent payment application path after verification, while retaining the webhook as the authoritative retry mechanism. The frontend/paywall should also support a short “payment received, confirming access” state.

### Fail-open behavior needs explicit safeguards

When `FAIL_MODE=open`, a database/Redis outage can allow traffic because the gate cannot distinguish a real active project from an unknown project. This may be acceptable for availability, but it is a significant security and revenue decision.

Consider separate handling for:

- known active projects
- known blocked projects
- unknown projects
- dependency outages

At minimum, document and monitor fail-open events prominently.

## Existing areas that should be improved

### Project state model

The current status strings combine several independent concerns:

- active or archived lifecycle
- allowed or blocked access
- manual versus automatic blocking
- overdue, payment reversal, or other block reasons

A clearer model would separate:

- lifecycle: active / archived
- access state: allowed / blocked
- block reason: overdue / manual / reversed / suspended
- billing state: current / due / paid / failed

This would make reporting, automation, and UI behavior more predictable.

### Billing model

The current `amount_due` model works for simple payment recovery but does not represent full billing. Future billing support may need:

- billing plans and intervals
- invoice records
- invoice due dates
- partial payments
- discounts and credits
- taxes and fees
- currency enforcement
- per-plan grace periods

### Payment records

Payment records should eventually include explicit state transitions and immutable provider data:

- provider event ID
- provider transaction timestamp
- expected amount and received amount
- currency
- failure reason
- refund/reversal metadata
- idempotency key

Raw webhook payloads are useful for debugging but should not be the only source for later reconciliation.

### Webhook processing

Add database-level idempotency using a unique provider event ID. Also consider recording:

- processing status
- retry count
- last error
- next retry time

An admin replay action and a dead-letter view would make failed webhook recovery much safer.

### Docker management

Access to `/var/run/docker.sock` is effectively host-level control. The current validation wizard is useful but is not a security boundary.

Recommended safeguards:

- restrict allowed registries and images
- restrict volume mounts
- reject privileged and host-network configurations unless explicitly allowed
- apply operation timeouts
- record every Docker action in the audit log
- isolate Docker operations behind a more restricted service where practical

### nginx management

The nginx integration writes system configuration and can reload the host proxy. Improve it with:

- atomic temporary-file writes
- configuration backups
- config diff preview
- `nginx -t` before replacement
- rollback after failed reload
- separation of generated and hand-managed configuration
- recovery tooling for failed changes

### Observability

Logging exists, but production support would benefit from:

- structured JSON logs
- request/correlation IDs
- gate decision metrics
- payment success/failure metrics
- webhook latency and failure metrics
- Redis/PostgreSQL availability metrics
- auto-block counts
- Docker operation durations
- alerts when fail-open behavior is used

Add separate liveness and readiness endpoints. A liveness endpoint should answer whether the process is alive; readiness should indicate whether required dependencies are usable.

### Test coverage

The current tests cover server authentication scaffolding and Docker/nginx helpers, but the highest-risk business paths need more coverage:

- active, blocked, archived, and unknown gate states
- Redis cache hit/miss and outage behavior
- webhook signature validation
- duplicate webhook delivery
- wrong amount/project payment
- payment reversal
- reconciliation
- auto-blocking and grace periods
- repository behavior against a test database
- role authorization
- nginx apply and rollback behavior
- end-to-end payment flow using a fake provider

## Features worth adding

### Immediate product features

1. **Customer payment status page**

   Show amount due, payment status, last payment, next due date, confirmation progress, and support contact.

2. **Payment receipts**

   Generate downloadable receipts and send them by email after successful payment.

3. **Email notifications**

   Notify clients about upcoming due dates, grace-period warnings, blocking, successful payments, failed payments, and reversals.

4. **Recurring billing**

   Add billing plans, recurring invoices, and automatic payment reminders.

5. **Admin user management**

   Support multiple admins, roles, permissions, password changes/resets, account disablement, token revocation, and login history.

6. **Project health dashboard**

   Show gate state, container health, port mappings, nginx status, certificate expiry, payment status, next block date, and recent errors.

7. **Better audit log**

   Include IP address, request ID, actor user ID, before/after values, action category, filters, and export.

8. **Bulk operations**

   Support bulk block/unblock, grace-period extensions, reminders, nginx regeneration, and failed-webhook retries.

9. **Self-service client portal**

   Allow clients to view invoices, update contact details, download receipts, and manage payment methods without admin access.

10. **Payment-provider abstraction**

    Introduce a provider interface so Paystack can later be joined by Stripe, Flutterwave, M-Pesa, or manual bank transfer.

## Recommended priority roadmap

### P0 — Before relying on production billing

- Validate payment amount, currency, project association, and reference.
- Add database-level webhook event idempotency.
- Fix nullable update semantics.
- Introduce versioned database migrations.
- Add payment and gate integration tests.
- Add login rate limiting.
- Review and explicitly approve fail-open behavior.

### P1 — Operational safety and maintainability

- Replace the 30-second auto-block loop.
- Add readiness checks and metrics.
- Add nginx atomic writes, backups, and rollback.
- Strengthen Docker restrictions.
- Add webhook retry/dead-letter handling.
- Separate lifecycle, access, billing, and block-reason state.

### P2 — Product growth

- Email notifications.
- Receipts.
- Customer payment status page.
- Recurring billing and invoices.
- Admin roles and user management.
- Client portal.
- Multi-provider payments.

## Related documentation

- [Project index and architecture](README.md)
- [REST API reference](API.md)
- [nginx client gating](nginx-client-gating.md)
- [nginx reverse proxy](nginx-reverse-proxy.md)
- [Staging deployment](staging-deployment.md)
- [Backend development plan](backend-development-plan.md) — historical planning reference
