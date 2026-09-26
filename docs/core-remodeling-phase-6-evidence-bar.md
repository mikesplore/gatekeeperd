# Core Remodeling — Phase 6 Evidence Bar

> **Superseded:** The owner later chose direct removal and frontend migration. See [Phase 6 Direct Retirement](core-remodeling-phase-6-retirement.md). The 30-day evidence window below was not run and is not a gate for that owner-directed change.

This document locks the evidence required before retiring project-level runtime compatibility fields or slug-based deployment ownership. It applies to [Phase 6 of the remodeling plan](core-remodeling-plan.md#phase-6--retire-compatibility-projections). It authorizes no field removal or destructive migration.

## Evidence window

Use **30 consecutive days** after the telemetry below is deployed to production and after every supported client has had the documented deprecation window to upgrade. The clock starts only when telemetry is live and its coverage has been checked against the deployed build. Any qualifying legacy use resets the 30-day clock.

The evidence report for a proposed retirement must show all of the following for the full window:

1. **Legacy container-first API writes: zero.** Count requests that send `containerName` to `POST /api/admin/projects` or `PUT /api/admin/projects/{slug}`, including supported clients that send an empty or unchanged value. Track the request route and client identity/version when available; do not log request bodies or secrets. A legacy call that cannot be attributed to a known supported client is still a hit.
2. **New slug-only deployment ownership: zero.** For each insert into `deployment_configurations`, `deployment_executions`, and `deployment_jobs`, require and measure a non-null `project_id`. Count rows created with null `project_id`, including rows that also have `project_slug`. At the end of the window, report existing unresolved/null-owner rows separately; no historical orphan is silently treated as evidence of safety.
3. **Legacy project-column reads: zero in normal application flows.** Measure reads of `projects.container_name` and project-level image fields (`deploy_image_name` and `deploy_image_tag`), plus any other field proposed for retirement. Instrument repository/service read paths or use equivalent production query telemetry with validated coverage. Exclude only explicitly approved, operator-invoked legacy import/backfill tooling; list that exception and its invocation audit separately. Any ordinary API, worker, webhook, dashboard, nginx, scheduler, or reconciliation read resets the window.
4. **Known-consumer migration: complete and attributable.** Every supported consumer below has an owner, deployed version/date, and evidence that it no longer sends or reads the retired representation. An unknown or unattributed client is not presumed absent.
5. **Database and recovery evidence: current.** Capture a read-only ownership report for all three deployment tables, verify there are no unresolved project owners, take/verify a backup, and rehearse the migration and recovery procedure against a recent sanitized copy before proposing the destructive migration.

The existing request tracing/logging is not sufficient by itself to prove that clients do not consume legacy response fields, and repository search cannot prove that unregistered clients are absent. Until field-level read/write telemetry with validated coverage is deployed, the 30-day clock has **not started**. The report must state telemetry gaps and the exact observation dates; it must not turn missing instrumentation into a zero count.

## Audit surface

Phase 0's checked-in source list is the **starting inventory**, not a complete proof of the live consumer population. Before the observation window starts, reconcile this list against production deployment/configuration records, API/access logs, frontend release records, and operator-owned automation inventories. Record each consumer's owner, version, and status in the evidence report.

| Consumer area | Required audit targets | Evidence to record |
|---|---|---|
| Backend project API and persistence | `ProjectAdminRoutes`, project DTOs, `ProjectRepository`, project table mappings, project create/update/archive/sync flows | Legacy request-field counters; repository/query read/write counters; confirm current and supported client versions |
| Deployment API, worker, and scheduler | `DeploymentAdminRoutes`, `ProjectSetupAdminRoutes`, `DeploymentJobRepository`, `DeploymentWorker`, `DeploymentApplicationService`, reconciliation and ownership-report commands, scheduled jobs | Per-table inserts with owner IDs; legacy project-column reads; all project ownership lookups use `project_id` except documented unresolved-row fallback |
| GitHub integration | GitHub webhook delivery and auto-deploy target selection, installation/PAT management, webhook retry/replay automation | Confirm webhook-triggered deployments carry `project_id`; inspect deployed webhook configuration and external delivery/replay clients |
| Nginx and site administration | `NginxAdminRoutes`, `NginxBackfillRunner`/CLI, site render/resolver, `NginxReconciliationService`, operations/status endpoints | Confirm runtime resolution comes from active deployment; identify any project-column reads. Keep an explicitly invoked legacy import utility only as a documented, audited exception if still needed |
| Admin frontend | Separately deployed `gatekeeperd-frontend`: project create/edit/detail, overview/setup wizard, deployments/settings/history, operations and nginx screens | Pin deployed frontend build; audit generated API payloads and displayed response fields; record its release and any cached/older supported release |
| MCP and automation | MCP server/repository and Phase 5 chunk 8 API automation tools, scripts, scheduled jobs, CLI clients, direct SQL/reporting consumers | Enumerate installed/deployed consumers from their actual environments; capture owner/version and representative payloads. Source review of this repository alone is insufficient |
| Other external clients | Admin scripts, integrations, monitoring/reporting, manually maintained API clients, and any direct database reader/writer | Reconcile API access logs/API keys or gateway identities with an owner; unknown identities remain unresolved until explained or expired from the full window |

This is the required audit surface. It is complete only after the production and operator inventories identify no additional active consumer. A checked-in source list, a successful frontend build, or zero local search matches does not prove completeness.

## Current readiness

The code and Phase 0 document identify several compatibility paths that remain intentionally present, including the legacy project API, project repository synchronization, nginx/backfill behavior, and the separate dashboard/MCP consumers. Phase 5 decisions require additive compatibility while clients roll independently. No production telemetry evidence or 30-day observation report is included here, and no assertion is made that external scripts or clients have been exhaustively discovered. Therefore the evidence bar is defined, but **not yet met**; Phase 6 must not remove fields or apply a destructive migration on this evidence alone.

## Evidence report template

Before any removal proposal, append or link a dated report with:

- observation start/end timestamps and confirmation that the full 30-day window had no telemetry gaps;
- the deployed backend, frontend, MCP, and automation versions plus named owners;
- legacy API request count by route/client/version, with unknown-client count;
- null-`project_id` insert count for each deployment table and a current ownership/orphan report;
- legacy project-column read/write count by audited code path, with any approved import-tool exception and its invocation records;
- production client inventory reconciliation and unresolved identities (must be empty);
- backup identifier, sanitized-copy migration/recovery rehearsal result, and rollback runbook reference.
