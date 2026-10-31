#!/usr/bin/env python3
"""Exercise real Gatekeeperd deployment APIs against an isolated local/staging instance."""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
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
    def __init__(self, base_url: str, timeout: int = 15) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
    ) -> tuple[int, Any]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=data, headers=headers, method=method
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
    "GATEKEEPER_SMOKE_TARGET": "staging (or local)",
    "GATEKEEPER_SMOKE_BASE_URL": "https://gatekeeper-staging.example.net",
    "GATEKEEPER_SMOKE_ADMIN_EMAIL": "admin@example.net",
    "GATEKEEPER_SMOKE_ADMIN_PASSWORD": "<admin password; value is never printed>",
}

SMOKE_IMAGE_REGISTRY = "docker.io"
SMOKE_IMAGE_NAME = "library/postgres"
SMOKE_IMAGE_TAG = "17-alpine"


def load_required_environment() -> dict[str, str]:
    values = {name: os.environ.get(name, "").strip() for name in REQUIRED_ENV_EXAMPLES}
    missing = [name for name, value in values.items() if not value]
    print("Smoke-test environment preflight (values are not echoed):")
    for name, example in REQUIRED_ENV_EXAMPLES.items():
        state = "SET" if values[name] else "NOT SET"
        print(f"  {name}: {state} (example: {example})")
    db_password = os.environ.get("GATEKEEPER_SMOKE_DB_PASSWORD", "").strip()
    print(
        "  GATEKEEPER_SMOKE_DB_PASSWORD: "
        + ("SET (actual value hidden)" if db_password else "NOT SET; a random value will be generated")
    )
    if missing:
        raise SmokeFailure("Required environment variables are not set: " + ", ".join(missing))
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
    host = parsed.hostname.lower()
    if "prod" in host or target == "production":
        raise SmokeFailure("This smoke script refuses production targets")


def require_dict(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise SmokeFailure(f"Unexpected {context} response: expected an object")
    return value


def wait_for_deployment(
    api: GatekeeperApi,
    slug: str,
    service_id: str,
    deployment_id: str,
    expected: set[str],
    timeout_seconds: int,
    label: str,
) -> dict[str, Any]:
    query = urllib.parse.urlencode({"environment": "production", "serviceId": service_id})
    path = f"/api/admin/projects/{urllib.parse.quote(slug, safe='')}/deployments/history?{query}"
    deadline = time.monotonic() + timeout_seconds
    last_status = "unknown"
    last_report = 0.0
    while time.monotonic() < deadline:
        _, result = api.request("GET", path)
        page = require_dict(result, "deployment history")
        items = page.get("items")
        if not isinstance(items, list):
            raise SmokeFailure("Deployment history response has no items list")
        item = next((row for row in items if row.get("id") == deployment_id), None)
        if item:
            last_status = str(item.get("status", "unknown")).lower()
            now = time.monotonic()
            if now - last_report >= 5:
                restart_count = item.get("restartCount")
                suffix = f", restarts={restart_count}" if restart_count is not None else ""
                print(f"  {label}: {last_status}{suffix}", flush=True)
                last_report = now
            if last_status in expected:
                return item
            if last_status in {"cancelled", "rolled-back"} and last_status not in expected:
                raise SmokeFailure(f"{label} ended unexpectedly in {last_status}")
        time.sleep(2)
    raise SmokeFailure(
        f"Timed out after {timeout_seconds}s waiting for {label}; last status was {last_status}"
    )


def active_deployment(api: GatekeeperApi, project_id: str, service_id: str) -> dict[str, Any]:
    query = urllib.parse.urlencode({"environment": "production"})
    path = f"/api/admin/projects/{project_id}/services/{service_id}/active-deployment?{query}"
    _, result = api.request("GET", path)
    return require_dict(result, "active deployment")


def customer_for_smoke(api: GatekeeperApi) -> tuple[str, bool]:
    email = "gatekeeper-deployment-smoke@example.invalid"
    query = urllib.parse.urlencode({"q": email, "limit": 20})
    _, result = api.request("GET", f"/api/admin/dashboard/customers?{query}")
    page = require_dict(result, "customer search")
    rows = page.get("customers", [])
    existing = next((row for row in rows if row.get("contactEmail") == email), None)
    if existing:
        return str(existing["id"]), False
    _, created = api.request(
        "POST",
        "/api/admin/dashboard/customers",
        {"name": "Gatekeeper Deployment Smoke Test", "contactEmail": email},
    )
    customer = require_dict(created, "customer creation")
    return str(customer["id"]), True


def create_test_project(api: GatekeeperApi, customer_id: str, run_id: str) -> dict[str, str]:
    _, result = api.request(
        "POST",
        "/api/admin/project-setup/projects",
        {
            "name": f"Gatekeeper Smoke {run_id}",
            "type": "backend",
            "customerId": customer_id,
            "currency": "KES",
            "amountDue": 0,
        },
    )
    project = require_dict(result, "project creation")
    project_id, slug = str(project.get("projectId", "")), str(project.get("slug", ""))
    if not project_id or not slug:
        raise SmokeFailure("Project creation did not return projectId and slug")
    try:
        _, result = api.request(
            "POST",
            f"/api/admin/projects/{project_id}/services",
            {"name": f"smoke-{run_id}", "serviceType": "backend"},
        )
    except Exception:
        try:
            api.request("DELETE", f"/api/admin/projects/{urllib.parse.quote(slug, safe='')}")
        except Exception:
            pass
        raise
    service = require_dict(result, "service creation")
    service_id = str(service.get("id", ""))
    if not service_id:
        raise SmokeFailure("Service creation did not return an ID")
    return {"project_id": project_id, "slug": slug, "service_id": service_id}


def configure_service(
    api: GatekeeperApi,
    project_id: str,
    service_id: str,
    registry: str,
    image_name: str,
    image_tag: str,
    password: str,
) -> None:
    api.request(
        "PUT",
        f"/api/admin/project-setup/projects/{project_id}/source-runtime",
        {
            "repository": None,
            "gitRef": "main",
            "registry": registry,
            "imageName": image_name,
            "imageTag": image_tag,
            "serviceId": service_id,
            "containerPort": 5432,
            "network": "bridge",
            "restartPolicy": "unless-stopped",
            "environment": "production",
            "env": {"POSTGRES_PASSWORD": password},
            "readinessType": "tcp",
            "readinessTarget": "5432",
            "readinessTimeoutSeconds": 60,
            "readinessIntervalSeconds": 2,
            "readinessProbeTimeoutMillis": 1000,
            "autoDeploy": False,
        },
    )


def queue_deployment(api: GatekeeperApi, project_id: str, service_id: str) -> str:
    query = urllib.parse.urlencode({"serviceId": service_id})
    _, result = api.request("POST", f"/api/admin/project-setup/projects/{project_id}/deploy?{query}")
    deployment = require_dict(result, "deployment queue")
    deployment_id = str(deployment.get("deploymentId", ""))
    if not deployment_id:
        raise SmokeFailure("Deployment request did not return a deploymentId")
    return deployment_id


def update_environment(
    api: GatekeeperApi,
    project_id: str,
    service_id: str,
    values: dict[str, str],
) -> str:
    query = urllib.parse.urlencode({"environment": "production"})
    _, result = api.request(
        "PUT",
        f"/api/admin/projects/{project_id}/services/{service_id}/environment?{query}",
        {"environment": "production", "values": values, "replaceExisting": True},
    )
    response = require_dict(result, "service environment update")
    deployment_ids = response.get("deploymentIds")
    if not isinstance(deployment_ids, list) or len(deployment_ids) != 1:
        raise SmokeFailure(f"Expected one deployment from environment update, got {deployment_ids!r}")
    return str(deployment_ids[0])


def test_nginx(api: GatekeeperApi, project: dict[str, str], run_id: str) -> str:
    project_id, slug, service_id = project["project_id"], project["slug"], project["service_id"]
    domain = f"gk-smoke-{run_id}.invalid"
    _, binding_result = api.request(
        "POST",
        f"/api/admin/projects/{project_id}/services/{service_id}/domains",
        {"domain": domain, "containerPort": 5432},
    )
    binding = require_dict(binding_result, "domain binding")
    binding_id = str(binding.get("id", ""))
    if not binding_id:
        raise SmokeFailure("Domain binding creation did not return an ID")
    target = {"serviceId": service_id, "domain": domain, "domainBindingId": binding_id, "requireSsl": True}
    try:
        api.request("POST", "/api/admin/nginx/enable", target)
    except ApiFailure as error:
        if error.status >= 500 or "certificate" not in error.detail.lower():
            raise
        print("PASS Nginx rejects required TLS when no certificate matches the test domain")
    else:
        raise SmokeFailure("Nginx unexpectedly enabled a certificate-less .invalid test domain")

    query = urllib.parse.urlencode({"serviceId": service_id})
    _, context = api.request("GET", f"/api/admin/nginx/wizard/context/{urllib.parse.quote(slug, safe='')}?{query}")
    if not isinstance(context, dict):
        raise SmokeFailure("Nginx wizard context did not return an object")
    status_path = f"/api/admin/nginx/status/{urllib.parse.quote(slug, safe='')}"
    try:
        _, before = api.request("GET", status_path)
        if isinstance(before, dict) and before.get("enabled"):
            raise SmokeFailure("Failed TLS validation unexpectedly enabled an Nginx site")
    except ApiFailure as error:
        if error.status != 404:
            raise

    target["requireSsl"] = False
    api.request("POST", "/api/admin/nginx/wizard/validate", target)
    try:
        _, after_preview = api.request("GET", status_path)
        if isinstance(after_preview, dict) and after_preview.get("enabled"):
            raise SmokeFailure("Nginx validation preview unexpectedly enabled the site")
    except ApiFailure as error:
        if error.status != 404:
            raise
    api.request("POST", "/api/admin/nginx/enable", target)
    api.request("POST", "/api/admin/nginx/enable", target)
    _, enabled = api.request("GET", status_path)
    if not isinstance(enabled, dict) or not enabled.get("enabled"):
        raise SmokeFailure("Nginx enable did not leave the test site enabled")
    print("PASS Nginx preview, missing-certificate rejection, enable, and repeated enable")
    return domain


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nginx", action="store_true", help="also exercise Nginx validation and enable APIs")
    parser.add_argument("--timeout", type=int, default=240, help="deployment terminal-state timeout in seconds")
    parser.add_argument("--keep-resources", action="store_true", help="leave the test service/project for inspection")
    args = parser.parse_args()

    settings = load_required_environment()
    base_url = settings["GATEKEEPER_SMOKE_BASE_URL"]
    check_target(base_url)
    email = settings["GATEKEEPER_SMOKE_ADMIN_EMAIL"]
    admin_password = settings["GATEKEEPER_SMOKE_ADMIN_PASSWORD"]
    registry = SMOKE_IMAGE_REGISTRY
    image_name = SMOKE_IMAGE_NAME
    image_tag = SMOKE_IMAGE_TAG
    db_password = os.environ.get("GATEKEEPER_SMOKE_DB_PASSWORD") or secrets.token_urlsafe(24)
    run_id = secrets.token_hex(4)
    api = GatekeeperApi(base_url)
    project: dict[str, str] | None = None
    project_id = ""
    slug = ""
    service_id = ""
    cleanup_errors: list[str] = []
    nginx_domain: str | None = None

    print(f"Target: {base_url} ({os.environ['GATEKEEPER_SMOKE_TARGET']})")
    print(f"Fixture: {registry}/{image_name}:{image_tag}")
    api.login(email, admin_password)
    print("PASS admin login")

    customer_id, customer_created = customer_for_smoke(api)
    print("PASS customer available" + (" (created)" if customer_created else " (reused)"))
    project = create_test_project(api, customer_id, run_id)
    project_id, slug, service_id = project["project_id"], project["slug"], project["service_id"]
    print(f"PASS project and service created: {slug}")

    try:
        print("Deployment environment expectations:")
        print("  Deployment 1: POSTGRES_PASSWORD is set (example: smoke-password-123; actual value hidden)")
        print("  Deployment 2: POSTGRES_PASSWORD is NOT SET intentionally; Postgres should exit")
        print("  Deployment 3: POSTGRES_PASSWORD is restored to the same non-empty value")
        configure_service(api, project_id, service_id, registry, image_name, image_tag, db_password)
        first_id = queue_deployment(api, project_id, service_id)
        first = wait_for_deployment(api, slug, service_id, first_id, {"active"}, args.timeout, "deployment 1")
        active = active_deployment(api, project_id, service_id)
        if active.get("deploymentId") != first_id:
            raise SmokeFailure("First deployment became active, but the active pointer does not match it")
        print("PASS deployment 1 becomes active with POSTGRES_PASSWORD")

        if args.nginx:
            nginx_domain = test_nginx(api, project, run_id)

        print("Updating service environment: POSTGRES_PASSWORD is NOT SET for deployment 2")
        second_id = update_environment(api, project_id, service_id, {})
        second = wait_for_deployment(api, slug, service_id, second_id, {"failed"}, args.timeout, "deployment 2")
        active = active_deployment(api, project_id, service_id)
        if active.get("deploymentId") != first_id:
            raise SmokeFailure("Failed deployment replaced or cleared the previous active deployment")
        if args.nginx:
            status_path = f"/api/admin/nginx/status/{urllib.parse.quote(slug, safe='')}"
            _, nginx_status = api.request("GET", status_path)
            if not isinstance(nginx_status, dict) or not nginx_status.get("enabled"):
                raise SmokeFailure("Nginx site was not left enabled after the failed candidate")
        failure = second.get("failureReason") or "no failure reason returned"
        print(f"PASS deployment 2 fails with POSTGRES_PASSWORD unset; deployment 1 remains active ({failure})")

        print("Updating service environment: restoring POSTGRES_PASSWORD for deployment 3 (actual value hidden)")
        third_id = update_environment(api, project_id, service_id, {"POSTGRES_PASSWORD": db_password})
        wait_for_deployment(api, slug, service_id, third_id, {"active"}, args.timeout, "deployment 3")
        active = active_deployment(api, project_id, service_id)
        if active.get("deploymentId") != third_id:
            raise SmokeFailure("Third deployment became active, but the active pointer does not match it")
        print("PASS deployment 3 recovers after restoring POSTGRES_PASSWORD")
        print("SMOKE TEST PASSED")
        return 0
    finally:
        if not args.keep_resources and project is not None:
            if service_id:
                try:
                    history_query = urllib.parse.urlencode({"environment": "production", "serviceId": service_id})
                    _, history = api.request(
                        "GET",
                        f"/api/admin/projects/{urllib.parse.quote(slug, safe='')}/deployments/history?{history_query}",
                    )
                    cleanup_blocked = False
                    for item in require_dict(history, "cleanup deployment history").get("items", []):
                        status = str(item.get("status", "")).lower()
                        if status in {"queued", "building", "starting", "health-checking"}:
                            deployment_id = urllib.parse.quote(str(item.get("id", "")), safe="")
                            try:
                                api.request(
                                    "POST",
                                    f"/api/admin/projects/{urllib.parse.quote(slug, safe='')}/deployments/{deployment_id}/cancel",
                                )
                            except ApiFailure:
                                cleanup_blocked = True
                        elif status in {"ready", "cutover_in_progress"}:
                            cleanup_blocked = True
                    if cleanup_blocked:
                        cleanup_errors.append("service cleanup skipped because a deployment could not be safely cancelled")
                    else:
                        api.request("DELETE", f"/api/admin/projects/{project_id}/services/{service_id}")
                        print("CLEANUP test service and its containers/domains")
                        if nginx_domain:
                            reuse_name = f"reuse-{run_id}"
                            _, reuse_result = api.request(
                                "POST",
                                f"/api/admin/projects/{project_id}/services",
                                {"name": reuse_name, "serviceType": "backend"},
                            )
                            reuse_service_id = str(require_dict(reuse_result, "domain reuse service").get("id", ""))
                            if not reuse_service_id:
                                raise SmokeFailure("Could not create the service used to verify domain release")
                            api.request(
                                "POST",
                                f"/api/admin/projects/{project_id}/services/{reuse_service_id}/domains",
                                {"domain": nginx_domain},
                            )
                            api.request("DELETE", f"/api/admin/projects/{project_id}/services/{reuse_service_id}")
                            print("PASS service deletion released its domain for reuse")
                except Exception as error:  # cleanup should not hide the test's original failure
                    cleanup_errors.append(f"service cleanup failed: {error}")
            if slug and not cleanup_errors:
                try:
                    api.request("DELETE", f"/api/admin/projects/{urllib.parse.quote(slug, safe='')}")
                    print("CLEANUP archived test project")
                except Exception as error:
                    cleanup_errors.append(f"project archive failed: {error}")
            if cleanup_errors:
                for error in cleanup_errors:
                    print(f"CLEANUP WARNING: {error}", file=sys.stderr)
                print("Inspect the staging instance before running the smoke script again.", file=sys.stderr)
                if sys.exc_info()[0] is None:
                    raise SmokeFailure("Smoke test passed but resource cleanup failed")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SmokeFailure as error:
        print(f"SMOKE TEST FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
