# Core Remodeling Phase 2 Verification

Development database and local API verification run on 2026-09-26. The database is the disposable development database configured in `.env`.

## Phase 1/2 exit checks

- **No-container project creation:** authenticated `POST /api/admin/projects` returned `201 Created` with `containerName: null`. Project `phase2-exit-1790420180` has ID `80d7cf18-7779-4de4-b585-a7191263fa7b`.
- **Legacy container-first create/update:** used the existing running `pgadmin` container. The old create payload, including `clientName` and `clientEmail`, returned `201`. The response-shaped update returned `200` and project detail retained `containerName: "pgadmin"`. An update carrying those legacy extra keys initially returned `400`; project update decoding was changed to ignore unknown fields, matching the API's other JSON routes, and the same payload then returned `200` with the container name unchanged.
- **New deployment ownership path:** attached configuration `c253e647-a124-46d3-ad3e-4efca3e0fcf0` to the new project, redeployed it as deployment `028a4b92-610e-4be2-819e-dd70a7a01e7d`, and observed matching `projectId` in admin detail, admin list, and worker claim (`currentStep=cloning`).
- **Backfilled deployment ownership path:** the Phase 1 deployment/configuration `b42aaf15-34d5-48e2-901c-1ce55c9e58c0` retained project ID `edcef574-53a9-4b45-ac47-7ce1c38d3045`. Redeploy created `1c23fba2-91f2-4ce3-960f-096a6e558042`; admin detail, admin list, and worker claim (`currentStep=cloning`) all returned that same project ID.
- **Final read-only ownership report:** `deployment_configurations` 5/5 uniquely resolved; `deployment_executions` 3/3; `deployment_jobs` 3/3. Each table reported zero null slugs, missing slugs, archived-project matches, and ambiguous matches.

## Unresolved ownership rows

No unresolved rows were reported in the final development database report. No rows need manual repair or Phase 2 follow-up.

| Table | Row ID | Category | `project_slug` | Operator resolution / Phase 2 follow-up |
|---|---|---|---|---|
| _No unresolved rows_ | | | | |

## Verification limits

The redeploy checks used deliberately nonexistent repositories. They verified project ownership through configuration redeploy and worker claim, but ended during cloning. A valid repository deployment was not run, so image build/push, readiness, nginx cutover, and old-runtime retirement were not exercised in this check. No sanitized production database was available; production orphan counts remain unknown.

## Chunk 9 — restart, failure preservation, and rollback

Code review on 2026-09-26; live lifecycle acceptance checks remain incomplete.

### Development runtime check on 2026-09-26

- Gatekeeperd was initially stopped. Started it with `rtk ./gradlew run`, stopped it cleanly with Ctrl-C, then started it again. On both starts it connected to PostgreSQL, Redis, and Docker; Flyway validated all 8 configured migrations and reported schema version 37 up to date.
- After the second start, unauthenticated `GET /api/health` returned `200` with `{"status":"ok"}`. Authenticated `GET /api/admin/deployments/reconciliation` returned `200`, `status=observed`, `entries=[]`, `errors=[]`, and `actionsTaken=[]`.
- Authenticated `GET /api/admin/deployments?limit=100` returned three rows, all `failed`; none was `active`. Docker inventory contained only the development PostgreSQL, Redis, and pgAdmin containers. No active application runtime was available for the pointer-recovery assertion.
- `rtk ./gradlew test` passed (`BUILD SUCCESSFUL`).
- No failure deployment or rollback was queued: without a known active/superseded application deployment, these checks cannot establish that old traffic survives or that rollback restores a real prior runtime. Doing so against unrelated infrastructure containers would not be a valid test.

- **Restart recovery from the database active pointer: not verified and not yet satisfied.** `DeploymentApplicationService.activeRuntime(projectId, environment)` reads the canonical active deployment and its persisted runtime name. The worker uses that lookup during cutover. However, deployment startup/recovery does not restore or identify active runtimes from that pointer, and the initial replacement target is still selected from `ProjectRepository.findActiveById(...).containerName` in `DeploymentWorker`. That leaves the requested post-restart/runtime-authority behavior unproven and partially project-field dependent.
- **Failed candidate leaves prior deployment serving: not verified.** The code starts candidates without stopping the prior canonical runtime and removes an unhealthy candidate. No valid image deployment or deliberately failing readiness check was run against Docker/nginx. In addition, the selected coexistence scheme binds candidates to Docker-assigned loopback host ports, while managed nginx cutover is currently applied through `switchDeploymentUpstream`; end-to-end gateway behavior needs a live validation before this criterion can pass.
- **Rollback creates an auditable row: code path present, not exercised.** Rollback creates a new queued deployment with `rolled_back_to_deployment_id`, and activation records an audit event. The rollback artifact currently restores the prior image digest/commit, while the queued runtime configuration is copied from the target deployment snapshot used by the endpoint. The complete rollback deployment was not run, so image pull, readiness, gateway switch, and audit persistence are unverified.
- **Reconciliation: report-only implementation present, live report not exercised.** `GET /api/admin/deployments/reconciliation` reads canonical active rows and compares their persisted runtime identity/ports and managed nginx state. It reports drift and declares no actions taken. It does not search for or adopt containers by discovered identity.

Do not mark the Phase 2 restart/failure/rollback exit criteria complete based on source review alone. Repeat these checks with a controlled project that has a known active deployment and managed gateway, recording deployment IDs and before/after route/runtime state. Production checks require a sanitized data copy and an approved maintenance/test window.

## Port and gateway cost to carry into Phase 3

The selected design is dynamic host-port coexistence with gateway switch after readiness. This adds operational work beyond the current fixed-port assumptions:

- Persist and reconcile every Docker-assigned host-to-container port mapping, including multiple exposed ports; `runtime_ports_json` is the current additive representation.
- Define allocation ownership and cleanup behavior for candidate ports across worker crashes, retries, cancellation, and stale containers. Docker allocation avoids a separate reservation table while candidates exist, but persisted mappings and orphan cleanup still need verification.
- Make nginx resolve the active deployment's persisted host port and write that target into the site configuration. Current project/site upstream fields and config rendering remain container/explicit-port oriented; the resolver and compatibility rollout belong to Phase 3.
- Validate nginx configuration and reload it before retiring the old runtime; preserve the previous runtime and route if validation/reload fails, and reconcile the state after a process crash at each cutover boundary.
- Test loopback reachability from the nginx host, multi-port readiness selection, port reuse after cleanup, and rollback with the prior deployment's complete runtime configuration.

This is a meaningful Phase 3 infrastructure dependency. The implementation should not be presented as zero-downtime until the active-deployment gateway resolver, cutover recovery, and failure cases pass against Docker and nginx.
