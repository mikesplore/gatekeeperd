# Architecture Baseline

Recorded on 2026-10-17 before the Clean Architecture refactor.

## Scope and method

Counts cover Kotlin sources under `src/main` and `src/test`, except the large-file
inventory, which covers production sources under `src/main/kotlin`. Line counts
come from `wc -l`; pattern counts are literal repository-wide source searches.

## Baseline measurements

| Measure | Count |
| --- | ---: |
| Production Kotlin files over 500 lines | 10 |
| Repository singleton declarations | 17 |
| `DockerService(` occurrences | 27 |
| `System.getenv` occurrences | 8 |

## Production files over 500 lines

| Lines | File |
| ---: | --- |
| 1,499 | `src/main/kotlin/com/gatekeeper/nginx/NginxService.kt` |
| 1,434 | `src/main/kotlin/com/gatekeeper/db/repositories/DeploymentJobRepository.kt` |
| 1,281 | `src/main/kotlin/com/gatekeeper/admin/NginxAdminRoutes.kt` |
| 993 | `src/main/kotlin/com/gatekeeper/admin/ProjectAdminRoutes.kt` |
| 904 | `src/main/kotlin/com/gatekeeper/deployment/DeploymentWorker.kt` |
| 880 | `src/main/kotlin/com/gatekeeper/admin/ProjectSetupAdminRoutes.kt` |
| 809 | `src/main/kotlin/com/gatekeeper/deployment/DeploymentApplicationService.kt` |
| 772 | `src/main/kotlin/com/gatekeeper/docker/DockerService.kt` |
| 646 | `src/main/kotlin/com/gatekeeper/admin/OperationsAdminRoutes.kt` |
| 534 | `src/main/kotlin/com/gatekeeper/auth/AuthRoutes.kt` |

## Repository singleton declarations

The 17 declarations are in:

- `CertificateRepository`
- `ProviderCredentialRepository`
- `GitHubCredentialRepository`
- `GitHubAppInstallationRepository`
- `RegistryCredentialRepository`
- `IntegrationOutboxRepository`
- `AuditRepository`
- `NotificationRepository`
- `EnvironmentSetRepository`
- `CustomerRepository`
- `DeploymentJobRepository`
- `SiteRepository`
- `ProjectAdjustmentRepository`
- `ServiceRepository`
- `DeploymentPortAllocationRepository`
- `GitHubWebhookRepository`
- `ProjectRepository`

This document is a snapshot for the Phase 0.1 ratchet. Later phases should
record reductions through their focused changes rather than rewriting this
historical baseline.
