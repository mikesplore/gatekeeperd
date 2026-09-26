# Core Remodeling — Phase 4 Decisions

These decisions resolve the credential scope and migration approach for [Phase 4](core-remodeling-plan.md#phase-4--credential-and-runtime-configuration-versioning). They keep the first implementation focused on the credentials Gatekeeperd already uses.

## Provider credential scope

The first provider credential model covers Docker registry credentials and GitHub access only. It is not a generic vault for arbitrary providers. Provider/type metadata should distinguish registry credentials from GitHub credentials, while each provider can add its own validation and usage rules.

The implementation audit must include both stored credentials and credentials supplied through process configuration. In particular, GitHub App private-key and installation-token flows should be represented accurately; do not copy environment-supplied configuration into database records automatically.

## Project/environment secret versioning

Application secrets are stored as one encrypted key/value map per immutable version, scoped to a project and environment. Replacing the map creates a new version; individual keys are not versioned independently. `production` remains the default environment for existing callers, consistent with the Phase 2 environment decision.

Deployments should reference the secret-set version they used. The version stores ciphertext, and only the deployment worker decrypts values when it prepares a runtime. API responses, logs, audit details, and deployment history expose metadata or references only.

## Additive transition

Migrate with nullable reference/version columns and keep the existing encrypted secret-map columns and registry credential key available during rollout. New writes populate both the new model and compatibility representation. Provide a dry-run inventory and idempotent backfill for existing registry and project/environment secret data; unresolved ownership remains visible and is not guessed. Reads prefer the new references when present and fall back to the existing encrypted representation when absent. Remove old writes and columns only in a later compatibility-retirement phase.

The concrete migration must preserve existing ciphertext byte-for-byte during backfill where possible. Backfill must not decrypt and re-encrypt secrets unless the encryption format explicitly requires it, and reports must never print credential values or ciphertext.
