#!/usr/bin/env python3
"""Exercise role management and live permission enforcement against Gatekeeperd."""

from __future__ import annotations

import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


class SmokeFailure(RuntimeError):
    pass


class GatekeeperApi:
    def __init__(self, base_url: str, timeout: int = 15) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token: str | None = None

    def request(
        self, method: str, path: str, body: dict[str, Any] | None = None
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
                return response.status, self._decode(response.read())
        except urllib.error.HTTPError as error:
            return error.code, self._decode(error.read())
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


def expect_status(
    api: GatekeeperApi,
    method: str,
    path: str,
    expected: int,
    body: dict[str, Any] | None = None,
) -> Any:
    status, result = api.request(method, path, body)
    if status != expected:
        detail = result.get("message", result.get("error", result)) if isinstance(result, dict) else result
        raise SmokeFailure(f"{method} {path}: expected HTTP {expected}, got {status}: {detail}")
    print(f"PASS {method} {path} -> {status}")
    return result


def login(api: GatekeeperApi, email: str, password: str) -> None:
    result = expect_status(api, "POST", "/api/auth/login", 200, {"email": email, "password": password})
    if not isinstance(result, dict) or not result.get("token"):
        raise SmokeFailure("Login did not return an access token (a 2FA challenge is not supported by this script)")
    api.token = str(result["token"])


def cleanup(
    api: GatekeeperApi,
    user_id: str | None,
    user_email: str,
    role_key: str | None,
) -> list[str]:
    failures: list[str] = []
    if not user_id and user_email:
        query = urllib.parse.urlencode({"q": user_email, "limit": 500, "offset": 0})
        status, result = api.request("GET", f"/api/admin/users?{query}")
        if status == 200 and isinstance(result, dict):
            matches = [item for item in result.get("users", []) if item.get("email") == user_email]
            if matches:
                user_id = str(matches[0].get("id", "")) or None
    if user_id:
        status, result = api.request("DELETE", f"/api/admin/users/{urllib.parse.quote(user_id, safe='')}")
        if status != 200:
            failures.append(f"could not delete smoke user (HTTP {status}): {result}")
        else:
            print("CLEANUP deleted smoke user")
    if role_key:
        status, result = api.request(
            "DELETE", f"/api/admin/rbac/roles/{urllib.parse.quote(role_key, safe='')}"
        )
        if status != 200:
            failures.append(f"could not delete smoke role {role_key} (HTTP {status}): {result}")
        else:
            print("CLEANUP deleted smoke role")
    return failures


def load_config() -> dict[str, str]:
    names = {
        "GATEKEEPER_SMOKE_TARGET": "local or staging",
        "GATEKEEPER_SMOKE_BASE_URL": "http://localhost:8080",
        "GATEKEEPER_SMOKE_ADMIN_EMAIL": "admin@example.net",
        "GATEKEEPER_SMOKE_ADMIN_PASSWORD": "<admin password; value is never printed>",
    }
    values = {name: os.environ.get(name, "").strip() for name in names}
    print("RBAC smoke-test preflight (secret values are never echoed):")
    for name, example in names.items():
        state = "SET" if values[name] else "NOT SET"
        print(f"  {name}: {state} (example: {example})")
    missing = [name for name, value in values.items() if not value]
    if missing:
        raise SmokeFailure("Required environment variables are missing: " + ", ".join(missing))
    target = values["GATEKEEPER_SMOKE_TARGET"].lower()
    if target not in {"local", "staging"}:
        raise SmokeFailure("This script only runs against local or staging targets; production is refused")
    parsed = urllib.parse.urlparse(values["GATEKEEPER_SMOKE_BASE_URL"])
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise SmokeFailure("GATEKEEPER_SMOKE_BASE_URL must be an http(s) URL")
    if parsed.scheme != "https" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise SmokeFailure("Use HTTPS when sending credentials to a non-local target")
    if "prod" in parsed.hostname.lower():
        raise SmokeFailure("This script refuses production hostnames")
    return values


def run() -> None:
    config = load_config()
    admin = GatekeeperApi(config["GATEKEEPER_SMOKE_BASE_URL"])
    unauthenticated = expect_status(admin, "GET", "/api/admin/rbac/permissions", 401)
    del unauthenticated

    login(admin, config["GATEKEEPER_SMOKE_ADMIN_EMAIL"], config["GATEKEEPER_SMOKE_ADMIN_PASSWORD"])
    permissions = expect_status(admin, "GET", "/api/admin/rbac/permissions", 200)
    codes = {item.get("code") for item in permissions} if isinstance(permissions, list) else set()
    if not {"projects.read", "projects.manage", "roles.manage"}.issubset(codes):
        raise SmokeFailure("Permission catalog is missing expected project or role permissions")
    print("PASS permission catalog includes project read/manage and role management")

    run_id = secrets.token_hex(6)
    role_key = f"rbac_smoke_{run_id}"
    user_email = f"rbac-smoke-{run_id}@example.com"
    user_password = f"RbacSmoke-{secrets.token_urlsafe(18)}"
    user_id: str | None = None
    created_role: str | None = None
    failure: BaseException | None = None

    try:
        expect_status(
            admin,
            "POST",
            "/api/admin/rbac/roles",
            400,
            {
                "key": f"rbac_bad_{run_id}",
                "displayName": "Invalid smoke role",
                "permissions": ["rbac_smoke.not_a_permission"],
            },
        )

        role = expect_status(
            admin,
            "POST",
            "/api/admin/rbac/roles",
            201,
            {
                "key": role_key,
                "displayName": "RBAC Smoke Test",
                "description": "Temporary role created by the RBAC smoke test.",
                "permissions": ["projects.read"],
            },
        )
        created_role = role_key
        if not isinstance(role, dict) or role.get("key") != role_key:
            raise SmokeFailure("Role creation returned an unexpected role")

        user = expect_status(
            admin,
            "POST",
            "/api/admin/users",
            201,
            {"email": user_email, "password": user_password, "role": role_key},
        )
        if not isinstance(user, dict) or not user.get("id"):
            raise SmokeFailure("User creation did not return its ID; manual cleanup may be required")
        user_id = str(user["id"])

        limited_user = GatekeeperApi(config["GATEKEEPER_SMOKE_BASE_URL"])
        login(limited_user, user_email, user_password)
        expect_status(limited_user, "GET", "/api/admin/projects", 200)
        expect_status(limited_user, "POST", "/api/admin/projects", 403, {})
        expect_status(limited_user, "GET", "/api/admin/rbac/roles", 403)
        expect_status(
            admin,
            "DELETE",
            f"/api/admin/rbac/roles/{urllib.parse.quote(role_key, safe='')}",
            409,
        )

        expect_status(
            admin,
            "PUT",
            f"/api/admin/rbac/roles/{urllib.parse.quote(role_key, safe='')}",
            200,
            {
                "displayName": "RBAC Smoke Test",
                "description": "Inactive test role.",
                "active": False,
                "permissions": ["projects.read"],
            },
        )
        expect_status(limited_user, "GET", "/api/admin/projects", 403)

        expect_status(
            admin,
            "PUT",
            f"/api/admin/rbac/roles/{urllib.parse.quote(role_key, safe='')}",
            200,
            {
                "displayName": "RBAC Smoke Test",
                "description": "Permission changes are checked live.",
                "active": True,
                "permissions": [],
            },
        )
        expect_status(limited_user, "GET", "/api/admin/projects", 403)
    except BaseException as error:
        failure = error
    finally:
        cleanup_failures = cleanup(admin, user_id, user_email, created_role)
        if cleanup_failures:
            cleanup_message = "; ".join(cleanup_failures)
            print(f"Manual cleanup identifiers: user={user_email}, role={created_role or role_key}", file=sys.stderr)
            if failure is None:
                failure = SmokeFailure(cleanup_message)
            else:
                print(f"CLEANUP WARNING: {cleanup_message}", file=sys.stderr)

    if failure is not None:
        raise failure
    print("RBAC smoke test passed; temporary role and user were removed.")


def main() -> int:
    try:
        run()
    except SmokeFailure as error:
        print(f"RBAC smoke test failed: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("RBAC smoke test interrupted.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
