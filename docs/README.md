# Gatekeeperd documentation

Gatekeeperd is a Kotlin/Ktor service that applies project and service access rules, records billing and payments, and can manage Docker deployments and nginx routes. The React administration dashboard and Scribed document service are maintained in separate repositories.

## Start here

| Need | Guide |
| --- | --- |
| Understand the current system, data flows, workers, and operations | [Backend system guide](backend-system-guide.md) |
| Call an endpoint or check its request and response shape | [API reference](API.md) |
| Configure or run the application locally | [`.env.example`](../.env.example) and [root README](../README.md) |
| Install or upgrade Gatekeeperd on a VPS | [VPS deployment guide](staging-deployment.md) |
| Put a client application behind nginx payment gating | [Nginx client gating](nginx-client-gating.md) |
| Put the Gatekeeperd API behind nginx and TLS | [Nginx reverse proxy](nginx-reverse-proxy.md) |
| Understand application deployment and cutover behavior | [Backend system guide, deployment system](backend-system-guide.md#11-deployment-system) |

## Product model

A **customer** owns one or more **projects**. A project can contain multiple **services**. Billing and access can apply at project level or to an individual service; a project-wide block overrides service access. Each service has its own runtime configuration, environment versions, deployment history, and optional domain/site.

Nginx or Traefik remains the public reverse proxy. Gatekeeperd answers whether traffic may reach the application. Paystack and M-Pesa handle payments; PostgreSQL stores the billing/access state; Redis caches gate decisions; Scribed handles invoice and receipt documents and configured notifications.

## Configuration reference

Keep [.env.example](../.env.example) as the environment-variable template. It documents the database, Redis, authentication, payment providers, Scribed/email integrations, Docker, GitHub, nginx, deployment, and gate settings. The backend system guide summarizes configuration groups and describes runtime behavior. Avoid copying full environment tables into other guides.

## Documentation ownership

- [API reference](API.md) is the source for endpoint contracts.
- [Backend system guide](backend-system-guide.md) explains current implementation and operational behavior.
- The nginx and VPS guides contain task-specific procedures and configuration examples.
- Source code and migrations are authoritative when implementation changes. Update the relevant guide when behavior changes; do not keep separate phase plans or static architecture reviews as current documentation.
