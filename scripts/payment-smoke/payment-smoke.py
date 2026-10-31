#!/usr/bin/env python3
"""Exercise service invoicing, cash recording, and signed Paystack webhook handling."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from decimal import Decimal
from typing import Any


class SmokeFailure(RuntimeError):
    pass


@dataclass
class ApiFailure(SmokeFailure):
    status: int
    detail: str

    def __str__(self) -> str:
        return f"HTTP {self.status}: {self.detail}"


class GatekeeperApi:
    def __init__(self, base_url: str, timeout: int = 20) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, Any]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request_headers = {"Accept": "application/json"}
        if data is not None:
            request_headers["Content-Type"] = "application/json"
        if self.token:
            request_headers["Authorization"] = f"Bearer {self.token}"
        if headers:
            request_headers.update(headers)
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=data, headers=request_headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return response.status, self._decode(raw)
        except urllib.error.HTTPError as error:
            raw = error.read()
            parsed = self._decode(raw)
            raise ApiFailure(error.code, self._error_text(parsed, raw)) from None
        except (urllib.error.URLError, TimeoutError) as error:
            raise SmokeFailure(f"Request failed for {method} {path}: {error}") from None

    @staticmethod
    def _decode(raw: bytes) -> Any:
        if not raw:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return raw.decode("utf-8", errors="replace")

    @staticmethod
    def _error_text(parsed: Any, raw: bytes) -> str:
        if isinstance(parsed, dict):
            for key in ("message", "error", "detail"):
                if parsed.get(key):
                    return str(parsed[key])
        return raw.decode("utf-8", errors="replace")[:500] or "request rejected"

    def login(self, email: str, password: str) -> None:
        _, result = self.request("POST", "/api/auth/login", {"email": email, "password": password})
        if not isinstance(result, dict) or not result.get("token"):
            raise SmokeFailure("Login did not return an admin token")
        self.token = str(result["token"])


REQUIRED_ENV_EXAMPLES = {
    "GATEKEEPER_SMOKE_TARGET": "local (or staging)",
    "GATEKEEPER_SMOKE_BASE_URL": "http://localhost:8080",
    "GATEKEEPER_SMOKE_ADMIN_EMAIL": "admin@example.net",
    "GATEKEEPER_SMOKE_ADMIN_PASSWORD": "<admin password; value is never printed>",
    "GATEKEEPER_SMOKE_PAYSTACK_TEST_SECRET": "sk_test_<Paystack test secret; value is never printed>",
}
CUSTOMER_EMAIL = "gatekeeper-payment-smoke@example.com"
INVOICE_AMOUNT = Decimal("100.00")
CASH_AMOUNT = Decimal("10.00")
CURRENCY = "KES"


def load_environment() -> dict[str, str]:
    values = {name: os.environ.get(name, "").strip() for name in REQUIRED_ENV_EXAMPLES}
    print("Payment smoke-test environment preflight (values are not echoed):")
    for name, example in REQUIRED_ENV_EXAMPLES.items():
        state = "SET" if values[name] else "NOT SET"
        print(f"  {name}: {state} (example: {example})")
    missing = [name for name, value in values.items() if not value]
    if missing:
        raise SmokeFailure("Required environment variables are not set: " + ", ".join(missing))
    if not values["GATEKEEPER_SMOKE_PAYSTACK_TEST_SECRET"].startswith("sk_test_"):
        raise SmokeFailure("GATEKEEPER_SMOKE_PAYSTACK_TEST_SECRET must be a Paystack test-mode secret beginning with 'sk_test_'")
    return values


def check_target(base_url: str) -> None:
    target = os.environ.get("GATEKEEPER_SMOKE_TARGET", "").strip().lower()
    if target not in {"local", "staging"}:
        raise SmokeFailure("Set GATEKEEPER_SMOKE_TARGET=local or staging; production is refused")
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise SmokeFailure("GATEKEEPER_SMOKE_BASE_URL must be an http(s) URL")
    if parsed.scheme != "https" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise SmokeFailure("Use HTTPS when sending admin credentials to a non-local target")
    if "prod" in parsed.hostname.lower():
        raise SmokeFailure("This smoke script refuses production targets")


def require_dict(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise SmokeFailure(f"Unexpected {context} response: expected an object")
    return value


def customer_for_smoke(api: GatekeeperApi) -> str:
    query = urllib.parse.urlencode({"q": CUSTOMER_EMAIL, "limit": 20})
    _, result = api.request("GET", f"/api/admin/dashboard/customers?{query}")
    page = require_dict(result, "customer search")
    customers = page.get("customers", [])
    existing = next((item for item in customers if item.get("contactEmail") == CUSTOMER_EMAIL), None)
    if existing:
        return str(existing["id"])
    _, result = api.request(
        "POST",
        "/api/admin/dashboard/customers",
        {"name": "Gatekeeper Payment Smoke Test", "contactEmail": CUSTOMER_EMAIL},
    )
    return str(require_dict(result, "customer creation")["id"])


def create_test_resources(api: GatekeeperApi, customer_id: str, run_id: str) -> dict[str, str]:
    _, result = api.request(
        "POST",
        "/api/admin/project-setup/projects",
        {
            "name": f"Gatekeeper Payment Smoke {run_id}",
            "type": "backend",
            "customerId": customer_id,
            "currency": CURRENCY,
            "amountDue": 0,
        },
    )
    project = require_dict(result, "project creation")
    project_id = str(project.get("projectId", ""))
    slug = str(project.get("slug", ""))
    if not project_id or not slug:
        raise SmokeFailure("Project creation did not return projectId and slug")
    _, result = api.request(
        "POST",
        f"/api/admin/projects/{project_id}/services",
        {"name": f"payment-smoke-{run_id}", "serviceType": "backend"},
    )
    service = require_dict(result, "service creation")
    service_id = str(service.get("id", ""))
    if not service_id:
        raise SmokeFailure("Service creation did not return an ID")
    return {"project_id": project_id, "slug": slug, "service_id": service_id}


def invoice(api: GatekeeperApi, project_id: str, service_id: str) -> dict[str, Any]:
    _, result = api.request(
        "GET",
        f"/api/admin/projects/{project_id}/services/{service_id}/invoice",
    )
    return require_dict(result, "service invoice")


def invoice_values(api: GatekeeperApi, project_id: str, service_id: str) -> tuple[Decimal, Decimal, str]:
    result = invoice(api, project_id, service_id)
    data = require_dict(result.get("invoice"), "invoice details")
    try:
        return Decimal(str(data["amount"])), Decimal(str(data["balance"])), str(data["currency"])
    except (KeyError, ArithmeticError, ValueError) as error:
        raise SmokeFailure(f"Service invoice is missing valid amount, balance, or currency: {error}") from None


def wait_for_invoice_balance(
    api: GatekeeperApi,
    project_id: str,
    service_id: str,
    expected_balance: Decimal,
    timeout_seconds: int,
    label: str,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last_balance: Decimal | None = None
    while time.monotonic() < deadline:
        result = invoice(api, project_id, service_id)
        data = require_dict(result.get("invoice"), "invoice details")
        try:
            last_balance = Decimal(str(data["balance"]))
        except (KeyError, ArithmeticError, ValueError):
            raise SmokeFailure("Service invoice response has no valid balance") from None
        if last_balance == expected_balance:
            print(f"PASS {label}; invoice balance is {data['currency']} {last_balance}")
            return result
        time.sleep(2)
    raise SmokeFailure(
        f"Timed out waiting for {label}; expected balance {expected_balance}, last balance was {last_balance}"
    )


def service_payments(api: GatekeeperApi, project_id: str, service_id: str) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode({"limit": 100, "offset": 0})
    _, result = api.request(
        "GET",
        f"/api/admin/projects/{project_id}/services/{service_id}/payments?{query}",
    )
    payments = require_dict(result, "service payments").get("payments")
    if not isinstance(payments, list):
        raise SmokeFailure("Service payment response has no payments list")
    return [item for item in payments if isinstance(item, dict)]


def pending_paystack_payment(api: GatekeeperApi, project_id: str, service_id: str) -> dict[str, Any]:
    payments = service_payments(api, project_id, service_id)
    pending = [
        item for item in payments
        if str(item.get("provider", "")).lower() == "paystack"
        and str(item.get("status", "")).lower() == "pending"
    ]
    if len(pending) != 1:
        raise SmokeFailure(f"Expected one pending Paystack payment for the service, found {len(pending)}")
    return pending[0]


def signed_webhook(
    api: GatekeeperApi,
    secret: str,
    payload: dict[str, Any],
    invalid_signature: bool = False,
) -> tuple[int, Any]:
    body = json.dumps(payload).encode("utf-8")
    signature = hmac.new(secret.encode("utf-8"), body, hashlib.sha512).hexdigest()
    if invalid_signature:
        signature = "0" * 128
    request = urllib.request.Request(
        f"{api.base_url}/api/paystack/webhook",
        data=body,
        headers={"Accept": "application/json", "Content-Type": "application/json", "x-paystack-signature": signature},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=api.timeout) as response:
            return response.status, api._decode(response.read())
    except urllib.error.HTTPError as error:
        raw = error.read()
        raise ApiFailure(error.code, api._error_text(api._decode(raw), raw)) from None
    except (urllib.error.URLError, TimeoutError) as error:
        raise SmokeFailure(f"Request failed for signed webhook: {error}") from None


def make_webhook(event: str, reference: str, amount: Decimal, slug: str, service_id: str) -> dict[str, Any]:
    return {
        "event": event,
        "data": {
            "reference": reference,
            "amount": int(amount * 100),
            "status": "success" if event == "charge.success" else "failed",
            "currency": CURRENCY,
            "metadata": {"project_slug": slug, "service_id": service_id},
            "customer": {"email": CUSTOMER_EMAIL},
        },
    }


def initiate_paystack(
    api: GatekeeperApi,
    slug: str,
    service_id: str,
    amount: Decimal,
) -> dict[str, Any]:
    path = f"/api/admin/projects/{urllib.parse.quote(slug, safe='')}/services/{service_id}/payment/initialize"
    _, result = api.request("POST", path, {"email": CUSTOMER_EMAIL, "amount": float(amount)})
    response = require_dict(result, "Paystack initialization")
    if not str(response.get("payment_link", "")).startswith("https://"):
        raise SmokeFailure("Paystack initialization did not return a secure checkout URL")
    return response


def assert_status(payment: dict[str, Any], expected: str) -> None:
    status = str(payment.get("status", "")).lower()
    if status != expected:
        raise SmokeFailure(f"Expected payment {payment.get('providerReference')} to be {expected}, got {status or 'unknown'}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=120, help="Scribed invoice balance synchronization timeout in seconds")
    args = parser.parse_args()

    settings = load_environment()
    base_url = settings["GATEKEEPER_SMOKE_BASE_URL"]
    check_target(base_url)
    api = GatekeeperApi(base_url)
    secret = settings["GATEKEEPER_SMOKE_PAYSTACK_TEST_SECRET"]
    run_id = secrets.token_hex(4)
    project: dict[str, str] | None = None

    print(f"Target: {base_url} ({os.environ['GATEKEEPER_SMOKE_TARGET']})")
    print(f"Test invoice: {CURRENCY} {INVOICE_AMOUNT}; cash partial: {CURRENCY} {CASH_AMOUNT}")
    print("Paystack: test-mode initialization plus synthetic HMAC-signed webhook; no card is charged")
    api.login(settings["GATEKEEPER_SMOKE_ADMIN_EMAIL"], settings["GATEKEEPER_SMOKE_ADMIN_PASSWORD"])
    print("PASS admin login")

    try:
        customer_id = customer_for_smoke(api)
        print("PASS dedicated smoke customer available")
        project = create_test_resources(api, customer_id, run_id)
        project_id, slug, service_id = project["project_id"], project["slug"], project["service_id"]
        print(f"PASS project and service created: {slug}")

        _, created = api.request(
            "POST",
            f"/api/admin/projects/{project_id}/services/{service_id}/invoice",
            {"description": f"Payment smoke test {run_id}", "amount": float(INVOICE_AMOUNT)},
        )
        if require_dict(created, "service invoice creation").get("status") not in {"created", "already_exists"}:
            raise SmokeFailure("Service invoice creation returned an unexpected status")
        total, balance, currency = invoice_values(api, project_id, service_id)
        if total != INVOICE_AMOUNT or balance != INVOICE_AMOUNT or currency.upper() != CURRENCY:
            raise SmokeFailure(f"Invoice mismatch: amount={total}, balance={balance}, currency={currency}")
        print(f"PASS service invoice created and linked: {currency} {total} outstanding")

        cash_receipt = f"smoke-{run_id}"
        _, cash_result = api.request(
            "POST",
            f"/api/admin/services/{service_id}/payments/cash",
            {
                "amount": float(CASH_AMOUNT),
                "currency": CURRENCY,
                "receiptNumber": cash_receipt,
                "notes": "Automated payment smoke test; test environment only",
            },
        )
        cash_payment = require_dict(cash_result, "cash payment")
        if str(cash_payment.get("status", "")).lower() != "success":
            raise SmokeFailure("Cash payment was not recorded as successful")
        print("PASS partial cash payment recorded against the service invoice")
        remaining = INVOICE_AMOUNT - CASH_AMOUNT
        wait_for_invoice_balance(api, project_id, service_id, remaining, args.timeout, "cash payment synced to Scribed")

        failed_amount = remaining
        initiate_paystack(api, slug, service_id, failed_amount)
        failed_pending = pending_paystack_payment(api, project_id, service_id)
        failed_reference = str(failed_pending.get("providerReference", ""))
        if not failed_reference or Decimal(str(failed_pending.get("amount", "0"))) != failed_amount:
            raise SmokeFailure("Pending Paystack payment does not match the requested service amount")
        failed_event = make_webhook("charge.failed", failed_reference, failed_amount, slug, service_id)

        try:
            signed_webhook(api, secret, failed_event, invalid_signature=True)
        except ApiFailure as error:
            if error.status != 401:
                raise
            print("PASS webhook rejects an invalid signature")
        else:
            raise SmokeFailure("Webhook accepted a deliberately invalid signature")

        _, failed_response = signed_webhook(api, secret, failed_event)
        if not isinstance(failed_response, dict) or failed_response.get("status") != "ok":
            raise SmokeFailure(f"Failed-payment webhook was not processed: {failed_response!r}")
        failed_payment = next(
            (item for item in service_payments(api, project_id, service_id)
             if item.get("providerReference") == failed_reference),
            None,
        )
        if failed_payment is None:
            raise SmokeFailure("Failed Paystack payment was not found in service payment history")
        assert_status(failed_payment, "failed")
        wait_for_invoice_balance(api, project_id, service_id, remaining, args.timeout, "failed payment left invoice balance unchanged")
        print("PASS failed Paystack event is recorded without reducing the service balance")

        initiate_paystack(api, slug, service_id, remaining)
        success_pending = pending_paystack_payment(api, project_id, service_id)
        success_reference = str(success_pending.get("providerReference", ""))
        if not success_reference or success_reference == failed_reference:
            raise SmokeFailure("Paystack did not create a new reference for the retry")
        if Decimal(str(success_pending.get("amount", "0"))) != remaining:
            raise SmokeFailure("Retry payment amount does not match the remaining invoice balance")
        success_event = make_webhook("charge.success", success_reference, remaining, slug, service_id)
        _, success_response = signed_webhook(api, secret, success_event)
        if not isinstance(success_response, dict) or success_response.get("status") != "ok":
            raise SmokeFailure(f"Successful-payment webhook was not processed: {success_response!r}")
        _, duplicate_response = signed_webhook(api, secret, success_event)
        if not isinstance(duplicate_response, dict) or duplicate_response.get("status") != "duplicate":
            raise SmokeFailure(f"Duplicate webhook was not idempotently acknowledged: {duplicate_response!r}")
        print("PASS successful Paystack webhook and duplicate delivery are idempotent")

        wait_for_invoice_balance(api, project_id, service_id, Decimal("0.00"), args.timeout, "successful payment synced to Scribed")
        final_payments = service_payments(api, project_id, service_id)
        by_reference = {item.get("providerReference"): item for item in final_payments}
        cash_payment_record = next(
            (item for item in final_payments
             if str(item.get("provider", "")).lower() == "cash"
             and item.get("providerReference") == cash_payment.get("reference")),
            None,
        )
        if cash_payment_record is None:
            raise SmokeFailure("Cash payment is missing from service payment history")
        assert_status(cash_payment_record, "success")
        if success_reference not in by_reference:
            raise SmokeFailure("Successful Paystack payment is missing from service payment history")
        assert_status(by_reference[success_reference], "success")
        if failed_reference not in by_reference:
            raise SmokeFailure("Failed Paystack payment is missing from service payment history")
        assert_status(by_reference[failed_reference], "failed")
        final_invoice = require_dict(invoice(api, project_id, service_id).get("invoice"), "final Scribed invoice")
        if Decimal(str(final_invoice.get("paid", "-1"))) != INVOICE_AMOUNT:
            raise SmokeFailure(f"Scribed invoice paid total is not {INVOICE_AMOUNT}")
        scribed_payments = final_invoice.get("payments")
        if not isinstance(scribed_payments, list):
            raise SmokeFailure("Scribed invoice response has no payment list")
        scribed_references = {item.get("provider_reference") for item in scribed_payments if isinstance(item, dict)}
        if cash_payment.get("reference") not in scribed_references or success_reference not in scribed_references:
            raise SmokeFailure("Scribed invoice is missing the cash or successful Paystack payment")
        print("PASS service payment history retains cash, failed, and successful payment records")
        print("PASS Scribed invoice shows the complete paid amount and both successful payment references")
        print("PAYMENT SMOKE TEST PASSED")
        return 0
    finally:
        if project:
            print(
                "PRESERVED payment smoke records for inspection; no cleanup is performed because "
                "payments, invoices, receipts, and audit history are durable: "
                f"project={project['slug']} projectId={project['project_id']} serviceId={project['service_id']}"
            )


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SmokeFailure as error:
        print(f"PAYMENT SMOKE TEST FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
