# Deployment pipeline

Gatekeeperd runs deployments in its background worker. The API only queues work; the frontend polls the deployment resource and audit endpoint.

The worker obtains a GitHub App installation token, checks out the requested ref, builds and pushes the image, pulls it on the host, then starts a candidate container. A candidate must be running and, when a host port is configured, reachable over TCP before it can replace the current container. The previous container image is persisted for rollback.

`registry` is a registry host (`docker.io` by default or a private host such as `registry.example.com:5000`). Docker credentials must already be configured for the service account running Gatekeeperd. Gatekeeperd does not accept registry passwords through the deployment request.

GitHub push deliveries are deduplicated using `X-GitHub-Delivery`. Configure repository/ref/image mapping on a project and set `autoDeploy=true` before enabling the webhook. Stale running jobs are returned to the queue on worker startup after `DEPLOYMENT_STALE_MINUTES`.

The nginx step is intentionally separate: call the existing nginx enable endpoint after the container is healthy. This keeps deployment lifecycle and access/proxy configuration independently observable and reversible.
