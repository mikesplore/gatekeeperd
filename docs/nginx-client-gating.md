# Nginx Client Project Gating

Gate client apps behind nginx using gatekeeperd. nginx stays the reverse proxy; gatekeeperd only decides allow vs block.

## Endpoints

| Endpoint | Used for | Response |
|----------|----------|----------|
| `/api/gate/auth?project={slug}&domain={domain}` | nginx `auth_request` subrequest | **403** when blocked (empty body) |
| `/api/gate/paywall?project={slug}&domain={domain}` | Reason-specific block page shown to clients | **402** HTML; payment blocks link to the customer portal |
| `/api/customer/projects/{slug}/portal` | Customer opens portal from paywall | Redirect to customer portal sign-in |
| `/api/gate/pay?project={slug}&serviceId={uuid}` | Signed-in customer starts Paystack checkout | Redirect to Paystack checkout |
| `/api/gate/payment/callback?project={slug}` | Paystack return URL | Redirect to project domain |

nginx `auth_request` only accepts **401** or **403** as deny codes. Never use `/api/gate/check` for the auth subrequest.
The `/api/gate/` location is a separate bypass path for payment and callback traffic, so those requests do not pass through `auth_request`.

## Example: `acw.mikesplore.me`

Replace `acw` with your project slug and `9921` with your app port. This production example routes Gatekeeperd calls through the stable `gatekeeperd_backend` upstream installed by the VPS deployment. Local DEV configurations use `127.0.0.1:8080` instead.

```nginx
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;

    server_name acw.mikesplore.me;

    ssl_certificate /etc/letsencrypt/live/acw.mikesplore.me/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/acw.mikesplore.me/privkey.pem;

    # -------------------------------------------------------------------------
    # 1. Gatekeeper Direct Bypass Route
    # Payment callbacks, webhooks, and paywall APIs bypass auth_request
    # -------------------------------------------------------------------------
    location /api/gate/ {
        proxy_pass http://gatekeeperd_backend/api/gate/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # -------------------------------------------------------------------------
    # 2. Main Protected Application Route (Aaron's Car Wash Backend)
    # Evaluates gatekeeper auth_request on every incoming request
    # -------------------------------------------------------------------------
    location / {
        auth_request /gatekeeper-auth-acw;
        error_page 403 = @gatekeeper_paywall_acw;

        proxy_pass http://127.0.0.1:9921;
        proxy_http_version 1.1;

        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # -------------------------------------------------------------------------
    # 3. Isolated Gatekeeper Subrequest (Browser-Header Sanitizer)
    # Strips all browser headers, CORS metadata, and POST bodies
    # -------------------------------------------------------------------------
    location = /gatekeeper-auth-acw {
        internal;
        proxy_pass http://gatekeeperd_backend/api/gate/auth?project=acw&domain=acw.mikesplore.me;

        # Always force subrequest method to GET
        proxy_method GET;

        # Disable body forwarding
        proxy_pass_request_body off;

        # Strip all body and content headers
        proxy_set_header Content-Length "";
        proxy_set_header Content-Type "";
        proxy_set_header Transfer-Encoding "";

        # Strip browser CORS and metadata headers that trigger 0ms 403s in Ktor
        proxy_set_header Authorization "";
        proxy_set_header Cookie "";
        proxy_set_header Origin "";
        proxy_set_header Referer "";
        proxy_set_header User-Agent "Nginx-Auth-Check";
        proxy_set_header Accept "";
        proxy_set_header Accept-Encoding "";
        proxy_set_header Accept-Language "";
        proxy_set_header Sec-Fetch-Dest "";
        proxy_set_header Sec-Fetch-Mode "";
        proxy_set_header Sec-Fetch-Site "";
        proxy_set_header Sec-Ch-Ua "";

        # Point Host header strictly to 127.0.0.1
        proxy_set_header Host 127.0.0.1;

        proxy_http_version 1.1;
        proxy_set_header Connection "";
    }

    # -------------------------------------------------------------------------
    # 4. Paywall Fallback Location
    # -------------------------------------------------------------------------
    location @gatekeeper_paywall_acw {
        rewrite ^ /api/gate/paywall?project=acw&domain=acw.mikesplore.me break;
        proxy_pass http://gatekeeperd_backend;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

That is the working split:
- `location /` protects the client app with `auth_request`
- `location = /gatekeeper-auth-acw` performs the internal allow/deny check
- `location @gatekeeper_paywall_acw` serves the paywall body
- `location /api/gate/` bypasses the guard so Pay Now, callback, and related gate endpoints stay reachable

If the client app itself terminates TLS on `443`, point `proxy_pass` at `https://127.0.0.1:443` instead of `http://127.0.0.1:9921`, and pass `upstreamScheme: "https"` to the nginx enable API if you are using the admin route.

Apply:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

## Gatekeeperd environment policy (DEV/PROD)

See the root `docs/README.md` for the full environment-variable reference.

### DEV (local development)

```env
GATEKEEPERD_ENVIRONMENT=DEV
GATEKEEPERD_NGINX_HOST_SUDO=true
NGINX_SITES_AVAILABLE=nginx/sites-available
NGINX_SITES_ENABLED=nginx/sites-enabled
```

In DEV, nginx host commands are **no-op by default**. Set `GATEKEEPERD_NGINX_HOST_SUDO=true` only when a local nginx installation is available; Gatekeeperd then runs `nginx -t` and `nginx -s reload` directly against `nginx/nginx.conf`, without sudo or systemd. Set `NGINX_BINARY` and `NGINX_CONFIG_PATH` if your local installation differs. Nginx files and certificates use project-local paths. Certbot install, renewal, and removal remain disabled in DEV unless `GATEKEEPERD_NGINX_CERTBOT_ENABLED=true` is explicitly set and the restricted helper is installed.

### PROD (VPS)

Keep the defaults: Gatekeeperd uses `/etc/nginx`, `/etc/letsencrypt`, and the `gatekeeperd-certbot` helper with restricted `sudo -n`.

## gatekeeperd `.env` (VPS)

```env
PAYSTACK_SECRET_KEY=sk_test_xxxx
PAYSTACK_PUBLIC_KEY=pk_test_xxxx
GATEKEEPER_PUBLIC_URL=https://gateapi.mikesplore.me
SUPPORT_CONTACT_EMAIL=you@example.com
```

`GATEKEEPER_PUBLIC_URL` must be the public URL where Paystack can reach `/api/gate/payment/callback`. It can be the gatekeeper API domain even if client sites use other domains.

Register the callback URL in Paystack dashboard:

```text
https://gateapi.mikesplore.me/api/paystack/webhook
```

(webhook for activation; Paystack also uses `callback_url` from each transaction for the browser redirect)

## Verify

```bash
curl -i "https://gateapi.mikesplore.me/api/gate/paywall?project=acw"   # 402 HTML paywall
curl -i "https://acw.mikesplore.me/api/v1/health"               # 402 HTML when blocked (browser)
curl -i -H "Accept: application/json" "https://acw.mikesplore.me/api/v1/health"  # 402 JSON for API clients
```

## Client payment flow

1. Client visits site → nginx blocks → shows paywall (project name, amount, due date)
2. Client opens the customer portal and signs in with their email code
3. Customer chooses a payment method for the service and completes payment
4. Provider confirmation updates the balance and restores access when the outstanding balance is cleared

No admin action required to generate a link.

## Admin dashboard note

**Generate payment link** still exists under Project detail → **Payments** tab for manual/offline use. Clients blocked at the nginx layer use the self-service paywall instead.
