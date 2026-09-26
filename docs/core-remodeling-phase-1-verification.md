# Core Remodeling Phase 1 Verification

Phase 1 source changes are present in the repository. Checks that require a database, Docker, or production-shaped data must be completed in the rollout environment before declaring the operational exit criteria complete.

## Completed locally

- [x] `rtk ./gradlew test` passes.
- [x] Applied V31 and V32 to the local disposable database; Flyway reports schema version 32.
- [x] Created project `phase1-check-1790411435` (`edcef574-53a9-4b45-ac47-7ce1c38d3045`) through authenticated `POST /api/admin/projects` with `containerName` omitted. API returned `201` and `containerName: null`.
- [x] Created configuration `7781db93-6730-4063-82ad-e0095a834709` through the new project-scoped endpoint; response carried the same project ID and `configured` status.
- [x] Created and updated project `phase1-legacy-1790411585` using a legacy payload containing `containerName: "sdasdfasd"` (an existing local container); create returned `201`, update returned `200`, and the container name remained intact. Extra legacy `clientName`/`clientEmail` fields were accepted.
- [x] Redeploy from the configuration-only row initially exposed a gap: the route searched `deployment_jobs`, where no row exists before the first deploy. Fixed it to build a new deployment request from `deployment_configurations`, preferring `project_id` and using slug only for null IDs.
- [x] Redeploy returned `202`; the worker claimed deployment `b42aaf15-34d5-48e2-901c-1ce55c9e58c0`. Admin detail and list both returned `projectId: edcef574-53a9-4b45-ac47-7ce1c38d3045`. The test intentionally used a nonexistent repository and ended in `failed` during clone; no image/container was built.
- [x] Post-backfill report: `deployment_configurations` 2 rows, 2 uniquely resolved; `deployment_executions` 1 row, 1 uniquely resolved; `deployment_jobs` 1 row, 1 uniquely resolved. All three tables had zero null slugs, missing slugs, archived matches, or ambiguous matches. Apply mode updated zero rows because dual-write had already populated the IDs.
- [x] `rtk git diff --check` passes.

## Pending operational verification

- [ ] Repeat these live checks against the rollout database before production rollout; this verification used only the local disposable database.
- [ ] Run a real build/deploy with a valid repository and reachable Docker daemon; this check verified worker claim/ownership, while the intentionally invalid test repository failed during clone.

## Unresolved ownership rows

The local disposable database report had zero unresolved ownership rows. No sanitized production database was available, so production unresolved counts remain unknown. Record every unresolved production row from its report here before rollout; do not infer ownership from container, image, or customer fields.

| Table | Row ID | Category | `project_slug` | Operator resolution / Phase 2 follow-up |
|---|---|---|---|---|
| _No unresolved rows in local development report_ | | | | |
