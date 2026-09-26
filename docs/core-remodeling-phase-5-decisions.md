# Core Remodeling — Phase 5 Decisions

These decisions resolve the API rollout and release-verification questions for [Phase 5](core-remodeling-plan.md#phase-5--api-and-dashboard-project-centered-workflows).

## API and frontend rollout

Keep the API and dashboard on an additive, independently deployable rollout. Existing request fields, response fields, and endpoint behavior remain compatible with the currently deployed dashboard and supported scripts while the new dashboard version is built and released separately. New project-centered fields and operations are additive. Do not remove, rename, or change the meaning of existing container-centric fields as part of this phase. Deprecation requires a later compatibility window after consumers have migrated.

The backend is the compatibility boundary: the old dashboard must continue to work against the updated API without modification. The new dashboard may use the additive project-ID/configuration/credential operations. Frontend work belongs in the separate `gatekeeperd-frontend` repository and must not include unrelated pre-existing working-tree changes.

## Required meaning of deployed and verified

Phase 5 is not complete based on builds, mocked UI, or API-only project creation. Its exit check must exercise this complete flow against the disposable development database and a real Docker runtime:

1. Create a project without a container, preserving the selected customer, access/lifecycle, and domain identity.
2. Configure source and runtime, configure registry access and a project/environment secret set, attach the project domain/gateway, and deploy.
3. Observe the deployment become active, resolve its runtime from the persisted active deployment, and confirm the domain/site and project identity stayed stable.
4. Rotate the registry credential and replace the project secret set with new versions, explicitly redeploy, and confirm the active deployment references and uses those new versions. Secret values must not appear in read responses, logs, audit output, or history.
5. Roll back through the supported operation and confirm a new auditable deployment is created from the prior runtime/configuration references; prior history and project/customer/payment/access/domain records remain present.

Record deployment IDs/status transitions, active runtime identity, credential and secret-set version metadata, gateway target, and relevant project/domain IDs in a checked-in verification note. Never include plaintext secrets, ciphertext, or customer-sensitive values. If Docker, registry, or database prerequisites prevent a step, document the exact blocker and leave the corresponding exit criterion open rather than treating compilation as a substitute.

## Existing verification gaps carried forward

The Phase 3 verification note says a real cutover and payment block with a healthy runtime were not exercised. The Phase 4 verification exercised credential rotation and secret-version references, but did not prove that a successful new deployment decrypted the selected new versions at runtime. The Phase 5 flow above must close deployment/cutover, worker credential and secret resolution, and rollback gaps where the disposable development environment permits. It does not silently convert any still-unverified earlier criterion into a verified one.
