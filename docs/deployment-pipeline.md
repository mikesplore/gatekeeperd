# Deployment pipeline

Gatekeeperd runs deployments in its background worker. The API only queues work; the frontend polls the deployment resource and audit endpoint.

The worker obtains a GitHub App installation token, checks out the requested ref, builds and pushes the image, pulls it on the host, then starts a candidate container. A candidate must be running and, when a host port is configured, reachable over TCP before it can replace the current container. The previous container image is persisted for rollback.

`registry` is a registry host (`docker.io` by default or a private host such as `registry.example.com:5000`). Docker credentials must already be configured for the service account running Gatekeeperd. Gatekeeperd does not accept registry passwords through the deployment request.

GitHub push deliveries are deduplicated using `X-GitHub-Delivery`. Configure repository/ref/image mapping on a project and set `autoDeploy=true` before enabling the webhook. Stale running jobs are returned to the queue on worker startup after `DEPLOYMENT_STALE_MINUTES`.

The project-centered setup flow may save a domain/gateway draft before the first deployment, but deployment does not create or enable an nginx site. A ready candidate becomes active and retires the old runtime independently when no enabled deployment-managed site exists. For a deployment-managed site, nginx keeps a stable `gk_<service UUID without hyphens>` upstream name and Gatekeeperd atomically rewrites only that service's include. It validates nginx, reloads, probes the candidate directly and through nginx with the site Host/SNI, waits `DEPLOYMENT_DRAIN_DELAY_SECONDS`, then rechecks before retiring the old runtime. The nginx site config does not change between deployments.

The nginx HTTP context must include `NGINX_UPSTREAM_INCLUDE_DIR/*.conf`. Startup creates/checks the directory and refuses to start if it is not writable or nginx does not load it. The local probe location is restricted to loopback and bypasses `auth_request` only for that exact probe path. Interrupted cutovers store the pending include contents and checksum in the existing durable cutover snapshot; startup resumes only when the file matches the saved target or prior checksum. Unknown file contents require the authenticated explicit repair endpoint. The periodic reconcilers remain report-only.

Legacy sites are converted one at a time through `GET /api/admin/nginx/upstream-migration` (dry run) and `POST /api/admin/nginx/upstream-migration/sites/{siteId}`. A `docker_discovery` site without an active deployment needs adoption first. An explicit-port developer site remains on its explicit target and deployment jobs do not rewrite it. To make that service deployment-managed, use the admin conversion operation; its legacy port is carried forward as the primary container port when it does not conflict with saved deployment configuration.
