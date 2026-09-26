# Core Remodeling — Phase 2 Decisions

These decisions resolve the Phase 2 design questions called out in the [core remodeling plan](core-remodeling-plan.md). They are the implementation target for the Phase 2 chunks; the rollout remains additive and keeps the legacy deployment tables available during transition.

## Port coexistence

Run the candidate and active runtime simultaneously by assigning the candidate a free, dynamically selected host port. Persist the actual runtime port with the canonical deployment. Keep the configured port as desired configuration, not as the candidate's mandatory host binding. After readiness succeeds, update the project's gateway upstream to the candidate runtime, validate/reload nginx, and only then retire the former runtime. If gateway validation or reload fails, retain the former upstream and runtime.

The worker starts a candidate on Docker-assigned loopback host ports and leaves the former container untouched through readiness. The candidate name and port mappings are persisted on the canonical deployment. After readiness passes, the worker validates/reloads a managed nginx site against the candidate, retires the previous canonical runtime, and then atomically marks the previous deployment superseded and the candidate active. If nginx validation/reload fails, the old runtime and route remain authoritative. If old-runtime retirement fails, it restores the old site config and retries cutover later; once activation succeeds, a Docker cleanup error is logged while the candidate remains active.

## Readiness contract

Use one explicitly configured supported probe: Docker health check, HTTP GET expecting 2xx, TCP connect, or process-running. When no probe is configured during compatibility rollout, use TCP when a candidate host port is available and otherwise use process-running. Defaults are a 60-second overall timeout, 2-second interval, and 1-second per-probe timeout. Probe selection and timing are captured in configuration and execution snapshots. Readiness success moves the worker into cutover; deployments remain `health-checking` until nginx switch, old-runtime retirement, and the canonical activation transaction complete.

## Canonical deployment record

Introduce a new `deployments` table as the canonical lifecycle record. It references the project, desired configuration, and execution snapshot; the execution remains the transitional snapshot/log compatibility record. `deployment_jobs` remains the worker queue compatibility representation until worker ownership is migrated. New state transitions are written through `DeploymentApplicationService`.

The record includes project/environment ownership, transition status and timestamps, trigger source, failure reason, and replacement/rollback links. Runtime-specific fields and the current deployment pointer are added in the later Phase 2 cutover chunks before this table becomes the active runtime authority.

## Environment key

Environment is a real deployment ownership dimension from the start. `production` is the default for existing callers, while the API and persisted configuration can carry another nonblank environment when needed. The database enforces at most one active canonical deployment per project/environment pair.
