# Gatekeeperd Stabilization Audit

**Inspection only.** This audit inspected `/home/mike/IdeaProjects/gatekeeperd` and its companion frontend. The supplied workspace instructions were for the separate GateWay checkout; GateWay has no Docker deployment lifecycle and explicitly excludes container management. No files were changed and no tests, builds, or production operations were run during the audit. Failure scenarios below were identified from code paths and were not exercised.

## 1. Architecture and deployment sequence

| Component | Responsibility |
|---|---|
| Frontend | Starts deployments and displays deployment history, status, retry, cancel, and rollback actions. |
| Ktor admin API | JWT-protected project/service setup and deployment routes; persists queued work and returns `202 Accepted`. |
| Deployment worker | Claims work, obtains source and credentials, builds/pushes an image when needed, pulls it, starts a candidate, checks readiness, and attempts cutover. |
| Docker service | Uses the configured Docker socket for image, container, network, and volume operations. |
| Persistence | Stores job/execution progress and canonical deployment/runtime records in PostgreSQL. |
| Nginx service | For an enabled managed site, stages config, validates it, activates/reloads it, and can restore prior config. |
| Reconciliation endpoint | Compares persisted active deployments with Docker and Nginx state; it is read-only. |

The project setup API queues a deployment. The worker claims it, checks out a Git ref and builds/pushes an image or uses a configured image, pulls the image, starts a candidate on dynamically assigned loopback ports, and runs a readiness probe. It persists the candidate runtime and readiness state. When an enabled managed Nginx site exists, it switches the upstream and validates/reloads Nginx. It then retires the previous production container and marks the candidate active. Without an enabled site, activation is independent of Nginx.

Primary references: `src/main/kotlin/com/gatekeeper/admin/ProjectSetupAdminRoutes.kt`, `src/main/kotlin/com/gatekeeper/deployment/DeploymentWorker.kt`, `src/main/kotlin/com/gatekeeper/db/repositories/DeploymentJobRepository.kt`, `src/main/kotlin/com/gatekeeper/docker/DockerService.kt`, and `src/main/kotlin/com/gatekeeper/nginx/NginxService.kt`.

## 2. Verified working functionality

- Candidate-first deployment and readiness checks are implemented. The worker supports TCP, Docker health, and process probes.
- Nginx config activation stages and tests a candidate config before activation; failed activation attempts restore the previous site config. `NginxServiceTest` includes failed-validation coverage.
- Job/execution progress and runtime state are persisted. Stale jobs have a startup recovery path, and ready candidates have a separate retry path.
- The authenticated deployment reconciliation endpoint compares active records with Docker containers, ports, and managed Nginx state. It reports findings but takes no corrective action.
- Focused tests cover rollback image references, published-port normalization, deployment upstream resolution, and Nginx behavior. CI is configured to run tests and build the fat JAR.

These facts do not establish end-to-end deployment reliability. No `DeploymentWorker` lifecycle test covers the full build-to-cutover flow. Tests were not run as part of this audit.

## 3. Critical bugs and security risks

### P1 — Candidate can be orphaned before runtime identity is persisted

The worker creates and starts a candidate before recording its name and ports in the deployment row. If the process stops between container start and `recordCandidateRuntime`, startup recovery requeues the deployment but retry cleanup only checks the persisted candidate name. The retry then tries to create the same deterministic deployment-based name and can fail on Docker's name collision, leaving manual cleanup necessary.

Evidence: candidate creation in `DeploymentWorker.kt` around lines 142–151; runtime persistence around lines 167–169; retry cleanup around lines 72–75.

### P1 — Previous-runtime recovery may restore the wrong image or incomplete ports

If canonical activation fails after the previous container is removed, recovery recreates it from `imageName:imageTag` and at most one port mapping. The deployment records an image digest and a full published-port map. A mutable tag can resolve to different code, and a multi-port service can return with only one port.

Evidence: `DeploymentWorker.kt` around lines 260–275.

### P1 — Revoked admin JWTs may be accepted during Redis outage

JWT validation checks revocation in Redis. If the check fails, `JwtConfig.isRevoked` returns `false`, so the otherwise valid token remains accepted until its 24-hour expiry.

Evidence: `src/main/kotlin/com/gatekeeper/plugins/Security.kt`.

### P2 — Some claimed jobs may remain running until process restart

Stale-job recovery runs once when the worker starts. If a job has been claimed and a later operation outside its job failure handler fails, such as distributed-lock acquisition, the outer loop logs and continues. There is no periodic recovery call for ordinary running jobs.

Evidence: `DeploymentWorker.start` and `processNext`; `DeploymentJobRepository.recoverStale`.

### P2 — Docker may leave a started container if post-start inspection fails

`DockerService.createContainer` creates and starts a container, then asserts a follow-up lookup is non-null. If that lookup fails, the method throws without local cleanup. The deployment worker has outer cleanup for a known candidate name, but direct container-creation callers do not get that guarantee from this method.

Evidence: `src/main/kotlin/com/gatekeeper/docker/DockerService.kt`, `createContainer`.

### Security and operations

- The application binds to `0.0.0.0`; Docker administration is under JWT authentication. The Docker socket grants powerful host-level capabilities, so compromise of an admin credential can expose host resources.
- `.env.example` contains placeholder values such as `DB_PASSWORD=changeme` and `JWT_SECRET=changeme-use-a-long-random-string`. `JWT_SECRET` must be set but is not strength-validated.
- Deployment secret values are encrypted with AES-GCM when `DEPLOYMENT_SECRETS_KEY` is configured; writes reject an unconfigured key. The key is loaded from environment or `.env`, and only its presence is logged.
- Registry login uses `docker login --password-stdin` but does not log out afterward. Whether credentials persist depends on the service account's Docker configuration; verify this in the deployment environment.
- Docker registry and volume-root allowlists are configurable but inactive by default when unset.

## 4. Deployment failure scenarios

These are code-inspection scenarios, not reproduced incidents:

1. Stop the worker after candidate start but before runtime persistence; restart and observe whether retry collides with the orphan's deterministic name.
2. Inject persistence failure after old-runtime removal and verify whether recovery restores the exact image digest and every published port.
3. Revoke an admin token, make Redis unavailable, and call a protected route with the token; current code predicts acceptance until expiry.
4. Inject a lock/repository failure after claim without restarting Gatekeeperd; check whether the job remains `running` past the stale threshold.
5. Make Docker create/start succeed and the follow-up inspection fail; check whether a container remains.
6. Inject Nginx validation, reload, and rollback failures; compare the active deployment record, route target, and running containers.

## 5. Blue-green assessment

The current implementation already uses a limited candidate-and-promote sequence: start a separate candidate, check it, switch Nginx when applicable, then retire the old runtime. There is no evidence of a requirement for a more elaborate blue-green platform. The documented behavior does promise readiness before replacement and retains rollback metadata; replacing it with an in-place restart would remove that protection and introduce avoidable downtime. Keep the existing strategy for stabilization without expanding orchestration.

## 6. Tests needed to establish reliability

- Worker lifecycle tests with controllable Docker/Nginx boundaries for success, build failure, readiness timeout, cancellation, cutover failure, and rollback.
- Crash-recovery tests at each persistence boundary, especially candidate-created/runtime-not-yet-recorded.
- Rollback tests asserting the same image digest and all port mappings are restored.
- Stuck-job tests for failures after claim and lock acquisition errors, including recovery without process restart.
- Redis-outage auth test for the chosen token-revocation behavior.
- Docker creation cleanup test for start-success/inspect-failure.
- One controlled staging integration test that deploys a known image behind managed Nginx, verifies the routed version, forces a failed candidate, and verifies the old version remains served.

Existing coverage is in `src/test/kotlin/com/gatekeeper/deployment`, `src/test/kotlin/com/gatekeeper/nginx`, and `src/test/kotlin/com/gatekeeper/docker`. No `DeploymentWorker` lifecycle test was found.

## 7. Explicitly out of scope for first stable release

- New orchestration services or a redesigned deployment architecture.
- Multi-host scheduling or another deployment platform.
- Replacing the existing candidate-and-promote flow absent a product requirement.
- Cloudflare enforcement or additional hosting/runtime management features.

## 8. Proposed small implementation tasks

1. **Make candidate creation recoverable.** Persist candidate identity before or atomically with start, or reliably discover the deterministic candidate on retry. Acceptance: an injected crash after Docker start causes no duplicate-name failure; retry reuses or removes the candidate and reaches a terminal/active state.
2. **Make old-runtime restoration exact.** Persist/reuse immutable image identity and all port mappings. Acceptance: activation failure restores the same image digest, network, environment, volumes, restart policy, and complete published-port map.
3. **Close the claimed-job recovery gap.** Add bounded recovery for stale ordinary running jobs or transition/requeue all post-claim failures. Acceptance: injected lock/repository failure does not leave a job permanently running without restart.
4. **Define Redis outage behavior for token revocation.** Acceptance: tests prove the selected behavior for revoked tokens during Redis outage.
5. **Make Docker creation cleanup-safe.** Acceptance: injected inspection failure leaves no new container.
6. **Add worker lifecycle regression coverage.** Acceptance: tests assert job state, runtime inventory, and route target after success and failure; CI passes without touching production resources.

This report records the inspection-only audit phase. Follow-up implementation work is tracked separately.
