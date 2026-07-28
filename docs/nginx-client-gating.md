# Nginx Client Project Gating

Gate client apps behind nginx using gatekeeperd. nginx stays the reverse proxy; gatekeeperd only decides allow vs block.

## Endpoints

| Endpoint | Used for | Response |
|----------|----------|----------|
| `/api/gate/auth?project={slug}` | nginx `auth_request` subrequest | **403** when blocked (empty body) |
| `/api/gate/paywall?project={slug}` | Paywall page shown to clients | **402** HTML with amount, due date, Pay Now |
| `/api/gate/pay?project={slug}` | Client clicks Pay Now | Redirect to Paystack checkout |
| `/api/gate/payment/callback?project={slug}` | Paystack return URL | Thank-you HTML page |

nginx `auth_request` only accepts **401** or **403** as deny codes. Never use `/api/gate/check` for the auth subrequest.

## Example: `acw.mikesplore.me`

Replace `acw` with your project slug and `9921` with your app port.

```nginx
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name acw.mikesplore.me;

    ssl_certificate /etc/letsencrypt/live/vela.mikesplore.tech/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/vela.mikesplore.tech/privkey.pem;

    # Route gatekeeper payment pages through the client domain (same-origin Pay Now button)
    location /api/gate/ {
        proxy_pass http://127.0.0.1:8080/api/gate/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

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

    location = /gatekeeper-auth-acw {
        internal;
        proxy_pass http://127.0.0.1:8080/api/gate/auth?project=acw;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header X-Original-URI $request_uri;
    }

    location @gatekeeper_paywall_acw {
        proxy_pass http://127.0.0.1:8080/api/gate/paywall?project=acw;
        proxy_set_header Host $host;
    }
}
```

Apply:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

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

(webhook for activation; Paystack also uses `callback_url` from each transaction for the browser thank-you page)

## Verify

```bash
curl -i "http://127.0.0.1:8080/api/gate/paywall?project=acw"   # 402 HTML paywall
curl -i "https://acw.mikesplore.me/api/v1/health"               # 402 HTML when blocked (browser)
curl -i -H "Accept: application/json" "https://acw.mikesplore.me/api/v1/health"  # 402 JSON for API clients
```

## Client payment flow

1. Client visits site → nginx blocks → shows paywall (project name, amount, due date)
2. Client clicks **Pay Now** → `/api/gate/pay?project=acw` → Paystack checkout
3. After payment → thank-you page → webhook activates project → site works again

No admin action required to generate a link.

## Admin dashboard note

**Generate payment link** still exists under Project detail → **Payments** tab for manual/offline use. Clients blocked at the nginx layer use the self-service paywall instead.
