# Gatekeeperd Phase 0 Baseline

This document records the product decisions and technical baseline for the first improvement phase. Phase 0 establishes what Gatekeeperd is meant to support before payment and state changes are implemented.

## Product direction

Gatekeeperd serves two related use cases:

### 1. Development and testing hosting

During a project build, the developer hosts the client application on the developer's VPS. Gatekeeperd gates access while:

- the application is being built
- the client is testing or reviewing it
- agreed project fees are outstanding
- the client needs temporary access to a staging or preview environment

After the agreed fees are paid, the application can be deployed to the client's own infrastructure. Gatekeeperd should not require the client to remain on the developer's VPS for this use case.

### 2. Permanent production gating

Some companies may continue using the gated application in production. In that case, Gatekeeperd remains part of the long-term access and billing workflow, either:

- hosted by the developer as a managed service, or
- deployed into the client's own infrastructure

The project model must therefore support both temporary hosting and permanent operation without changing the core gate mechanism.

## Billing direction

Gatekeeperd should support both billing modes:

### One-time overdue payment

Used for project milestones, build fees, deployment fees, support charges, or a one-off invoice.

Expected behavior:

- an amount is assigned to a project or invoice
- a due date and grace period may be configured
- the project is blocked after the grace period expires
- one successful payment clears the outstanding obligation
- the project may then be transferred, archived, or kept under permanent gating

### Recurring payment

Used when a client continues to rely on Gatekeeperd for production access control.

Expected behavior:

- a billing plan defines the recurring interval and amount
- each billing period creates an obligation or invoice
- the project remains active while the billing account is current
- failed or overdue renewal payments begin a grace period
- the project is blocked when the grace period expires
- successful payment restores access and advances the billing period

## Important modeling decision

Billing mode should not be encoded directly into the project access status.

These are separate concepts:

- **Deployment mode**: developer-hosted, client-hosted, or external-hosted
- **Service mode**: development/testing or production
- **Billing mode**: one-time or recurring
- **Lifecycle**: active, transferred, archived, or cancelled
- **Access state**: allowed or blocked
- **Block reason**: overdue, manual, reversed, failed renewal, or suspended

Keeping these dimensions separate allows a project to move through a lifecycle such as:

```text
developer-hosted build
        ↓
client testing / review
        ↓
one-time fee paid
        ↓
transferred to client infrastructure
        ↓
optional permanent production gating
        ↓
recurring billing and renewal enforcement
```

## Current technical baseline

The application already has the required basic test dependencies available:

- PostgreSQL test database
- Redis test instance
- Ktor test support
- Gradle-based Kotlin build

Phase 0 does not need to provision new PostgreSQL or Redis services. The next technical task is to make the existing setup repeatable and documented for developers and CI.

The current implementation already contains:

- `projects.amount_due`
- `projects.currency`
- `projects.due_date`
- `projects.grace_period_days`
- Paystack payment records
- payment webhooks and reconciliation
- project status gating

These fields are enough to stabilize one-time overdue payments first. They are not yet sufficient for recurring billing because there are no billing plans, invoices, billing periods, or subscription state.

## Phase 0 decisions

The following decisions are now the working baseline:

1. Gatekeeperd supports both temporary project hosting and permanent production gating.
2. A project may be transferred from developer infrastructure to client infrastructure without losing its billing or audit history.
3. Gatekeeperd supports both one-time and recurring payment models.
4. One-time overdue payment is the first payment model to stabilize because it matches the current schema and code.
5. Recurring billing will be added after the payment-integrity and state-consistency work is complete.
6. Billing mode, hosting mode, service mode, lifecycle, and access state must remain separate fields/concepts.
7. PostgreSQL and Redis are already available for testing; environment reproducibility and test coverage are the remaining Phase 0 infrastructure concerns.

## Open decisions before recurring billing implementation

These do not block the first payment-integrity work, but they must be decided before implementing subscriptions:

- Will recurring billing be managed by Paystack subscriptions, generated invoices, or an internal schedule?
- Is a production-gated project hosted by the developer, the client, or either party?
- Does transferring a project change its slug/domain, or only its deployment metadata?
- What happens to access when a project is transferred but recurring billing remains active?
- Are one-time payments attached to projects, invoices, milestones, or all three?
- Should clients be able to pause, cancel, or change recurring plans themselves?
- What is the grace-period policy for failed recurring renewals?
- Which currencies and payment providers must be supported?

## Phase 0 completion criteria

Phase 0 is complete when:

- the product distinction between development hosting and permanent production gating is documented
- one-time and recurring billing are explicitly represented as separate concepts
- the current PostgreSQL and Redis test setup can be started or accessed consistently
- the project lifecycle and transfer behavior are agreed
- the first payment-integrity work package is ready to implement
- no recurring-billing schema is introduced before its lifecycle decisions are settled

## Next implementation phase

Proceed to the payment-integrity vertical slice described in [fix-journey.md](fix-journey.md):

1. Add payment-event uniqueness and migration support.
2. Validate local payment/project ownership.
3. Validate amount and currency.
4. Make repeated webhook delivery a no-op.
5. Add integration tests for success, mismatch, replay, and reversal.

## Related documents

- [Fix journey](fix-journey.md)
- [Architecture and product review](architecture-and-product-review.md)
- [Project index and architecture](README.md)
- [REST API reference](API.md)
