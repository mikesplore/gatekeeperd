# Nginx Reverse Proxy Configuration

If you want to expose gatekeeperd behind nginx with SSL, use this configuration.

## Prerequisites
- nginx installed on your VPS
- SSL certificate for your domain (e.g., via Let's Encrypt)
- gatekeeperd container running on port 8080

## Nginx Configuration

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

    # Proxy to gatekeeperd container
    location / {
        proxy_pass http://localhost:8080;
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
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
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