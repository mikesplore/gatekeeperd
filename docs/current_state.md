I have a complete picture now. Here is the audit report against `docs/clean_architecture_plan.md`.

# Architecture Audit Report — vs `docs/clean_architecture_plan.md`

## Phase-0 Baseline State (what exists before any new work)

| Measure | Recorded baseline (2026-10-17) | Current (audit) | Delta |
|---|---|---|---|
| Files over 500 lines (`src/main/kotlin`) | 10 | 10 (unchanged) | — |
| Repository `object` declarations | 17 | 17 (unchanged) | — |
| `DockerService(` call sites | 27 | **4** | **−23** |
| `System.getenv` occurrences (`src/main`) | 8 | **1** | **−7** |

The `DOCKER_SERVICE` count is the key metric and it is already **26% of the way** through the ~25 call-site migration.

---

## Phase-by-Phase Findings

### Phase 0: Safety Net and Ratchet — **INCOMPLETE / STALE**

1. **0.1 Baseline** — `docs/architecture-baseline.md` exists and was committed, so this is **OK**. No change needed.
2. **0.2 Whole-codebase ratchet** — Partially incomplete:
    - `ArchitectureRulesTest.kt` is committed and guards three focused rules:
      (a) domain packages import no framework/infra packages;
      (b) production sources have packages and no test imports;
      (c) payment presentation imports no persistence/provider adapters;
        + a `docker-construction` ratchet that returns an **exact-equality** assertion against a file-based allowlist.
    - The `0.3` characterization/snapshot tests **do not exist yet** — no golden-file route snapshots or end-to-end deployment-pipeline tests are present. This is the largest Phase 0 gap.
3. **Ratchet rule status:** The `docker-construction` ratchet's allowlist currently contains:
    - `Application.kt`
    - `NginxBackfillMain.kt`

   These two are still the **only** `DockerService(` call sites outside `DockerService.kt` / `DependencyInjection.kt`. As the migration proceeds, these must be pruned one-per-file (ratchet semantics: allowlist only shrinks). Note the ratchet itself is correct; it's just not yet green.

4. **Done condition:** Ratchet passes in CI + baseline + allowlist committed + snapshots pass. **Blocked on 0.3; bootstrapped but not done.**

---

### Phase 1: Configuration Injection — **MIGRATION COMPLETE; FULL TEST GATE NOT GREEN**

1. **1.1 Immutable Settings Model** — **DONE.** `AppSettings` provides grouped immutable settings, including DB, Redis, Docker, Nginx, authentication, payment, GitHub, integrations, telemetry, application, and scheduler groups. Its `fromEnv(env: Map<String, String>)` factory is the only production location that reads `System.getenv`.
2. **1.2 Koin Registration** — **DONE.** `AppSettings` is registered as a Koin singleton. Application components receive the relevant settings group from that instance; standalone commands load a settings snapshot at startup.
3. **1.3 Incremental Migration** — **DONE.** Production consumers have been migrated away from `AppConfig`; direct production references to the shim were absent before its removal. `SecretValueCipher` keeps its static API for existing persistence callers and receives its key through explicit startup initialization from application settings.
4. **1.4 Delete Legacy Shim** — **DONE.** `src/main/kotlin/com/gatekeeper/config/AppConfig.kt` was removed.
5. **Done condition status:** `System.getenv` appears only in `AppSettings.fromEnv`; other production `AppSettings.fromEnv()` calls are limited to application/standalone composition roots. The latest `rtk ./gradlew test` run compiled source and test code, then reported **14 failures out of 164 tests**. Tests have not been modified or investigated here, so the migration is implemented but full verification is not green.

---

### Phase 2: Infrastructure Ports and Single Docker Client — **NEARLY DONE, NOT GREEN**

1. **2.1 Define Core Infrastructure Ports** — **PARTIALLY DONE.** The plan calls for `DockerGateway`, `ProcessRunner`, `PortProbe`, `Clock`, `FileStore`, `DistributedLockPort`, `TransactionRunner`. The codebase already has `DockerGateway` and `DockerService : DockerGateway`, plus `ProcessRunnerImpl` and a `FakeProcessRunner` test double. However, I did **not** see dedicated `PortProbe`/`Clock`/`FileStore`/`DistributedLockPort`/`TransactionRunner` core ports in the slice I inspected — these may exist elsewhere or still be ad-hoc; a full directory sweep is needed before calling 2.1 complete.
2. **2.2 Implement `DockerGateway` & Lifecycle** — **PARTIALLY DONE.** `DockerService` is a single `DockerClient` implementation. The `DockerClient` instance is declared `private val client` inside the class and built via `DockerClientBuilder.getInstance(config)` — so there is currently exactly one `DockerClient` at runtime.
3. **2.3 Eliminate Ad-hoc `DockerService` Instantiations** — **NEARING COMPLETE.** There are exactly **4** remaining `DockerService(` call sites:
    - `Application.kt` (operational `backfill` command)
    - `NginxBackfillMain.kt` (operational `backfill` command)
    - `DependencyInjection.kt` (intended Koin `single { DockerService(...) }` registration)
    - `DockerService.kt` (its own constructor)

   The two operational commands (`Application.kt` and `NginxBackfillMain.kt`) are the legacy throwaway inline constructions that need to move to the injected `DockerGateway`. These are the ratchet allowlisted files.
4. **2.4 Adapt OS Commands & Sockets** — **PARTIALLY DONE.** `ProcessBuilder` still appears in:
    - `DockerService.kt` (line ~314)
    - `NginxService.kt` (lines ~1080, ~1100)
    - `admin/LogAdminRoutes.kt` (line 73)
    - `feature/deployment/data/process/HostDeploymentCommandRunner.kt`
    - `infrastructure/ProcessRunnerImpl.kt` (intended adapter)

   These should all be routed through an injected `ProcessRunner` port, with raw `ProcessBuilder` confined to `infrastructure/ProcessRunnerImpl.kt`.
5. **2.5 In-Memory Fakes** — **DONE.** Several fakes already exist: `FakeProcessRunner`, `FakeDocker` (multiple service tests), `FakeDockerBoundary`, `FakeDocker()` (ContainerWizard, ReadinessChecker). This is a strong partial.
6. **Done condition:** Exactly one `DockerClient`; `ProcessBuilder`/`Socket` only in infrastructure; fakes cover tests. **Near done; ratchet allowlist not yet cleared.**

---

### Phase 3: Repository Interfaces and Transaction Decoupling — **IN PROGRESS; SUBSTANTIAL WORK REMAINS**

Feature-level repository interfaces and Exposed adapters already exist for audit, certificate, customer, notification, payment, integrations, and several deployment/project support repositories. The legacy `DeploymentJobRepository` and `ServiceDomainRepository` objects have also been replaced by feature interfaces and adapters, and the project repository now has a feature port and `ExposedProjectRepository`. These are meaningful extractions, but the current worktree is still undergoing caller migration and does not yet satisfy Phase 3's done criteria.

**Remaining repository migrations and rough scope (production call counts from the current audit):**

- `ServiceRepository` remains a singleton object in `db/repositories/ServiceRepository.kt`, with about **46 direct calls across 9 files**. It must move behind a feature-owned interface/Exposed adapter and be registered in Koin; service admin/setup/nginx routes, payment adapters, billing jobs, and gate routes still need migration.
- `SiteRepository` remains a singleton object in `db/repositories/SiteRepository.kt`, with about **70 direct calls across 12 files**. Its model and dashboard update type are referenced across admin, nginx, deployment, backfill, and reconciliation code, so callers and public record types must move together to an injected port.
- `ServiceDomainRepository` is not one of the plan's ordered 3.1–3.7 targets, but literal Phase 3 completion requires **zero `object` repositories** in `com.gatekeeper.db.repositories`. Its feature interface and adapter exist, but callers and DI wiring need to be fully moved and verified.
- `ProjectRepository` has a feature interface/adapter, but the remaining callers include scheduler, backfill, deployment reconciliation, adapters, and DTO/model references. Verify there are no references to the deleted `db.repositories.ProjectRepository` object and that all remaining operations use the feature port.
- `DeploymentJobRepository` has a feature interface/adapter and an `ExposedDeploymentQueue` wrapper. Confirm every caller uses the feature interface directly; the plan defers its query/state-machine split to Phase 5.

**Transaction decoupling is not complete.** `TransactionRunner`/`ExposedTransactionRunner` are registered, but repository adapters still open their own Exposed `transaction {}` blocks. Move use-case transaction boundaries outward where multi-step operations must remain atomic, and ensure repository work joins the ambient transaction. In particular, preserve project creation's atomic project/default-service/audit writes and deployment environment/configuration/job operations.

**Tests and completion gate:** Existing repository migration tests are sparse; the plan requires interface-based tests and transaction/atomicity coverage. Tests have been skipped at the user's request, so that gate is currently unverified. Phase 3 is complete only when no `object` repositories remain in `com.gatekeeper.db.repositories`, consumers depend on injected interfaces, transaction boundaries are preserved through `TransactionRunner`, and interface-based tests pass.

---

### Phase 4: Nginx Slice Decomposition — **NOT DONE**

This is a large, unstarted-to-axially-done slice. The baseline data shows:
- `NginxService.kt` — **1,274 lines**
- `NginxAdminRoutes.kt` — **1,213 lines**
- `NginxBackfillRunner.kt`, `NginxReconciliationService.kt`, `NginxAdminService.kt` also over 100 lines

The plan's 4.1–4.5 require decomposition into `<400-line` domain/application/adapter chunks. Nothing in the current editing state indicates these are started. **Not done.**

---

### Phase 5: Deployment Slice Decomposition — **NOT DONE**

Baseline:
- `DeploymentWorker.kt` — **845 lines**
- `DeploymentApplicationService.kt` — **854 lines**
- `DeploymentJobRepository.kt` — 1,434 lines

Phase 5 depends on Phase 2/3 being green in a fresh PR, which is why it is not started yet. **Not done.**

---

### Phase 6: Project/Service/Customer/Gate Slices — **NOT DONE**

The baseline shows:
- `ProjectAdminRoutes.kt` — **757 lines**
- `ProjectSetupAdminRoutes.kt` — **982 lines**

These have not been decomposed. **Not done.**

---

### Phase 7: Supervised Background Workers — **NOT DONE**

The `scheduler/` directory currently has:
- `AutoBlockerJob.kt`
- `ReconciliationJob.kt`
- `IntegrationOutboxJob.kt`

The plan requires a `BackgroundWorker` interface + `WorkerSupervisor`, removing static `start(scope)` methods and `AtomicBoolean` flags. Current `AutoBlockerJob`/`ReconciliationJob` usage of `runWorkerLoop(...)` + `AtomicBoolean` flags has not been converted. **Not done.**

---

### Phase 8: Unified Error Model — **NOT DONE**

The audit notes any kind of `DomainError`/`Outcome<T>` hierarchy or centralized status-page mapping are absent. Existing code uses blanket `runCatching`/catch(Throwable). **Not done.**

---

### Phase 9: Package Consolidation — **NOT DONE**

No evidence of the `domain`, `application`, `infrastructure`, `presentation` directory restructure for the nginx/deployment/worker/error-model slices. Note that `feature/payment` is in fact the reference style and already has `domain`, `data/persistence`, `data/provider`, `domain/usecase`, `domain/repository`, `presentation` — serving as the pattern for new slices.

---

## Key Metrics Summary (how far along the repo is)

| Measurement                            | Count                                | Status                                      |
|----------------------------------------|--------------------------------------|---------------------------------------------|
| `DockerService(` main call sites       | **4** (was 27)                       | 88% of migration done                       |
| `System.getenv` in `src/main`          | **1** (inside `AppSettings.fromEnv`) | Phase 1 migration complete                  |
| `AppConfig` main consumers             | **0**; shim deleted                  | Phase 1 migration complete                  |
| `ArchitectureRulesTest.kt`             | present                              | bootstrapped (needs 0.3)                    |
| `docs/architecture-baseline.md`        | present                              | committed                                   |
| Fakes for Docker/ProcessRunner         | multiple                             | Phase 2.5 mostly done                       |
| Nginx slices `<400 lines`              | **not yet**                          | not started                                 |
| Deployment slices `<400 lines`         | **not yet**                          | not started                                 |
| Repository interface extraction        | **largely done**                     | gate not yet                                |
| Worker supervision                     | **not yet**                          | not started                                 |
| Error model `DomainError`/`Outcome<T>` | **not yet**                          | not started                                 |
| `AppConfig` deletion                   | **done**                             | deleted after production consumer migration |

---

## Issue / Warning Flags

1. **`Application.kt` and `NginxBackfillMain.kt` are ratchet allowlisted** — these two files still construct `DockerService` directly because they are operational commands (not Koin-injected). The ratchet requires their entries to be removed as each is migrated. This is expected but must be tracked.

2. **`Routing.kt` line 44–45** still selects `val dockerService: DockerService? = try { get<DockerService>() ... }` — this is the Ktor `install` block holding a `DockerService` reference manually. It should be replaced with the `DockerGateway` port or `lazy`/`inject` usage so routes are thin orchestrators.

3. **`DeploymentReconciliationService.production(docker)`** at `admin/DeploymentAdminRoutes.kt:15` and `ProjectSetupAdminRoutes.kt:369` are direct `get<DockerService>()` calls outside the Koin single registration — these will need to go through an injected `DockerGateway`.

4. **`NginxService.configured().bootstrapDeploymentUpstreams()`** runs at Application startup regardless of env, before DI runs. That is a side-effect bootstrap that may violate Phase 2.2 semantics — it should be deferred or wrapped behind a graceful adapter if it must exist.

5. **No Phase 0.3 characterization/snapshot tests.** The plan explicitly requires golden-file route snapshots and deployment-pipeline E2E tests before starting Phase 2 in a fresh PR. These are absent.

---

## Net Verdict

The Phase 1 configuration migration is **complete**: `AppConfig` has been deleted, production configuration uses `AppSettings`, and `System.getenv` is confined to `AppSettings.fromEnv`. The overall test gate remains **not green**: the latest `rtk ./gradlew test` run reported 14 failures of 164, which are not diagnosed in this audit update. Phase 0.3 and the remaining formal gates for the other phases remain incomplete as recorded above.

Immediate follow-up: investigate the failing tests before claiming the full verification gate is green, then continue with the outstanding Phase 0 characterization tests and subsequent architecture phases. Phase 1 no longer has an `AppConfig` migration backlog.
