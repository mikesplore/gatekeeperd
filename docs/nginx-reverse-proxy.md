# Nginx Reverse Proxy Configuration

If you want to expose gatekeeperd behind nginx with SSL, use this configuration.

## Prerequisites
- nginx installed on your VPS
- SSL certificate for your domain (e.g., via Let's Encrypt)
- gatekeeperd container running on port 8080

## Gatekeeperd environment policy

Gatekeeperd reads `GATEKEEPERD_ENVIRONMENT` to control host-privilege policy. The nginx reverse-proxy docs assume **PROD** (system paths and restricted sudo). For local development:

```env
GATEKEEPERD_ENVIRONMENT=DEV
GATEKEEPERD_NGINX_HOST_SUDO=true
NGINX_SITES_AVAILABLE=nginx/sites-available
NGINX_SITES_ENABLED=nginx/sites-enabled
```

In DEV, nginx host commands are **no-op by default** and Certbot is disabled. Setting `GATEKEEPERD_NGINX_HOST_SUDO=true` enables direct, sudo-less nginx validation and reload against `nginx/nginx.conf`. Set `NGINX_BINARY` and `NGINX_CONFIG_PATH` for local overrides. Set `GATEKEEPERD_NGINX_CERTBOT_ENABLED=true` only when the restricted helper is installed. Nginx files and certificates use the configured local directories; system paths are used in PROD.

## Nginx Configuration

For zero-downtime JAR deployments with systemd, Gatekeeperd's proxy vhost must use the
stable `gatekeeperd_backend` upstream. The blue/green deployment setup keeps the upstream
definition in `/etc/nginx/conf.d/gatekeeperd-upstream.conf` and changes its localhost port
only after the candidate slot passes readiness and stability checks. See the
[systemd blue/green setup](staging-deployment.md#one-time-systemd-bluegreen-setup) before
changing an existing deployment.

Create `/etc/nginx/sites-available/gatekeeperd`:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name gateapi.mikesplore.me;

    # Redirect HTTP to HTTPS
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name gateapi.mikesplore.me;

    # SSL Configuration (adjust paths to your certificates)
    ssl_certificate /etc/letsencrypt/live/gateapi.mikesplore.me/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/gateapi.mikesplore.me/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN";
    add_header X-Content-Type-Options "nosniff";
    add_header X-XSS-Protection "1; mode=block";

    # Proxy through the stable blue/green upstream managed by deploy.sh.
    location / {
        proxy_pass http://gatekeeperd_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cache_bypass $http_upgrade;
        
        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 600s;
        # Certificate renewal can take several minutes while Let's Encrypt validates the domain.
        proxy_read_timeout 600s;
    }
}
```

## Enable the Site

```bash
# Create symbolic link
sudo ln -s /etc/nginx/sites-available/gatekeeperd /etc/nginx/sites-enabled/

# Test nginx configuration
sudo nginx -t

# Reload nginx
sudo systemctl reload nginx
```

## Verify Setup

```bash
# Test HTTP redirect (should 301 to HTTPS)
curl -i http://gateapi.mikesplore.me/api/health

# Test HTTPS endpoint
curl -i https://gateapi.mikesplore.me/api/health
```

## Firewall

Make sure ports 80 and 443 are open:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

## SSL Certificate with Let's Encrypt

If you don't have an SSL certificate yet:

```bash
# Install certbot
sudo apt install certbot python3-certbot-nginx

# Get certificate
sudo certbot --nginx -d gateapi.mikesplore.me

# Test auto-renewal
sudo certbot renew --dry-run
```

## Common Issues

1. **502 Bad Gateway**: gatekeeperd container is not running or not on port 8080
   ```bash
   docker ps | grep gatekeeperd
   ```

2. **Connection refused**: Check if nginx is running
   ```bash
   sudo systemctl status nginx
   ```

3. **SSL certificate errors**: Verify certificate paths in nginx config
   ```bash
   sudo ls -la /etc/letsencrypt/live/gateapi.mikesplore.me/
   ```

4. **CORS errors**: Ensure your frontend domain is in the CORS allowlist in `Security.kt`
