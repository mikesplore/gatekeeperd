# Gatekeeperd

Payment gating engine for client projects hosted on your VPS. Gatekeeperd sits behind your reverse proxy (nginx or Traefik), decides whether traffic reaches a client app, and shows a self-service Paystack paywall when a project is suspended.

**Active clients** → traffic passes through. **Overdue or blocked clients** → 402 paywall with Pay Now. **Payment received** → project unblocked automatically via Paystack webhook.

Admin dashboard (`gatekeeperd-frontend`) manages projects, blocking, payments, and Docker containers via a JWT-protected API.

## Documentation

| Doc | Description |
|-----|-------------|
| [docs/README.md](docs/README.md) | Project index — architecture, source layout, env vars |
| [docs/API.md](docs/API.md) | REST API reference |
| [docs/nginx-client-gating.md](docs/nginx-client-gating.md) | Gate client sites with nginx |
| [docs/staging-deployment.md](docs/staging-deployment.md) | Build, push, and deploy to VPS |
| [.env.example](.env.example) | Required environment variables |

## Quick start

```bash
cp .env.example .env   # edit credentials, admin login, Paystack keys
./gradlew run          # local dev (needs Postgres + Redis)
```

Production deploy: `./build.sh && ./push.sh` on dev machine, then `./deploy.sh` on the VPS. See [docs/staging-deployment.md](docs/staging-deployment.md).
