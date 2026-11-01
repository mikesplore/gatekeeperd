#!/usr/bin/env bash
# Provision a Gatekeeperd VPS and perform its first blue/green deployment.
# --reset-runtime removes Gatekeeperd-owned runtime/configuration while preserving
# only the application env file and external PostgreSQL/Redis data.
set -euo pipefail

REPOSITORY="mikesplore/gatekeeperd"
WORKFLOW="build.yml"
ARTIFACT_NAME="gatekeeperd-all-jar"
SCRIPT_URL="https://raw.githubusercontent.com/mikesplore/gatekeeperd/main/deploy.sh"
TARGET_DIR="/opt/gatekeeperd"
ENV_FILE="${GATEKEEPERD_ENV_FILE:-/etc/gatekeeperd/gatekeeperd.env}"
DEPLOY_ENV_FILE="${DEPLOY_CONFIG_FILE:-/etc/gatekeeperd/deploy.env}"
NGINX_UPSTREAM_FILE="/etc/nginx/conf.d/gatekeeperd-upstream.conf"
NGINX_UPSTREAM_INCLUDE_FILE="/etc/nginx/conf.d/gatekeeperd-upstreams.conf"
NGINX_SITE_FILE="${GATEKEEPERD_NGINX_SITE_FILE:-/etc/nginx/sites-available/gatekeeperd}"
NGINX_SITE_LINK="/etc/nginx/sites-enabled/gatekeeperd"
SERVICE_USER="${GATEKEEPERD_SERVICE_USER:-$(id -un)}"
DOMAIN="${GATEKEEPERD_DOMAIN:-}"
EMAIL="${GATEKEEPERD_EMAIL:-}"
RUN_ID="${GATEKEEPERD_RUN_ID:-}"
ENABLE_TLS=true
RESET_RUNTIME=false
SOAK_SECONDS="${DEPLOY_SOAK_SECONDS:-30}"
WORK_DIR=""
BACKUP_DIR=""

log() { printf '[%s] ==> %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf 'Warning: %s\n' "$*" >&2; }
die() { printf 'Error: %s\n' "$*" >&2; exit 1; }

as_root() {
  if (( EUID == 0 )); then "$@"; else sudo "$@"; fi
}

as_service_user() {
  if (( EUID == 0 )); then runuser -u "${SERVICE_USER}" -- "$@"; else sudo -u "${SERVICE_USER}" -- "$@"; fi
}

usage() {
  cat <<'EOF'
Usage: prepare-vps.sh [--domain HOSTNAME] [options]

Prepares an Ubuntu/Debian host, starts a bootstrap instance on port 8080,
checks database/Redis readiness, and runs the latest successful CI deployment.

Options:
  --domain HOSTNAME       Public API hostname (default: from GATEKEEPER_PUBLIC_URL)
  --email ADDRESS         Let's Encrypt contact (default: support/admin email in env file)
  --env-file PATH         Existing app env file (default /etc/gatekeeperd/gatekeeperd.env)
  --service-user USER     Existing Linux service account (default current user)
  --run-id ID             Successful workflow run to deploy (default latest success on main)
  --nginx-site PATH       Managed Nginx site file (default /etc/nginx/sites-available/gatekeeperd)
  --reset-runtime         Remove Gatekeeperd runtime, service, deploy, and Nginx files before preparing
  --no-tls                Configure HTTP only; use only for temporary testing
  -h, --help              Show this help

The script needs an existing app env file and GitHub CLI authentication with access
to workflow artifacts. It does not install, reset, or modify PostgreSQL/Redis data.
EOF
}

while (($#)); do
  case "$1" in
    --domain) [[ $# -ge 2 ]] || die "--domain requires a hostname."; DOMAIN="$2"; shift 2 ;;
    --email) [[ $# -ge 2 ]] || die "--email requires an address."; EMAIL="$2"; shift 2 ;;
    --env-file) [[ $# -ge 2 ]] || die "--env-file requires a path."; ENV_FILE="$2"; shift 2 ;;
    --service-user) [[ $# -ge 2 ]] || die "--service-user requires a username."; SERVICE_USER="$2"; shift 2 ;;
    --run-id) [[ $# -ge 2 ]] || die "--run-id requires an ID."; RUN_ID="$2"; shift 2 ;;
    --nginx-site) [[ $# -ge 2 ]] || die "--nginx-site requires a path."; NGINX_SITE_FILE="$2"; shift 2 ;;
    --reset-runtime) RESET_RUNTIME=true; shift ;;
    --no-tls) ENABLE_TLS=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "Unknown option: $1" ;;
  esac
done

[[ "${ENV_FILE}" =~ ^/[A-Za-z0-9_./-]+$ ]] || die "The env file path must be absolute and contain no spaces."
[[ "${DEPLOY_ENV_FILE}" =~ ^/[A-Za-z0-9_./-]+$ ]] || die "The deploy env file path must be absolute and contain no spaces."
[[ "${NGINX_SITE_FILE}" =~ ^/[A-Za-z0-9_./-]+$ ]] || die "The Nginx site path must be absolute and contain no spaces."
[[ "${SERVICE_USER}" =~ ^[A-Za-z_][A-Za-z0-9_.-]*$ ]] || die "Invalid service username."
[[ "${RUN_ID}" == "" || "${RUN_ID}" =~ ^[0-9]+$ ]] || die "Workflow run ID must be numeric."
[[ "${SOAK_SECONDS}" =~ ^[0-9]+$ ]] && (( SOAK_SECONDS > 0 )) || die "DEPLOY_SOAK_SECONDS must be a positive integer."

[[ -r /etc/os-release ]] || die "Cannot identify the operating system."
# shellcheck disable=SC1091
source /etc/os-release
[[ "${ID:-}" == ubuntu || "${ID:-}" == debian ]] || die "This provisioner supports Ubuntu and Debian hosts."
[[ -f "${ENV_FILE}" ]] || die "App env file not found: ${ENV_FILE}. Create it with database, Redis, JWT, and payment settings first."

env_value() {
  python3 - "$1" "${ENV_FILE}" <<'PY'
import sys
key, path = sys.argv[1:]
value = ""
with open(path, encoding="utf-8") as source:
    for line in source:
        stripped = line.strip()
        if stripped.startswith(key + "="):
            value = stripped.split("=", 1)[1].strip()
if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
    value = value[1:-1]
print(value)
PY
}

if [[ -z "${DOMAIN}" ]]; then
  DOMAIN="$(python3 - "$(env_value GATEKEEPER_PUBLIC_URL)" <<'PY'
import sys
from urllib.parse import urlsplit
print(urlsplit(sys.argv[1]).hostname or "")
PY
  )"
fi
if [[ -z "${EMAIL}" ]]; then EMAIL="$(env_value SUPPORT_CONTACT_EMAIL)"; fi
if [[ -z "${EMAIL}" ]]; then EMAIL="$(env_value ADMIN_EMAIL)"; fi
[[ "${DOMAIN}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "Set GATEKEEPER_PUBLIC_URL in ${ENV_FILE} or pass --domain HOSTNAME."
[[ "${ENABLE_TLS}" != true || "${EMAIL}" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || die "Add SUPPORT_CONTACT_EMAIL or ADMIN_EMAIL to ${ENV_FILE}, or pass --email ADDRESS."

id "${SERVICE_USER}" >/dev/null 2>&1 || die "Service account does not exist: ${SERVICE_USER}."
command -v sudo >/dev/null 2>&1 || (( EUID == 0 )) || die "Run as root or install/configure sudo for this account."
if ! command -v curl >/dev/null 2>&1 || ! command -v gh >/dev/null 2>&1 || ! command -v setfacl >/dev/null 2>&1; then
  log "Installing curl, GitHub CLI, and host permission prerequisites..."
  as_root apt-get update -qq
  as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y curl gh acl
fi
command -v gh >/dev/null 2>&1 || die "GitHub CLI could not be installed from the configured Debian/Ubuntu repositories. Install gh, then rerun."
gh auth status --hostname github.com >/dev/null 2>&1 || die "GitHub CLI is not authenticated. Run 'gh auth login' with Actions artifact read access, then rerun."
if as_root systemctl is-active --quiet gatekeeperd.service && [[ "${RESET_RUNTIME}" != true ]]; then
  die "gatekeeperd.service is already active. This is a first-install/reset tool; use deploy.sh for an upgrade, or pass --reset-runtime to replace the app runtime."
fi
for key in DB_URL DB_USER DB_PASSWORD REDIS_HOST JWT_SECRET; do
  grep -Eq "^[[:space:]]*${key}=.+$" "${ENV_FILE}" || die "Required setting ${key} is missing from ${ENV_FILE}."
done

APP_UPSTREAM_DIR="$(env_value NGINX_UPSTREAM_INCLUDE_DIR)"
APP_UPSTREAM_DIR="${APP_UPSTREAM_DIR:-/etc/nginx/conf.d/gatekeeperd-upstreams}"
[[ "${APP_UPSTREAM_DIR}" =~ ^/[A-Za-z0-9_./-]+$ ]] || die "NGINX_UPSTREAM_INCLUDE_DIR must be an absolute path without spaces or shell metacharacters."
APP_NGINX_BINARY="$(env_value NGINX_BINARY)"
APP_NGINX_BINARY="${APP_NGINX_BINARY:-/usr/sbin/nginx}"
[[ "${APP_NGINX_BINARY}" =~ ^/[A-Za-z0-9_./-]+$ ]] || die "NGINX_BINARY must be an absolute executable path."
SYSTEMCTL_BINARY="$(command -v systemctl)"
if [[ "${ENABLE_TLS}" == true ]]; then
  EXPECTED_URL="https://${DOMAIN}"
else
  EXPECTED_URL="http://${DOMAIN}"
fi

# The operator supplies infrastructure credentials; deployment-specific app
# settings are derived from the chosen hostname and managed here.
as_root python3 - "${ENV_FILE}" "${EXPECTED_URL}" "${DOMAIN}" <<'PY'
import os, stat, sys, tempfile
path, public_url, domain = sys.argv[1:]
updates = {
    "GATEKEEPER_PUBLIC_URL": public_url,
    "GATEKEEPERD_ENVIRONMENT": "PROD",
    "GATEKEEPERD_SELF_DOMAIN": domain,
    "GATEKEEPERD_NGINX_HOST_SUDO": "false",
    "GATEKEEPERD_NGINX_CERTBOT_ENABLED": "false",
    "NGINX_DEPLOYMENT_UPSTREAMS_ENABLED": "true",
}
metadata = os.stat(path)
with open(path, encoding="utf-8") as source:
    lines = source.readlines()
output, written = [], set()
for line in lines:
    key = line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else ""
    if key not in updates:
        output.append(line)
    elif key not in written:
        output.append(f"{key}={updates[key]}\n")
        written.add(key)
for key, value in updates.items():
    if key not in written:
        output.append(f"{key}={value}\n")
fd, temporary = tempfile.mkstemp(prefix=".gatekeeperd-env-", dir=os.path.dirname(path))
try:
    with os.fdopen(fd, "w", encoding="utf-8") as target:
        target.writelines(output)
    os.chmod(temporary, stat.S_IMODE(metadata.st_mode))
    os.chown(temporary, metadata.st_uid, metadata.st_gid)
    os.replace(temporary, path)
except BaseException:
    try: os.unlink(temporary)
    except FileNotFoundError: pass
    raise
PY
log "Configured production URL and Nginx settings in ${ENV_FILE}."
as_root setfacl -m "u:${SERVICE_USER}:r" "${ENV_FILE}"
as_service_user test -r "${ENV_FILE}" 2>/dev/null || die "The service account ${SERVICE_USER} cannot read ${ENV_FILE} after configuration."

if command -v systemctl >/dev/null 2>&1 && as_root systemctl is-active --quiet nginx; then
  ACTIVE_PORT="$(as_root grep -Eo 'server[[:space:]]+127\.0\.0\.1:[0-9]+' "${NGINX_UPSTREAM_FILE}" 2>/dev/null | head -n1 | grep -Eo '[0-9]+$' || true)"
  if [[ "${ACTIVE_PORT}" == 18081 || "${ACTIVE_PORT}" == 18082 ]]; then
    if [[ "${RESET_RUNTIME}" != true ]]; then
      die "Nginx already routes to blue/green slot ${ACTIVE_PORT}. Use deploy.sh for upgrades; this script is only for first install or runtime reset."
    fi
  fi
fi

WORK_DIR="$(mktemp -d)"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="/var/backups/gatekeeperd-prepare-${TIMESTAMP}"
cleanup() { [[ -z "${WORK_DIR}" ]] || rm -rf -- "${WORK_DIR}"; }
trap cleanup EXIT

backup_file() {
  local path="$1"
  if as_root test -e "${path}"; then
    as_root install -d -o root -g root -m 0700 "${BACKUP_DIR}$(dirname "${path}")"
    as_root cp -a "${path}" "${BACKUP_DIR}${path}"
  fi
}

write_root_file() {
  local source="$1" destination="$2" mode="$3"
  backup_file "${destination}"
  as_root install -D -o root -g root -m "${mode}" "${source}" "${destination}"
}

update_deploy_setting() {
  local key="$1" value="$2" file_tmp="${WORK_DIR}/deploy.env"
  if as_root test -f "${DEPLOY_ENV_FILE}"; then
    as_root cat "${DEPLOY_ENV_FILE}" >"${file_tmp}"
  else
    : >"${file_tmp}"
  fi
  if grep -qE "^[[:space:]]*${key}=" "${file_tmp}"; then
    sed -i -E "s|^[[:space:]]*${key}=.*$|${key}=${value}|" "${file_tmp}"
  else
    printf '%s=%s\n' "${key}" "${value}" >>"${file_tmp}"
  fi
  write_root_file "${file_tmp}" "${DEPLOY_ENV_FILE}" 0644
}

log "Checking Nginx and Docker configuration..."
if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q 'version "21'; then
  log "Installing Java 21 runtime..."
  as_root apt-get update -qq
  as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless
fi
if ! command -v nginx >/dev/null 2>&1; then
  log "Installing Nginx..."
  as_root apt-get update -qq
  as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
fi
if ! command -v docker >/dev/null 2>&1; then
  log "Installing Docker Engine..."
  as_root apt-get update -qq
  as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io
fi
if [[ "${ENABLE_TLS}" == true ]] && ! command -v certbot >/dev/null 2>&1; then
  log "Installing Certbot..."
  as_root apt-get update -qq
  as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y certbot python3-certbot-nginx
fi
as_root systemctl enable --now nginx docker

if getent group docker >/dev/null 2>&1; then
  as_root usermod -aG docker "${SERVICE_USER}"
fi
if getent group systemd-journal >/dev/null 2>&1; then
  as_root usermod -aG systemd-journal "${SERVICE_USER}"
fi
as_root install -d -o root -g root -m 0755 /opt/gatekeeperd /etc/gatekeeperd/slots /etc/nginx/conf.d /etc/nginx/sites-available /etc/nginx/sites-enabled "${APP_UPSTREAM_DIR}"
as_root install -d -o root -g root -m 0700 "${BACKUP_DIR}"
as_root setfacl -m "u:${SERVICE_USER}:rwx" /etc/nginx/sites-available /etc/nginx/sites-enabled
as_root setfacl -m "d:u:${SERVICE_USER}:rwx" /etc/nginx/sites-available /etc/nginx/sites-enabled
as_root setfacl -m "u:${SERVICE_USER}:rwx" "${APP_UPSTREAM_DIR}"
as_root setfacl -m "d:u:${SERVICE_USER}:rwx" "${APP_UPSTREAM_DIR}"

if [[ "${RESET_RUNTIME}" == true ]]; then
  log "Removing Gatekeeperd runtime, service/deploy settings, managed Nginx files, and related sudo rules; preserving only ${ENV_FILE} and external database/Redis data..."
  as_root systemctl disable --now gatekeeperd-auto-deploy.timer >/dev/null 2>&1 || true
  as_root systemctl disable --now gatekeeperd-slot@blue.service gatekeeperd-slot@green.service >/dev/null 2>&1 || true
  as_root systemctl disable --now gatekeeperd.service >/dev/null 2>&1 || true
  as_root find "${TARGET_DIR}" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  as_root find /etc/systemd/system -maxdepth 1 -name 'gatekeeperd*' -type f -delete
  as_root find /etc/systemd/system -maxdepth 1 -name 'gatekeeperd*' -type l -delete
  as_root rm -f /etc/systemd/system/gatekeeperd.service /etc/systemd/system/gatekeeperd-slot@.service \
    /etc/systemd/system/gatekeeperd-auto-deploy.service /etc/systemd/system/gatekeeperd-auto-deploy.timer \
    /usr/local/sbin/gatekeeperd-certbot /etc/sudoers.d/gatekeeperd-certbot \
    "${NGINX_SITE_FILE}" "${NGINX_SITE_LINK}" "${NGINX_UPSTREAM_FILE}" "${NGINX_UPSTREAM_INCLUDE_FILE}"
  as_root rm -f /etc/sudoers.d/gatekeeperd*
  for site in /etc/nginx/sites-available/* /etc/nginx/sites-enabled/*; do
    [[ -e "${site}" ]] || continue
    if as_root grep -Fq "${DOMAIN}" "${site}" && as_root grep -Eq 'server_name[[:space:]]' "${site}"; then
      site_target="$(as_root readlink -f "${site}" 2>/dev/null || true)"
      as_root rm -f -- "${site}"
      if [[ "${site_target}" == /etc/nginx/sites-available/* ]]; then
        as_root rm -f -- "${site_target}"
      fi
    fi
  done
  as_root find /etc/nginx/conf.d -maxdepth 1 -name 'gatekeeperd*.conf' -type f -delete
  as_root find /etc/nginx/sites-available /etc/nginx/sites-enabled -maxdepth 1 -name 'gatekeeperd*' -delete
  as_root rm -rf -- "${APP_UPSTREAM_DIR}"
  as_root find /var/backups -maxdepth 1 -type d -name 'gatekeeperd-*' -exec rm -rf -- {} + 2>/dev/null || true
  if as_root test -d /etc/gatekeeperd; then
    as_root find /etc/gatekeeperd -mindepth 1 -maxdepth 1 ! -samefile "${ENV_FILE}" -exec rm -rf -- {} +
  fi
  as_root install -d -o root -g root -m 0755 /etc/gatekeeperd/slots
  if command -v certbot >/dev/null 2>&1 && as_root test -d /etc/letsencrypt/renewal; then
    cert_names="$(as_root python3 - "${DOMAIN}" /etc/letsencrypt/renewal <<'PY'
import configparser, glob, os, sys
domain, directory = sys.argv[1:]
for path in glob.glob(os.path.join(directory, "*.conf")):
    parser = configparser.ConfigParser()
    try:
        parser.read(path)
        raw_domains = parser.get("renewalparams", "domains", fallback="")
        domains = [item.strip().lower() for item in raw_domains.split(",")]
        if domains == [domain.lower()]:
            print(os.path.basename(path)[:-5])
    except (configparser.Error, OSError):
        continue
PY
    )"
    while IFS= read -r cert_name; do
      [[ -n "${cert_name}" ]] || continue
      log "Removing Gatekeeperd TLS certificate ${cert_name}..."
      as_root certbot delete --non-interactive --cert-name "${cert_name}"
    done <<<"${cert_names}"
  fi
  as_root systemctl daemon-reload
fi

# Reset removes the old app-owned include directory and its ACLs. Recreate it
# after cleanup so the fresh service can manage client-site upstreams.
as_root install -d -o root -g root -m 0755 "${APP_UPSTREAM_DIR}"
as_root setfacl -m "u:${SERVICE_USER}:rwx" "${APP_UPSTREAM_DIR}"
as_root setfacl -m "d:u:${SERVICE_USER}:rwx" "${APP_UPSTREAM_DIR}"

cat >"${WORK_DIR}/gatekeeperd-nginx.sudoers" <<EOF
# Managed by Gatekeeperd prepare-vps.sh. Allows only config inspection/test and reload.
${SERVICE_USER} ALL=(root) NOPASSWD: ${APP_NGINX_BINARY} -T, ${APP_NGINX_BINARY} -t, ${SYSTEMCTL_BINARY} reload nginx, /bin/systemctl reload nginx, /usr/bin/systemctl reload nginx
EOF
as_root visudo -cf "${WORK_DIR}/gatekeeperd-nginx.sudoers"
write_root_file "${WORK_DIR}/gatekeeperd-nginx.sudoers" /etc/sudoers.d/gatekeeperd-nginx 0440
as_root visudo -c

if [[ -e "${NGINX_SITE_FILE}" ]]; then
  as_root install -d -o root -g root -m 0700 "${BACKUP_DIR}/etc/nginx/sites-available"
  as_root cp -a "${NGINX_SITE_FILE}" "${BACKUP_DIR}/etc/nginx/sites-available/$(basename "${NGINX_SITE_FILE}")"
fi
if as_root test -e "${NGINX_UPSTREAM_FILE}"; then
  as_root install -d -o root -g root -m 0700 "${BACKUP_DIR}/etc/nginx/conf.d"
  as_root cp -a "${NGINX_UPSTREAM_FILE}" "${BACKUP_DIR}/etc/nginx/conf.d/gatekeeperd-upstream.conf"
fi
backup_file "${NGINX_UPSTREAM_INCLUDE_FILE}"

for site in /etc/nginx/sites-enabled/*; do
  [[ -e "${site}" ]] || continue
  [[ "$(readlink -m "${site}")" == "$(readlink -m "${NGINX_SITE_LINK}")" ]] && continue
  if as_root grep -Fq "${DOMAIN}" "${site}" && as_root grep -Eq 'server_name[[:space:]]' "${site}"; then
    die "Nginx site ${site} already references ${DOMAIN}. Back it up/remove the duplicate or pass the existing site file with --nginx-site."
  fi
done

cat >"${WORK_DIR}/upstream.conf" <<'EOF'
upstream gatekeeperd_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}
EOF
write_root_file "${WORK_DIR}/upstream.conf" "${NGINX_UPSTREAM_FILE}" 0644
cat >"${WORK_DIR}/upstream-includes.conf" <<EOF
# Managed by Gatekeeperd prepare-vps.sh
include ${APP_UPSTREAM_DIR}/*.conf;
EOF
write_root_file "${WORK_DIR}/upstream-includes.conf" "${NGINX_UPSTREAM_INCLUDE_FILE}" 0644

cat >"${WORK_DIR}/site.conf" <<EOF
# Managed by Gatekeeperd prepare-vps.sh
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        proxy_pass http://gatekeeperd_backend;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_connect_timeout 60s;
        proxy_send_timeout 600s;
        proxy_read_timeout 600s;
    }
}
EOF
write_root_file "${WORK_DIR}/site.conf" "${NGINX_SITE_FILE}" 0644
as_root ln -sfn "${NGINX_SITE_FILE}" "${NGINX_SITE_LINK}"

update_deploy_setting DEPLOY_APP_ENV_FILE "${ENV_FILE}"
update_deploy_setting DEPLOY_GATEWAY_URL "${EXPECTED_URL}"
update_deploy_setting DEPLOY_SOAK_SECONDS "${SOAK_SECONDS}"
log "Validating Nginx site and upstream..."
as_root nginx -t
as_root systemctl reload nginx
if [[ "${ENABLE_TLS}" == true ]]; then
  log "Requesting or installing the TLS certificate for ${DOMAIN}..."
  as_root certbot --nginx --non-interactive --agree-tos --keep-until-expiring --redirect \
    --email "${EMAIL}" -d "${DOMAIN}"
  as_root nginx -t
  as_root systemctl reload nginx
fi
if command -v ufw >/dev/null 2>&1 && as_root ufw status | grep -q 'Status: active'; then
  as_root ufw allow 80/tcp
  as_root ufw allow 443/tcp
fi

if [[ -z "${RUN_ID}" ]]; then
  log "Finding latest successful ${WORKFLOW} run on main..."
  RUN_ID="$(gh run list --repo "${REPOSITORY}" --workflow "${WORKFLOW}" --branch main --status success --limit 1 --json databaseId,headSha --jq '.[0] | [.databaseId, .headSha] | @tsv' | awk -F '\t' '{print $1}')"
fi
[[ "${RUN_ID}" =~ ^[0-9]+$ ]] || die "No successful ${WORKFLOW} workflow run was found."
log "Downloading successful workflow artifact ${RUN_ID}..."
gh run download "${RUN_ID}" --repo "${REPOSITORY}" --name "${ARTIFACT_NAME}" --dir "${WORK_DIR}/artifact"
[[ -s "${WORK_DIR}/artifact/gatekeeperd-all.jar" && -s "${WORK_DIR}/artifact/gatekeeperd-all.jar.sha256" ]] || die "The workflow artifact is missing the JAR or checksum."
expected_sha="$(awk 'NR == 1 {print $1}' "${WORK_DIR}/artifact/gatekeeperd-all.jar.sha256")"
actual_sha="$(sha256sum "${WORK_DIR}/artifact/gatekeeperd-all.jar" | awk '{print $1}')"
[[ "${expected_sha}" == "${actual_sha}" ]] || die "Downloaded JAR checksum did not match."

log "Installing bootstrap JAR and service unit..."
as_root install -o root -g root -m 0755 "${WORK_DIR}/artifact/gatekeeperd-all.jar" "${TARGET_DIR}/gatekeeperd-all.jar"
primary_group="$(id -gn "${SERVICE_USER}")"
supplementary_groups=""
if id -nG "${SERVICE_USER}" | tr ' ' '\n' | grep -qx docker; then supplementary_groups="SupplementaryGroups=docker"; fi
cat >"${WORK_DIR}/gatekeeperd.service" <<EOF
[Unit]
Description=Gatekeeperd bootstrap service
Wants=network-online.target
After=network-online.target docker.service

[Service]
Type=simple
User=${SERVICE_USER}
Group=${primary_group}
${supplementary_groups}
WorkingDirectory=${TARGET_DIR}
EnvironmentFile=${ENV_FILE}
ExecStart=/usr/bin/java -jar ${TARGET_DIR}/gatekeeperd-all.jar -port=8080
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
write_root_file "${WORK_DIR}/gatekeeperd.service" /etc/systemd/system/gatekeeperd.service 0644
as_root systemctl daemon-reload
as_root systemctl enable --now gatekeeperd.service

wait_for_readiness() {
  local start="$SECONDS" last_report="$SECONDS" status="000" body=""
  local url="http://127.0.0.1:8080/api/health/ready"
  log "Waiting for bootstrap readiness at ${url}..."
  while (( SECONDS - start < 180 )); do
    body="${WORK_DIR}/readiness.json"
    status="$(curl --silent --max-time 3 --output "${body}" --write-out '%{http_code}' "${url}" 2>/dev/null || true)"
    if [[ "${status}" == 200 ]]; then
      log "Bootstrap service is ready: $(cat "${body}")"
      return 0
    fi
    if (( SECONDS - last_report >= 10 )); then
      log "Waiting for database, Redis, and migrations ($((SECONDS - start))s elapsed; HTTP ${status:-000})$( [[ -s "${body}" ]] && printf ': %s' "$(cat "${body}")" )"
      last_report="$SECONDS"
    fi
    sleep 2
  done
  warn "Bootstrap service did not become ready. Inspect: sudo journalctl -u gatekeeperd.service --since '10 minutes ago' --no-pager -n 100"
  [[ ! -s "${body}" ]] || warn "Last readiness response: $(cat "${body}")"
  return 1
}
wait_for_readiness || die "The app environment is not ready; deployment was not started."

log "Downloading the production deploy script and promoting the bootstrap service to blue/green..."
curl --fail --silent --show-error --location "${SCRIPT_URL}" --output "${WORK_DIR}/deploy.sh"
chmod 0755 "${WORK_DIR}/deploy.sh"
bash "${WORK_DIR}/deploy.sh" --run-id "${RUN_ID}" --force
log "VPS preparation and first deployment completed. Backups of replaced Nginx/systemd files are in ${BACKUP_DIR}."
