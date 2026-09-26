# Core remodeling Phase 3 verification

Date: 2026-09-26

## Runtime record prerequisite

The active runtime target is persisted on the canonical `deployments` row: runtime container name, assigned host port, and the container-port-to-host-port mapping. `DeploymentApplicationService.activeDeploymentRuntime(projectId, environment)` reads the active deployment and its execution's container port. Phase 3 does not depend on a separate `runtime_instances` table.

This confirms that the resolver has a persisted runtime target to consume. It does not establish full Phase 2 runtime authority: `docs/core-remodeling-phase-2-verification.md` records that startup recovery is not implemented and the worker still selects the initial replacement target from `projects.container_name`.

## Development environment preflight

Read-only checks found:

- The development database has zero active deployments, zero registered sites, and five non-archived active projects.
- Docker has only the development PostgreSQL, Redis, and pgAdmin containers; there is no application runtime to preserve during a cutover.
- Gatekeeperd is not listening on port 8080.
- Nginx is installed on the host.

## Requested exit criteria

- **Image-change cutover preserving project/domain/site identity: not run.** There is no active deployment, managed site, or application runtime in the development environment to replace.
- **Nginx validation failure preserving the old runtime and site config: end-to-end check not run.** The unit regression test `failed global validation does not reload candidate site or replace current runtime route` verifies that validation failure skips reload and preserves the current site file and symlink. No old runtime exists here for a worker-level assertion.
- **Non-payment block while a healthy deployment remains active: not run end-to-end.** The source audit in Chunk 6 confirmed gate decisions use project access status and do not read deployment or container state, but this database has no healthy active deployment against which to exercise the requested scenario.

Do not mark the Phase 3 live exit criteria complete from these checks. Repeat them with a disposable project that has a known active Docker runtime and managed site, and record the project ID, deployment IDs, domain, runtime names, and before/after nginx targets.
