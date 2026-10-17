# Gatekeeperd Clean Architecture Plan

Behavior-preserving refactor of the repository audit findings into a layered, injectable, testable codebase. Each chunk is one shippable PR. Do not start a chunk until the previous one is merged and green.

---

### Target Architecture

```
com.gatekeeper
├── app/             # Composition root: Koin modules, Application.kt, worker supervisor, lifecycle hooks
├── core/            # Shared kernel: Result/Outcome/errors, Clock, TransactionRunner, common IDs, Ports
├── config/          # AppSettings (immutable, injected), no System.getenv outside here
├── <feature>/       # customer, project, service, deployment, nginx, gate, payment, integrations, auth
│   ├── domain/         # Entities, value objects, state machines, repository & gateway interfaces (no Ktor, Exposed, Docker, java.io)
│   ├── application/    # Use cases & application services (orchestration, transaction demarcation)
│   ├── infrastructure/ # Exposed repos, Docker adapter, Nginx file/command adapters, HTTP clients
│   └── presentation/   # Ktor routes & DTO mapping only
```

#### Rules That Define Done:
1. **Dependencies point inward:** Presentation and infrastructure depend on application and domain, never the reverse.
2. **No Kotlin `object` holds state, I/O, or config:** `object` is allowed only for pure constants, value objects, and pure functions.
3. **Routes are thin orchestrators:** Routes parse HTTP requests, invoke exactly one application use case, and map domain results/errors to HTTP responses. No DB, Docker, filesystem, or HTTP client calls inside route lambdas.
4. **Every external effect sits behind an interface:** Docker, OS processes, raw sockets, filesystem, Redis, and HTTP integrations are behind injected domain/core ports.
5. **Single Docker client lifecycle:** Exactly one `DockerClient` instance per process, initialized with graceful fallback if the socket is absent, and closed cleanly on shutdown.
6. **Workers are supervised, restartable, and testable:** Background jobs are managed by a centralized supervisor using injected clocks/delays without real-time sleep loops or static `AtomicBoolean` flags.
7. **Feature consistency:** `feature/payment` is the reference style. All features must match this pattern.

---

### Ground Rules for Every Chunk

- **Behavior-preserving:** No schema changes, no API shape changes, no deployment logic changes unless the chunk explicitly specifies it.
- **Coexistence via shims:** Old and new coexist through thin shims (e.g. `AppConfig` delegating to `AppSettings`) until the final cleanup chunk, keeping PRs small and revertable.
- **Transaction atomicity:** When moving transaction blocks out of individual repository methods, `TransactionRunner` must wrap multi-step use cases so operations that previously executed atomically continue to do so.
- **Graceful degradation:** Infrastructure adapters (Docker, Nginx, Scribed) must preserve existing graceful fallback behavior in environments where sockets, paths, or companion services are unavailable.
- **Tests green before and after:** If a chunk touches untested behavior, add a characterization test first in the same PR as the first commit.
- **No concurrent conflicts:** Do not run this alongside Remodel Phase 6 (retiring compatibility projections). Finish one before starting the other.
- **New code compliance:** All new code (including external service integrations) must follow the target layout immediately.

---

### Phase-by-Phase Execution Plan

#### Phase 0: Safety Net and Ratchet
**Goal:** Make regressions loud, quantify technical debt, and prevent coupling from growing.
- **0.1 Record Baseline:** Record line counts of files over 500 lines, total `object` repositories, `DockerService(` call sites, and `System.getenv` occurrences. Commit as `docs/architecture-baseline.md`.
- **0.2 Whole-Codebase Architecture Ratchet:** Extend `ArchitectureRulesTest.kt` to the entire codebase using an allowlist ratchet (`docs/architecture-ratchet-allowlist.txt`). Rules enforced:
    - `domain` packages import no Ktor, Exposed, Docker, `java.io`, or `java.net`.
    - `presentation` packages import no database repositories, Docker services, or `ProcessBuilder`.
    - No `DockerService(` instantiations outside the approved infrastructure factory.
    - The test fails if a new unlisted violation appears or if a listed violation is resolved (forcing the allowlist to shrink with each PR).
- **0.3 Characterization Tests for Critical Flows:**
    - Create golden-file snapshot tests for admin route response shapes (projects, deployments, nginx, customer).
    - Add end-to-end characterization tests for deployment pipeline stages: candidate launch, port probe verification, cutover failure recovery, and rollback.
- **Done when:** Ratchet test runs in CI, baseline and allowlist are committed, and route/pipeline snapshots pass.

#### Phase 1: Configuration Injection
**Goal:** Eliminate global static configuration and direct environment reads.
- **1.1 Immutable Settings Model:** Create `AppSettings` composed of grouped, immutable data classes (`DbSettings`, `RedisSettings`, `DockerSettings`, `NginxSettings`, `PaymentSettings`, `GitHubSettings`, `AuthSettings`, `SchedulerSettings`). Factory `AppSettings.fromEnv(env: Map<String, String>)`.
- **1.2 Koin Registration & Backward-Compatible Shim:** Register `AppSettings` as a Koin singleton. Refactor `AppConfig` to act as a pure delegation shim querying the injected `AppSettings` instance during the transition.
- **1.3 Incremental Migration:** Migrate consumers module by module (starting from plugins and background jobs to repositories and routes) to constructor-injected settings. Shrink the ratchet allowlist with each module.
- **1.4 Delete Legacy Shim:** Delete `AppConfig` once all consumers use injected settings and its allowlist is empty.
- **Done when:** `System.getenv` appears exclusively in `AppSettings.fromEnv`, and all components receive configuration via constructor injection.

#### Phase 2: Infrastructure Ports and Single Docker Client
**Goal:** Resolve resource lifecycle issues, connection leaks, and create testable infrastructure seams.
- **2.1 Define Core Infrastructure Ports:** Define interfaces in `core` or owning features:
    - `DockerGateway` (container lifecycle, image inspection, preset credentials, port checks)
    - `ProcessRunner` (command execution for nginx, systemctl, journalctl, docker login)
    - `PortProbe` (TCP socket reachability checks)
    - `Clock` (time abstraction)
    - `FileStore` (filesystem operations for nginx site configs and certificates)
    - `DistributedLockPort` (lock acquisition and release abstraction)
    - `TransactionRunner` (database transaction boundary abstraction)
- **2.2 Implement `DockerGateway` & Lifecycle Management:** Adapt `DockerService` into `DockerGatewayImpl`. Register as a Koin singleton with graceful socket-check initialization and clean teardown in the application shutdown hook.
- **2.3 Eliminate Ad-hoc `DockerService` Instantiations:** Replace all ~25 `DockerService(AppConfig.dockerSocket)` call sites across routes, workers, and reconciliation services with the injected `DockerGateway`.
- **2.4 Adapt OS Commands & Sockets:** Move all `ProcessBuilder` calls behind `ProcessRunner` and raw `Socket` checks behind `PortProbe`.
- **2.5 In-Memory Fakes:** Provide in-memory test doubles (`FakeDockerGateway`, `FakeProcessRunner`, `FakePortProbe`, `FakeClock`) under `src/test`.
- **Done when:** Exactly one `DockerClient` instance exists at runtime, `ProcessBuilder` and `Socket` appear only in infrastructure adapters, and tests execute using fast in-memory fakes.

#### Phase 3: Repository Interfaces and Transaction Decoupling
**Goal:** Convert singleton `object` repositories into injected interface implementations and move transaction demarcation to application use cases.
- **Repository Migration Pattern:**
    1. Extract repository interface into `<feature>/domain/repository`.
    2. Implement interface as `Exposed<Name>Repository(private val db: Database)` in `<feature>/infrastructure/persistence`.
    3. Register repository implementation in Koin.
    4. Ensure repository methods execute within the ambient transaction provided by `TransactionRunner`.
    5. Update callers to receive the interface via constructor injection.
    6. Remove the legacy `object` repository and update the ratchet allowlist.
- **Execution Order (by Dependency Topology):**
    - **3.1 Isolated Leaf Repositories:** `AuditRepository`, `NotificationRepository`, `CertificateRepository`
    - **3.2 External Credential & Webhook Repositories:** `GitHubCredentialRepository`, `GitHubAppInstallationRepository`, `GitHubWebhookRepository`, `RegistryCredentialRepository`, `ProviderCredentialRepository`
    - **3.3 Customer & Project Adjustment Repositories:** `CustomerRepository`, `ProjectAdjustmentRepository`, `EnvironmentSetRepository`
    - **3.4 Project Core Repositories:** `ProjectRepository` (writes), `ProjectQueryRepository` (reads), `IntegrationOutboxRepository`
    - **3.5 Service & Site Repositories:** `ServiceRepository`, `SiteRepository`
    - **3.6 Deployment Support Repositories:** `DeploymentPortAllocationRepository`
    - **3.7 Deployment Core Repository:** `DeploymentJobRepository` (interface extraction in this phase; full query/state machine split in Phase 5)
- **Done when:** Zero `object` repositories remain in `com.gatekeeper.db.repositories`, and all repository operations are accessible via injected interfaces and covered by interface-based tests.

#### Phase 4: Nginx Slice Decomposition
**Goal:** Decompose the 1,500-line `NginxService` and 1,281-line `NginxAdminRoutes` into testable domain, application, and adapter layers.
- **4.1 Pure Domain & Rendering Components:** Extract `NginxConfigRenderer`, `NginxConfigParser`, and domain validation rules (`UpstreamValidation`, `DeploymentUpstreamResolver`) with comprehensive table-driven unit tests.
- **4.2 Infrastructure Adapters:** Extract `NginxSiteFileStore` (filesystem symlinks, sites-available/enabled), `NginxCommandRunner` (process execution via `ProcessRunner`), and `CertificateProvisioner`.
- **4.3 Application Use Cases:** Implement `ApplySiteConfigUseCase`, `ReconcileSitesUseCase`, `BackfillSitesUseCase`, and `ProvisionCertificateUseCase`. Preserve existing validation-reload-retirement ordering exactly.
- **4.4 Thin Presentation Routes:** Refactor `NginxAdminRoutes.kt` to parse HTTP requests, invoke use cases, and map responses. Move container inspection and file operations out of route lambdas.
- **4.5 Decommission Legacy Service:** Delete `NginxService` once all responsibilities are transitioned to use cases and adapters.
- **Done when:** No Nginx source file exceeds 400 lines, routes perform zero direct I/O, and existing integration tests pass unchanged.

#### Phase 5: Deployment Slice Decomposition
**Goal:** Formalize deployment state transitions, decompose worker orchestration, and eliminate mixed locking strategies.
- **5.1 Explicit State Machine:** Define `DeploymentStatus` domain sealed hierarchy with explicit, table-tested `canTransition(from, to)` validation. Move transition rules out of repository SQL queries into domain logic.
- **5.2 Repository Segregation:** Split `DeploymentJobRepository` into:
    - `DeploymentRepository` (CRUD and entity queries)
    - `DeploymentQueue` (job claiming, lease renewal, and stale job recovery)
    - `DeploymentConfigurationRepository` (runtime config and cutover file metadata)
- **5.3 Worker Pipeline Decomposition:** Split `DeploymentWorker` into a poller that coordinates discrete step collaborators:
    - `SourceFetcher` (Git clone and revision checkout)
    - `ImageBuilder` (Docker build execution)
    - `CandidateStarter` (container configuration and startup via `DockerGateway`)
    - `ReadinessChecker` (health checks and port probing via `PortProbe`)
    - `CutoverCoordinator` (Nginx upstream cutover)
    - `RollbackExecutor` (automated failure rollback and cleanup)
- **5.4 Unified Locking Strategy:** Replace the mix of Redis locks, DB flags, and in-memory booleans with a unified `DeploymentLock` port with single ownership per service/environment.
- **5.5 Application Use Cases:** Decompose `DeploymentApplicationService` into discrete use cases: `CreateDeployment`, `RedeployService`, `RollbackDeployment`, `CancelDeployment`.
- **5.6 Fast Unit Testing on Fakes:** Port deployment recovery and cutover tests to run on `FakeDockerGateway` and `FakeClock`, removing dependence on live Docker daemons for workflow tests.
- **Done when:** Deployment components are organized into discrete single-responsibility classes under 400 lines each, state transitions are fully unit tested, and cutover/rollback workflows run against fakes.

#### Phase 6: Project, Service, Customer, and Gate Slices
**Goal:** Remove business logic, external API calls, and container provisioning from remaining admin and gate routes.
- **6.1 Customer Module:** Formalize `CustomerApplicationService` into focused use cases (`CreateCustomer`, `UpdateCustomer`, `GetCustomerSummary`) and thin `CustomerRoutes.kt`.
- **6.2 Scribed Integration Port:** Define `ScribedPort` interface in `integrations/domain`. Adapt `ScribedClient` and `ScribedIntegrationClient` behind it. Remove direct HTTP client invocations from project and service routes.
- **6.3 Project Module:** Extract use cases for `CreateProject`, `UpdateProject`, `ArchiveProject`, `ChangeProjectStatus`, and `GetProjectFinancials`. Move payment balance calculation to application query services. Split `ProjectAdminRoutes.kt` (~993 lines) by sub-resource.
- **6.4 Service and Setup Module:** Move container wizard and container provisioning logic out of routes into `ProvisionServiceRuntimeUseCase` behind `DockerGateway`. Split `ProjectSetupAdminRoutes.kt` (~880 lines).
- **6.5 Gate Module:** Retain `GateService` as an application use case, move paywall HTML generation to presentation templates, and inject gate access lookups through `ProjectQueryRepository`.
- **Done when:** No admin route file exceeds 300 lines, and no route imports repository implementations, Docker services, or external HTTP clients.

#### Phase 7: Supervised Background Workers and Application Lifecycle
**Goal:** Provide structured lifecycle management, supervision, error recovery, and testability for all background workers.
- **7.1 Worker Abstraction & Supervisor:** Define `BackgroundWorker` interface (`name`, `suspend fun tick()`) and `WorkerSupervisor`. Supervisor coordinates worker lifecycles, runs scheduled intervals using injected `Clock`/delays, catches failures per tick with exponential backoff, and tracks health status.
- **7.2 Worker Migration:** Convert `AutoBlockerJob`, `ReconciliationJob`, `IntegrationOutboxJob`, and the `DeploymentWorker` poller to `BackgroundWorker` implementations. Remove static `start(scope)` methods and atomic flags.
- **7.3 Graceful Shutdown:** Integrate `WorkerSupervisor` with Ktor's `ApplicationStopping` hook to cleanly cancel worker jobs, drain in-flight ticks, and close database, Redis, and Docker resources in correct topological order.
- **7.4 Operations Health Endpoint:** Expose worker status, last tick time, and error metrics through the `/api/admin/operations` endpoint.
- **Done when:** Worker crashes automatically restart with backoff, background jobs are verifiable with simulated clocks, and shutdown drains in-flight work cleanly.

#### Phase 8: Unified Error Model and Observability
**Goal:** Replace ad-hoc exception swallowing with an explicit outcome domain model and consistent structured logging.
- **8.1 Domain Error Hierarchy:** Define `DomainError` sealed hierarchy and `Outcome<T>` (or `Result<T>`) in `core`. Use cases return explicit outcomes rather than throwing exceptions for predictable business failures.
- **8.2 Centralized StatusPages Mapping:** Map `DomainError` variants to standard API error responses (`respondError`) in Ktor `StatusPages`, preserving exact existing HTTP status codes and JSON response bodies.
- **8.3 Exception Auditing:** Audit and eliminate blanket `runCatching` and `catch (Throwable)` blocks. Retain catch blocks only for documented best-effort cleanups, allowing infrastructure and programming errors to surface predictably.
- **8.4 Structured Logging:** Attach `projectId`, `serviceId`, and `deploymentId` to MDC across request pipelines and background worker execution contexts.
- **Done when:** Expected failures flow via explicit domain outcomes, unexpected errors map through centralized status handlers, and logs contain consistent diagnostic metadata.

#### Phase 9: Package Consolidation and Final Cleanup
**Goal:** Consolidate directory structure, eliminate transition shims, and lock in architecture rules.
- **9.1 Final Shim & Allowlist Removal:** Remove all backward-compatibility shims (`AppConfig` delegation, temporary adapters). The architecture ratchet allowlist must be empty with zero exceptions.
- **9.2 Package Structure Normalization:** Move all feature classes into standardized `domain`, `application`, `infrastructure`, and `presentation` directories matching `feature/payment`.
- **9.3 Verification of Test Pyramid:** Verify full test suite across unit tests (using fakes), database adapter tests (using Testcontainers), and end-to-end route tests.
- **9.4 Architecture Documentation:** Create `docs/architecture.md` outlining layer boundaries, port/adapter creation guidelines, and testing patterns. Update `AGENTS.md` to mandate the new architecture for future development.
- **Done when:** Target architecture rules are fully realized, ratchet test passes with zero allowlist entries, and documentation is updated.

---

### Phase Dependency and Execution Order

```
Phase 0 (Safety Net & Ratchet)
   │
   ▼
Phase 1 (Configuration Injection)
   │
   ▼
Phase 2 (Infrastructure Ports & Single Docker Client)
   │
   ▼
Phase 3 (Repository Interfaces & Transactions)
   │
   ├───────────────────────────┬───────────────────────────┐
   ▼                           ▼                           ▼
Phase 4 (Nginx Slice)      Phase 6 (Admin/Gate Slices)  Phase 7 (Worker Supervision)
   │                           │                           │
   └───────────────────────────┼───────────────────────────┘
                               ▼
                        Phase 5 (Deployment Slice - depends on Phase 2, 3, 4)
                               │
                               ▼
                        Phase 8 (Unified Error Model & Observability)
                               │
                               ▼
                        Phase 9 (Consolidation & Final Cleanup)
```

- **Strictly Sequential:** Phases 0 → 1 → 2 → 3 must be executed in order.
- **Parallelizable:** Once Phase 3 is complete, Phases 4, 6, and 7 can proceed independently.
- **Deployment Dependencies:** Phase 5 (Deployment Slice) should execute after Phase 2 (Docker port), Phase 3 (Repository interfaces), and Phase 4 (Nginx upstream ports) are in place.
- **Final Stages:** Phase 8 standardizes error handling across all decomposed modules, followed by Phase 9 final cleanup.