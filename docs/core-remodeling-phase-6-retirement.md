# Core Remodeling — Phase 6 Direct Retirement

This document supersedes the observation-window proposal in [the Phase 6 evidence bar](core-remodeling-phase-6-evidence-bar.md). The owner chose to remove legacy compatibility immediately and update the admin frontend to the canonical project setup and deployment APIs. No telemetry window is claimed or implied.

## Retired project/runtime compatibility

- Project creation now uses `POST /api/admin/project-setup/projects`; `POST /api/admin/projects` and the container-first project wizard context were removed.
- Project DTOs and persistence no longer expose or read `projects.container_name`, `github_repository`, `github_ref`, `deploy_image_name`, `deploy_image_tag`, or `auto_deploy`.
- GitHub auto-deploy configuration lives on `deployment_configurations` and webhook jobs resolve by required `project_id`.
- Deployment configuration, execution, and queue records require `project_id`; `project_slug` was removed from those tables. Slugs remain business/public route identifiers for payments, gate checks, project URLs, and nginx filenames.
- Sites no longer persist `upstream_container_name`. Docker-discovery runtime host/port and health are derived from the active deployment record. Explicit host/port sites remain supported.
- Container names remain only in the canonical deployment runtime record, which identifies the actual Docker instance used for health checks, gateway resolution, cutover, and rollback. Configured fixed names and image/host-port container guessing were removed.

## Retired admin API compatibility

- Removed container-first project creation and the project Docker-container picker endpoint.
- Removed the project-level GitHub deployment-source patch endpoint and its project response fields.
- Removed global job-table deployment list/create/detail/audit/cancel/retry/rollback routes and configuration-ID update/redeploy routes.
- Project setup owns configuration and deploy operations. Canonical deployment history is available globally at `GET /api/admin/deployment-history` and per project at `GET /api/admin/projects/{slug}/deployments/history`; redeploy and rollback are project-scoped and create canonical deployment records.
- Existing dashboard/site edits cannot write a Docker container name. Docker upstream resolution uses the active deployment pointer; explicit host/port mode remains available.

## Migration behavior

`V44__retire_project_runtime_compatibility_columns.sql` performs the destructive schema retirement after preflight checks. It blocks if a legacy project container does not exactly match the active production deployment runtime, if project-level deployment settings have no configuration to receive them, or if any deployment configuration/execution/job lacks `project_id`. After the checks, all three deployment tables enforce non-null ownership, project runtime/source columns are dropped, the sites cache and configured container-name snapshots are dropped, and deployment slug columns are dropped.

The migration intentionally does not infer identity from image, container inventory, customer, or other runtime observations. A blocked migration requires operator repair/import before retrying.

## Remaining internal queue structure

The worker still uses `deployment_jobs` as its private queue and mutable progress snapshot. It is no longer exposed as a supported deployment API or used to infer ownership; each queue row has a required project ID, while `deployments` remains canonical history and active state. Removing the internal queue table requires a separate worker/execution-store redesign and is not represented as completed here.

## Rollout consequence

This is a breaking API and database change. Older admin frontends, scripts, direct SQL readers, or MCP clients that use removed project fields/routes must be upgraded before the backend migration is deployed. This repository audit and frontend source update do not establish that external consumers have been upgraded. Apply V44 only after its preflight passes on the target database and after a database backup is available.
