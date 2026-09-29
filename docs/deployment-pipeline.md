# Deployment pipeline

Gatekeeperd runs deployments in its background worker. The API only queues work; the frontend polls the deployment resource and audit endpoint.

The worker obtains a GitHub App installation token, checks out the requested ref, builds and pushes the image, pulls it on the host, then starts a candidate container. A candidate must be running and, when a host port is configured, reachable over TCP before it can replace the current container. The previous container image is persisted for rollback.

`registry` is a registry host (`docker.io` by default or a private host such as `registry.example.com:5000`). Docker credentials must already be configured for the service account running Gatekeeperd. Gatekeeperd does not accept registry passwords through the deployment request.

GitHub push deliveries are deduplicated using `X-GitHub-Delivery`. Configure repository/ref/image mapping on a project and set `autoDeploy=true` before enabling the webhook. Stale running jobs are returned to the queue on worker startup after `DEPLOYMENT_STALE_MINUTES`.

The project-centered setup flow may save a domain/gateway draft before the first deployment, but deployment does not create or enable an nginx site. A ready candidate becomes active and retires the old runtime independently when no enabled managed site exists. If an enabled managed site already exists, cutover updates that site's upstream and validates/reloads nginx before retiring the old runtime. Create or enable a new site separately through Nginx administration after deployment; this lets certificate setup and gateway activation be handled independently from container readiness.
