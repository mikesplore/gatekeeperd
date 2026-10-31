# Payment smoke test

This external API smoke test exercises a service invoice, a partial cash payment, Paystack payment initialization in test mode, and signed webhook handling against a running local or staging Gatekeeperd instance.

The script uses a KES 100 service invoice. It records KES 10 as cash, initializes a Paystack test-mode payment for the remaining KES 90, sends a failed test webhook, then initializes a new KES 90 payment and sends a successful test webhook twice. It checks that:

- The cash payment reduces the service invoice balance to KES 90.
- A webhook with an invalid signature is rejected.
- A failed Paystack event does not reduce the balance.
- A successful Paystack event is recorded against the service invoice.
- Replaying the same success event is acknowledged as a duplicate and does not double count.
- Scribed eventually reports the invoice paid, and Gatekeeper's service payment history retains cash, failed, and successful records.

The script does **not** open checkout or charge a card. It creates pending Paystack test transactions through Gatekeeperd, then sends synthetic webhook payloads signed with the Paystack test secret. This covers Gatekeeperd's webhook signature, event, idempotency, service attribution, and Scribed synchronization paths; it does not test Paystack's hosted checkout or card authorization.

## Safety and retained test records

Run only against local or staging Gatekeeperd, configured with the same Paystack test secret as `GATEKEEPER_SMOKE_PAYSTACK_TEST_SECRET`. The script rejects anything except a `sk_test_` secret and refuses production targets.

Gatekeeperd must be connected to an isolated test Scribed instance. The script creates a service invoice and records cash/payment events; successful payment synchronization creates a Scribed receipt. These are durable billing records, so the script deliberately does not delete or archive its project, service, invoice, payments, or audit history. It prints the project and service IDs for inspection. The dedicated smoke-test customer is reused across runs.

## Run

Requires Python 3, a local/staging Gatekeeperd instance, a matching Paystack test secret on both the script and Gatekeeperd, working Paystack test-mode initialization, and Scribed integration configured on Gatekeeperd.

```bash
export GATEKEEPER_SMOKE_TARGET=local # or staging
export GATEKEEPER_SMOKE_BASE_URL=http://localhost:8080
export GATEKEEPER_SMOKE_ADMIN_EMAIL=admin@example.net
export GATEKEEPER_SMOKE_ADMIN_PASSWORD='...'
export GATEKEEPER_SMOKE_PAYSTACK_TEST_SECRET='sk_test_...'

python3 scripts/payment-smoke/payment-smoke.py
```

Preflight prints every required variable as `SET` or `NOT SET` and never echoes the admin password or Paystack secret. The Scribed balance synchronization timeout defaults to two minutes; pass `--timeout 240` to increase it.
