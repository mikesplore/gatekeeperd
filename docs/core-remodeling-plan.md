# Gatekeeperd Core Remodeling Plan

## Purpose

Make Gatekeeperd's persistent model describe the application it manages, rather than treating a Docker container as the application. A project should survive container replacement, failed deployment, transfer of its runtime, and periods with no runtime.

This plan builds on existing functionality. Gatekeeperd already has customer records, project billing and access state, deployment configuration and execution records, encrypted application environment values, registry credentials, Docker deployment workers, safe container replacement, nginx sites, certificates, audit history, and payment reconciliation. The work is primarily about connecting and clarifying these concepts, preserving their history, and making the lifecycle project-centered.

## Domain target

```text
Customer 1 ─── * Project
                   ├── access, lifecycle, billing, policy
                   ├── source and desired runtime configuration
                   ├── domain and gateway configuration
                   └── Deployment 1 ─── * Service ─── * Runtime instance
```

Infrastructure credentials (GitHub, registry, and future providers) belong to Gatekeeperd. Application configuration and secrets belong to a project environment and are referenced by a deployment. A deployment records the immutable runtime version and the configuration versions it used. Containers remain disposable runtime instances.

For the initial implementation, one project has one primary service and one active deployment at a time. The model should leave room for multiple services later, without exposing multi-service orchestration in the first UI/API iteration.

## Phase 7 — Service identity (chunked)

Phase 7 evolves the project-centered model so one customer project can own multiple independently configured services/deployments (for example, a frontend and backend). Implement this phase in small, reviewable chunks. The decisions below are locked for implementation and supersede conflicting earlier assumptions in this document, including project-owned sites and source settings.

### Chunk 0 — Locked decisions

1. **Effective status:** Project access status controls customer access across all services. A service status controls only that service's deployment/runtime. A project-level block gates every service; a service-level block affects only that service. Deployment health does not implicitly mutate project access or service status.
2. **Shared variable imports:** A service pins imported project-level variable versions when configured. Later edits to shared variables do not silently alter that service's desired configuration; an explicit import refresh/update followed by redeployment applies them. Each deployment records the resolved variable versions it used.
3. **Site ownership:** A site attaches to a service because it routes to one service. Project identity, billing, and customer ownership remain project-scoped.
4. **Source settings:** Repository, branch/ref, and related build/source settings belong to a service. Services in the same project may use different sources.
5. **Project-level environment:** Keep project-level variables as shared variables that services can import. Service-specific variables may add or override imported keys. The resolved values used by each deployment are captured in that deployment's immutable configuration snapshot, while secret values remain protected under the existing secret-handling rules.

### Chunk sequence

Keep subsequent Phase 7 chunks independently reviewable and compatible with the existing single-service workflow. Establish service identity and backfill existing project configuration as the first implementation chunk; then migrate source/configuration ownership, environment imports and snapshots, site routing, and API/UI workflows in separate chunks. Update this sequence as each chunk's concrete schema/API scope is agreed.

### Chunk 1 — Service identity table and default-service backfill

- Add an additive Flyway migration creating `services` with UUID `id`, required `project_id` FK, required `name`, and unique `(project_id, name)` constraint.
- Backfill exactly one `default` service for each existing project that does not already have one. Preserve existing project data and make the insert idempotent.
- Before applying the migration in an environment, run `service-backfill --dry-run`; the command reports project counts with and without a default service and the planned insert count, without modifying data. The Flyway migration remains the only write path for this chunk.
- This chunk establishes identity only. It does not yet move deployment configuration, source settings, environment variables, sites, routes, or runtime status to service ownership.

### Chunk 2 — Service ownership on runtime records

- Add nullable `service_id` foreign keys to deployment configurations, deployment executions, deployment jobs, and project secret-set versions. Keep existing `project_id` columns during the compatibility period.
- Backfill each row to the `default` service for its project where that association resolves. Keep unresolved legacy rows nullable and visible; do not guess ownership.
- Scope secret-set version uniqueness by `(service_id, environment, version)` while retaining `project_id` as a compatibility owner.
- Dual-write project and service IDs for new/updated configurations, execution snapshots, queued jobs, and secret-set versions. Existing project-based configuration/job lookups resolve to the project's default service and prefer matching `service_id` rows, with a nullable-ID fallback for legacy data.
- Validate a deployment's secret-set service ownership when resolving its execution snapshot, while tolerating historical rows whose service ID remains null.
- Do not yet remove project-level ownership, alter site ownership, or expose multiple services through routes/UI.

### Chunk 3 — Deployment lifecycle per service

- Add nullable `service_id` to canonical deployments and backfill from the associated execution/configuration service, falling back to the project's `default` service when necessary.
- Replace the partial unique active-deployment index on `(project_id, environment)` with one on `(service_id, environment)`, retaining the `status = 'active'` predicate.
- Write service ownership when creating queued or adopted deployment lifecycle records. Scope activation/supersession and rollback-active checks to service plus environment; keep the existing status transition graph unchanged.
- Serialize worker processing with a distributed lock keyed by service ID and environment. Resolve the claimed job's service ID and use it when locating the prior active runtime.

### Chunk 5 — Versioned shared and service environment sets

- Keep service-level environment versions in the existing encrypted version table, now owned by `service_id`; add a separate project/environment table for immutable encrypted shared-variable versions.
- Desired configuration may pin an optional shared-set ID/version and a service-set ID/version. Shared-set references are validated against the owning project and environment.
- At deployment creation, resolve the pinned shared map first, then apply service values so the service wins on duplicate keys. The immutable execution snapshot stores the two set references and a key-to-source metadata map (`project_shared` or `service`), never environment values.
- Store environment values only in the encrypted version-set payloads for newly written configurations/executions. Keep a compatibility resolver for historic snapshots that still have inline `env_json` or encrypted legacy secret payloads.
- The worker resolves version references immediately before container creation. Log/error redaction uses resolved values in memory; values are not copied into deployment, execution, or queue snapshots.

### Chunk 6 — Environment edit redeployment

- Editing a service environment creates a new encrypted service-set version, updates that service's desired configuration reference, and queues a deployment from that configuration in one database transaction.
- Editing shared project variables creates a new shared-set version. Services pinned to the previous latest shared version are explicitly refreshed to the new version and redeployed; services pinned to an older version remain unchanged, following Chunk 0's pin policy.
- Each shared-edit fan-out deployment is queued with that service's own configuration and environment set. The shared edit and all resulting service configuration/deployment records commit atomically.
- Environment values are accepted write-only and remain only in encrypted version payloads. API responses return version IDs and queued deployment IDs.

### Chunk 7 — Gateway ownership by service

- Add nullable `sites.service_id` with a foreign key to `services`, and backfill existing sites to their project's `default` service. Keep the project FK for project ownership and billing/gate policy.
- Resolve a gateway site using `(service_id, environment)` and select that service's active deployment. Project-level views without an explicit service continue to use the default service.

### Chunk 8 — Access policy per service

- Add service `access_status` and `block_reason` fields, independent of deployment/runtime health.
- Extend the access-block model additively on both `projects` and `services`: add a nullable `block_reason_code` backed by the shared `access_block_reason` enum and a nullable free-text `block_reason_note`. Keep the existing free-text `block_reason` columns during compatibility; classify only recognized legacy values in the migration and preserve unrecognized values unchanged.
- Supported reason codes are `payment`, `manual_hold`, `abuse_tos`, `suspended_by_request`, and `other`.
- Gate checks resolve the requested project or site slug to its site, then its service, and compute effective access: a project billing block denies all services; otherwise that service's access status applies.
- Resolve the wall's reason from the block that actually denies access: project reason code/note takes precedence whenever the project is blocked; otherwise use the blocked service's reason code/note. A service-only block must not affect sibling services.
- Auto-blocking continues to set only the project billing block. It does not set service access status.
- Invalidate cached decisions for affected project and site slugs when project or service access changes.
- Scope deployment cutover, rollback, and gateway reconciliation to the service's site. Distinct sites use distinct nginx config slugs; rendered config includes stable site identity so reconciliation can distinguish multiple domains in one project.
- Retain the existing multi-site database support; do not add a project-wide uniqueness restriction.

### Chunk 9 — Service and environment APIs

- Add authenticated service list/create/read/update/delete endpoints scoped to a project. Keep the default service permanent and preserve any service referenced by deployment, environment, or site history.
- Add metadata-only reads for project shared and service environment versions. Responses may include key names, versions, timestamps, and pinned configuration references, but never environment values.
- Provide a dedicated service environment write endpoint that creates a new encrypted version and queues that service's deployment atomically. Keep the shared environment write endpoint's existing atomic fan-out policy.
- Add active-deployment inspection scoped to a service and environment. It reports immutable deployment and set-version references plus each resolved key's source and keyed HMAC fingerprint; it never returns plaintext values.

## Current state and gaps

| Area | Already present | Gap to address |
|---|---|---|
| Customer and project | Separate `customers` and `projects`; projects may reference a customer | Project creation is currently container-first; project records still carry `container_name` and image fields as if they define runtime identity |
| Deployments | `deployment_configurations`, `deployment_executions`, legacy `deployment_jobs`; execution captures source, image, ports, network, env, encrypted secret env, volumes, status, commit and digest | Configurations/executions use nullable `project_slug` rather than a stable project FK; execution state is not yet the unambiguous source of the project's current runtime; lifecycle and rollback links need formal semantics |
| Safe replacement | Worker starts a candidate, checks it, then switches/stops the previous runtime | Make the deployment state transitions and current deployment pointer durable and explicit, including failure/rollback outcomes |
| Gateway/domain | `sites` stores domain, upstream, TLS, gate and reconciliation state; certificates are separately stored | Site is constrained to one site per project; formalize domain as a project gateway attachment whose upstream follows the active deployment |
| Credentials | Registry credentials are encrypted; application secret environment values are encrypted in deployment config/execution | No unified metadata/versioned credential-set model; infrastructure credentials and project environment secrets have different ownership and should be represented separately |
| Access/lifecycle | Project status, block reason, service mode and lifecycle status are already separate | Define allowed transitions and ensure runtime health/deployment failures never silently rewrite business access or lifecycle state |
| Auditability | Audit and deployment logs exist | Ensure deployment/config/credential version references and operator/source details are sufficient to explain any active runtime |

## Design principles

1. **Project is durable identity.** Billing, access, customer, domain, and audit history remain attached to a project when runtime changes.
2. **Deployment is a persistent version.** It records source revision, built image and digest, runtime configuration snapshot, credential version references, trigger, outcome, and timestamps.
3. **Runtime is replaceable.** A container or future service instance belongs to a deployment, not directly to the customer or project identity.
4. **Business state is independent.** Project access and lifecycle are not inferred from container status. Deployment health is its own operational state.
5. **Gateway targets the active deployment.** Domains remain attached to projects; gateway routing changes only after a candidate is ready.
6. **Secret values remain server-side.** APIs, logs, audit events, and MCP-facing tools expose metadata and references, never plaintext secret values.
7. **Runtime changes use candidate replacement.** Image, ports, volumes, environment, secrets, and similar changes produce a candidate deployment; do not mutate the serving container in place.
8. **Keep the first scope focused.** Continue using GitHub, Docker/Docker Hub, one VPS and nginx. Preserve a path to multiple services without implementing a cluster orchestrator.

## Target records and relationships

### Project

Owns stable application identity, customer relationship, source defaults, business access/lifecycle, billing policy, and project-level desired configuration references. Project creation must be valid with no deployment and no container. Replace runtime-defining project columns only after all readers/writers use deployment records; retain compatibility fields during migration.

### Deployment configuration

Represents the editable desired configuration for a project/environment: repository/ref, image settings, runtime parameters, non-secret environment, references to secret sets, volumes, health check and resource settings as supported. Use a stable `project_id` FK. Updating desired configuration must not rewrite historical deployments.

### Deployment

Represents one immutable attempted/released runtime version. It should include project FK, configuration snapshot/version, source commit, image digest, status, trigger/actor, timestamps, failure reason, and links to the deployment it replaces or rolls back to. Never include plaintext secrets. Define statuses such as queued, building, starting, health-checking, active, superseded, failed, cancelled, and rolled-back; finalize the exact state machine before migration implementation.

### Service and runtime instance (later-compatible)

For the first release, associate one primary runtime instance with a deployment. Use a service abstraction only where it materially reduces coupling in this single-service path. Do not build arbitrary multi-container manifests yet. Design keys and API shapes so a later deployment can contain named services and multiple instances.

### Project gateway/domain

Associate one or more domains/sites with a project, independent of a specific container. A site resolves its upstream from the active deployment's primary runtime, with explicit port/process upstream support retained for developer-hosted projects. Enable/disable and reconciliation remain gateway state, not project identity.

### Credentials

Keep two scopes distinct:

- **Infrastructure credentials:** provider-level registry/GitHub/etc. credentials, with metadata, encryption, rotation, and permission scope.
- **Application environment sets:** project/environment-scoped key/value secrets, versioned as immutable snapshots. Deployments reference a version; the version owns encrypted values.

No endpoint or MCP tool should return secret values after creation. Support replace/rotate and metadata inspection. Treat credential changes as desired configuration changes that require an explicit deployment, with an atomic save-and-deploy operation available for automation.

## Phased implementation

### Phase 0 — Baseline and compatibility map

- Inventory every API, worker, scheduler, dashboard flow, migration, and query that reads/writes `projects.container_name`, image fields, `project_slug`, deployment tables, and `sites.project_id`.
- Document existing container-first creation and safe replacement behavior, including rollback and failure cases.
- Establish invariants: a project may have no deployment; at most one deployment is active per project/environment; failed candidates never replace the active deployment; gateway/access status are independent.
- Deliverable: a checked-in data-flow and compatibility matrix plus explicit state diagrams before schema changes.

**Exit criteria:** all current consumers are identified; production data shape and backfill rules are known; no destructive migration is required for the first rollout.

### Phase 1 — Stable project ownership and create-before-deploy

- Add stable `project_id` references to deployment configuration, execution, and job records. Backfill from `project_slug` where it resolves; report unresolved rows for operator repair rather than guessing.
- Make project creation independent of Docker discovery. Keep a compatibility path for existing container-first UI/API callers during rollout.
- Allow project source, customer, billing, access, lifecycle, and domain configuration to exist before first deployment.
- Treat existing project container/image columns as compatibility projections temporarily; mark the deployment record as the source of truth for managed runtime state.
- Add admin API operations for creating a project without a container and linking/creating its deployment configuration.

**Exit criteria:** fresh project can be created without Docker availability; existing projects and old clients still work; deployment records resolve by `project_id`.

### Phase 2 — Deployment as current runtime authority

- Define deployment state machine and enforce allowed transitions centrally in the deployment application service.
- Persist candidate, active, superseded, failed and rollback relationships. Add a project/environment current-deployment pointer or an equivalent constrained relation.
- Ensure deployment starts a candidate, performs readiness checks, changes gateway routing only after success, and only then retires the prior runtime.
- On candidate failure, retain the old active deployment and route; record diagnostic output without exposing secrets.
- Make rollback create or reactivate a well-defined deployment version, preserving audit history.
- Reconciliation should compare persisted active deployment state with Docker/gateway state and report drift; it must not invent project identity from discovered containers.

**Exit criteria:** after restart, Gatekeeperd can identify the active deployment and runtime without relying on mutable project container fields; failed candidate leaves the former deployment serving.

### Phase 3 — Gateway and domain follow active deployment

- Keep domain/site ownership on project; remove the assumption that a site permanently targets a project-level container name.
- Add a resolver that obtains the desired upstream from the active deployment runtime, while preserving explicit host/port mode for non-Docker apps.
- Support zero or more domains per project at the model level; retain current one-site behavior until API/UI requirements and DNS/TLS constraints are ready.
- Coordinate deployment cutover with nginx validation/reload and certificate state. If gateway validation fails, do not retire the old runtime.
- Keep payment gating as a project access decision at the gateway, independent of runtime health.

**Exit criteria:** replacing a container/image does not change project/domain identity; gateway only switches to a successfully validated deployment; blocked projects still receive the configured paywall behavior.

### Phase 4 — Credential and runtime configuration versioning

- Introduce provider credential records with stable IDs, provider/type, display name, scope, encrypted payload, rotation metadata, and audit history. Migrate registry credential use behind this abstraction without exposing decrypted values in DTOs.
- Introduce project/environment secret sets with immutable versions. Encrypt values at rest and only decrypt in the deployment worker at the point of use.
- Deployment snapshots reference credential set IDs and versions, not secret values. Non-secret runtime configuration also gets a version/snapshot reference.
- Add safe create/replace/rotate APIs, metadata-only list/detail APIs, and authorization/audit rules.
- Add runtime settings incrementally: health checks first, then commands/resources as product requirements justify. Validate network, volume, port, and host-path policy; never blindly execute a repository-provided compose file.
- Credential/config changes create a new desired version and require explicit deployment; provide one atomic save-and-deploy operation for automation.

**Exit criteria:** old deployments can be explained by configuration and secret-version references; secret plaintext never appears in API responses, logs, audit payloads, or deployment history.

### Phase 5 — API and dashboard project-centered workflows

- Reshape project setup into: create project → configure source/runtime → configure credentials → configure domain/gateway → deploy.
- Project overview presents separate sections for access/lifecycle, desired configuration, current deployment/runtime health, domains/gateway, customer/billing, and deployment history.
- Deployment history shows source commit/image digest, who/what triggered it, status, health result, configuration/credential versions, and rollback action.
- Customer pages stay commercial: customer totals and their projects. They should not treat deployments or containers as customer-owned records.
- Infrastructure credential pages manage provider credentials; project settings select references and versions without revealing values.
- MCP/API automation uses credential references and supported deployment operations only; no secret-read capability.
- Preserve compatibility during transition through additive API fields/endpoints, then deprecate old container-centric fields after frontend and external consumers have migrated.

**Exit criteria:** an administrator can create a project before any container exists, deploy it, see the active deployment/runtime, rotate a credential with an explicit redeploy, and rollback without losing customer, payment, access, or domain history.

### Phase 6 — Retire compatibility projections

- Confirm telemetry/audit shows no supported client relying on old container-first fields or slug-based deployment ownership.
- Remove old writes first, then old reads and API fields in a documented breaking-change window.
- Drop project-level container/image columns only after backups, migration validation, and rollback procedures are proven.
- Keep a legacy import/backfill utility for manually managed existing containers/sites; importing runtime state must not silently create or merge business projects.

**Exit criteria:** deployment is the authoritative runtime model; project records are valid without a runtime; migration and recovery runbooks are current.

## Data migration and rollout rules

1. Use additive, versioned Flyway migrations. Never replay or edit an already-applied production migration.
2. Back up production data and test migration/rollback against a recent sanitized copy before rollout.
3. Add new nullable FKs and indexes; backfill in resumable batches; validate row counts and orphan reports; only then enforce `NOT NULL` where justified.
4. Resolve project slugs to stable IDs during backfill. Keep unresolved records visible for manual repair; do not infer identity from a Docker container name alone.
5. Deploy code that can read old and new representations before switching writes. Switch writes to new model, observe, then remove compatibility reads in a later release.
6. Preserve payments, audit, customer links, project slugs, domains, and certificate associations. Runtime migration must never delete project history.
7. Make every backfill idempotent and produce a dry-run report before applying mutations.

## Cross-cutting acceptance checks

- A project can be created and edited while Docker is unavailable and before it has a deployment.
- Customer → project cardinality remains one-to-many; projects retain their customer and financial history across all deployment changes.
- Failed deployment does not alter active traffic, access status, lifecycle, or billing state.
- Successful deployment changes gateway upstream only after health and nginx validation succeed; previous runtime remains available until cutover succeeds.
- Rollback is auditable and does not erase the failed or superseded deployment.
- Payment blocking/suspension affects gateway access without requiring the app container to stop.
- Domain/TLS ownership survives runtime replacement and supports explicit non-container upstreams.
- Secret values are encrypted at rest and absent from all read APIs, logs, audit details, and MCP outputs.
- Existing deployment/project/site data migrates with reportable unresolved cases and a tested recovery path.
- The single-service Docker-on-one-VPS workflow remains straightforward; multi-service orchestration is not required for acceptance.

## Decisions to settle before schema implementation

These decisions are intentionally called out instead of hidden in a migration:

1. Is a site's ownership exactly a project, or should sites attach to a named project service? Start with project ownership plus one primary service unless multiple independent domains-to-services are an immediate requirement.
2. Is one active deployment allowed per project, or per environment? The schema should include environment in the uniqueness/current-pointer rule even if production is the only environment initially.
3. Which runtime fields belong in immutable deployment snapshots versus reusable desired configuration? Snapshot everything that affects the running process; keep reusable configuration separately editable.
4. What readiness contract can each deployment use: Docker health check, HTTP probe, TCP probe, or process-running fallback? Define safe timeouts and the default before cutover changes.
5. What is the desired lifecycle vocabulary and transition authority for `lifecycle_status`, `service_mode`, and access status? Document it without conflating deployment state.
6. Which provider credentials are in first scope? Registry credentials and GitHub access are current; avoid making an open-ended generic secret vault before use cases are defined.
7. Which API compatibility window is needed for the separate admin frontend and any scripts/MCP clients?

## Out of scope for the first remodeling releases

- Kubernetes, Swarm, multi-host scheduling, service meshes, or cluster orchestration.
- Arbitrary `docker-compose.yml` execution from an application repository.
- A promise of multi-service deployments in the first cut; only data-model compatibility is required.
- Replacing nginx or the current payment providers.
- Rewriting billing, Paystack, M-Pesa, or customer semantics except where stable project identity requires a reference update.

## Completion definition

The remodeling is complete when Gatekeeperd treats Project as the stable managed application, Deployment as the versioned runtime release, and Container as a disposable deployment artifact; supports creating projects before runtime exists; routes domains through the current healthy deployment; preserves payment/access/customer/audit history across replacement and rollback; and manages credentials as versioned references without exposing secret values.
