# RBAC smoke test

This integration smoke test exercises the RBAC API against a running local or staging Gatekeeperd instance. It verifies that:

- Admin routes require authentication.
- The permission catalog is available and invalid permission grants are rejected.
- An administrator can create a limited role and assign it to a user.
- A custom-role user can use granted project-read access but cannot manage projects or view roles.
- A role assigned to a user cannot be deleted.
- Deactivating the role and changing its permissions take effect immediately for an already-issued JWT.

The script creates a uniquely named temporary role and user, then deletes both. The audit log entries are retained. It does not create or modify projects, deployments, payments, or customer data. If cleanup fails, the script reports the role and user details needed for manual cleanup.

## Run

Requires Python 3 and a reachable local or staging Gatekeeperd instance. The administrator account needs the built-in superuser role. A user with two-factor authentication enabled should not be used as the admin test account because this script does not complete an interactive 2FA challenge.

```bash
export GATEKEEPER_SMOKE_TARGET=local # or staging
export GATEKEEPER_SMOKE_BASE_URL=http://localhost:8080
export GATEKEEPER_SMOKE_ADMIN_EMAIL=admin@example.net
export GATEKEEPER_SMOKE_ADMIN_PASSWORD='...'

python3 scripts/rbac-smoke/rbac-smoke.py
```

The script refuses production targets, requires HTTPS for remote hosts, and never prints the administrator password or access tokens.
