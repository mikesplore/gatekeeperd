# Gatekeeperd Fix Journey

This document defines the recommended order for improving Gatekeeperd. The sequence prioritizes payment correctness and access-control safety before operational polish and new product features.

## Guiding principle

Stabilize the payment and gating foundation before expanding the billing model or adding substantial customer-facing functionality.

The first milestone is:

> A payment can never activate the wrong project, for the wrong amount, and can safely be delivered multiple times.

## Phase 0 — Establish a safe baseline

Before changing behavior:

- Confirm whether the billing model is one-time overdue payment or recurring billing.
- Confirm whether fail-open behavior is acceptable for production.
- Capture current API behavior and database schema.
- Set up repeatable local PostgreSQL and Redis test dependencies.
- Make Gradle and test execution reliable for developers and CI.
- Add CI checks for tests, formatting, and database migrations.

### Deliverable

A documented baseline with repeatable tests and an agreed definition of correct behavior.

## Phase 1 — Protect payment correctness

Payment errors affect both money and access control, so this is the first implementation phase.

1. Validate payment reference ownership.
2. Validate payment amount and currency.
3. Add unique webhook event IDs.
4. Make webhook processing fully idempotent.
5. Route callback verification through the same payment-processing service.
6. Add tests for payment mismatches and replayed events.
7. Add failed-webhook visibility and retry support.

### Deliverable

Every payment can be safely processed, replayed, rejected, or investigated without activating the wrong project.

## Phase 2 — Make project state reliable

Refactor state handling before introducing more billing behavior.

Separate these concepts:

- lifecycle: active or archived
- access: allowed or blocked
- block reason: overdue, manual, reversed, or suspended
- billing state: current, due, or failed

Then fix:

- nullable update semantics
- transactional status changes
- Redis cache invalidation
- stale cache behavior
- auto-blocker frequency
- duplicate auto-block updates and audit records

### Deliverable

Project state remains predictable across the API, PostgreSQL, Redis, and background jobs.

## Phase 3 — Add production safety

Harden administrative and infrastructure operations.

- Introduce versioned database migrations.
- Add login rate limiting.
- Add password change and reset flows.
- Add admin roles and permissions.
- Restrict Docker images, volumes, networking, and privileged operations.
- Add nginx configuration backups, atomic writes, validation, and rollback.
- Add separate liveness and readiness checks.
- Add request IDs and structured logs.
- Add metrics and alerts for important failure modes.

### Deliverable

The service can be operated safely through failures, deployments, and infrastructure changes.

## Phase 4 — Improve operational workflows

Make the existing system easier to operate without direct database or server-shell access.

- Project health dashboard
- Payment and webhook troubleshooting
- Audit filtering and export
- Bulk block/unblock actions
- Failed-webhook replay
- nginx configuration preview
- Certificate expiry warnings
- Container health and deployment status

### Deliverable

An administrator can diagnose and resolve common operational issues from the dashboard.

## Phase 5 — Add customer-facing value

Once payment and access behavior are trustworthy, improve the client experience.

- Customer payment status page
- Payment receipts
- Email reminders
- Payment-success confirmation screen
- Client self-service portal
- Support/contact workflow

### Deliverable

Clients understand why access is blocked, how much is due, and how to restore access.

## Phase 6 — Expand the billing model

Only after the payment foundation is stable, consider:

- invoices
- recurring billing
- billing plans
- partial payments
- discounts and credits
- refunds
- multiple payment providers
- multi-currency support

### Deliverable

Gatekeeperd evolves from a payment-gated access switch into a broader billing platform.

## Recommended development rhythm

For every phase:

1. Write the desired behavior.
2. Add failing tests for that behavior.
3. Implement the smallest safe change.
4. Run unit and integration tests.
5. Update the API, documentation, and configuration examples.
6. Deploy to staging.
7. Test failure and recovery paths.
8. Move to the next item only after the behavior is verified.

Each phase should produce a small, reviewable change set rather than mixing payment, infrastructure, and product work into one release.

## Suggested first work package

Start with a payment-integrity vertical slice:

1. Add a payment-event uniqueness constraint or migration.
2. Make payment application use the locally stored payment/project association.
3. Validate amount and currency before activation.
4. Make repeated webhook delivery a no-op.
5. Add integration tests for success, mismatch, replay, and reversal.
6. Document the resulting payment state transitions in `docs/API.md`.

This gives the project a narrow, testable first milestone and reduces the largest current risk before broader refactoring.

## Related documents

- [Architecture and product review](architecture-and-product-review.md)
- [Project index and architecture](README.md)
- [REST API reference](API.md)
- [Staging deployment](staging-deployment.md)
