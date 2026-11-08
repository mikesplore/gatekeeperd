#!/usr/bin/env bash
# Shared prerequisite checks and idempotent host preparation for Gatekeeperd deploy.
# Sourced by deploy.sh and gatekeeperd-auto-deploy; do not execute directly unless testing.

[[ "${BASH_SOURCE[0]}" == "${0}" ]] && {
  echo "Error: Source this file from deploy.sh or gatekeeperd-auto-deploy." >&2
  exit 2
}

# Exit codes (do not record auto-deploy-failed-commit for 10–19)
readonly GK_EXIT_PREREQ=10
readonly GK_EXIT_PREPARE=11

gk_preflight_log() { printf '[preflight] %s\n' "$*"; }
gk_preflight_warn() { printf '[preflight] Warning: %s\n' "$*" >&2; }
gk_preflight_fail_prereq() {
  echo "Error: $*" >&2
  echo "Fix the items above, then run: sudo ${GK_DEPLOY_SCRIPT:-deploy.sh} --check" >&2
  exit "${GK_EXIT_PREREQ}"
}
gk_preflight_fail_prepare() {
  echo "Error: $*" >&2
  exit "${GK_EXIT_PREPARE}"
}

gk_preflight_resolve_slot_template() {
  local base=""
  if [[ -n "${DEPLOY_TOOLS_DIR:-}" && -f "${DEPLOY_TOOLS_DIR}/scripts/gatekeeperd-slot@.service" ]]; then
    echo "${DEPLOY_TOOLS_DIR}/scripts/gatekeeperd-slot@.service"
    return 0
  fi
  if [[ -f "${TARGET_DIR}/gatekeeperd-slot@.service.in" ]]; then
    echo "${TARGET_DIR}/gatekeeperd-slot@.service.in"
    return 0
  fi
  if [[ -n "${GK_DEPLOY_SCRIPT_DIR:-}" && -f "${GK_DEPLOY_SCRIPT_DIR}/scripts/gatekeeperd-slot@.service" ]]; then
    echo "${GK_DEPLOY_SCRIPT_DIR}/scripts/gatekeeperd-slot@.service"
    return 0
  fi
  return 1
}

gk_preflight_resolve_bundle_root() {
  if [[ -n "${DEPLOY_TOOLS_DIR:-}" ]]; then
    echo "${DEPLOY_TOOLS_DIR}"
    return 0
  fi
  if [[ -n "${GK_DEPLOY_SCRIPT_DIR:-}" ]]; then
    echo "${GK_DEPLOY_SCRIPT_DIR}"
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------- prerequisites

gk_preflight_check_binaries() {
  local missing=() tool
  for tool in java gh curl flock systemctl nginx tar sha256sum; do
    command -v "${tool}" >/dev/null 2>&1 || missing+=("${tool}")
  done
  ((${#missing[@]} == 0)) || gk_preflight_fail_prereq "Missing required commands: ${missing[*]}. Install them on the host before deploying."
  if ! java -version >/dev/null 2>&1; then
    gk_preflight_fail_prereq "Java is installed but 'java -version' failed."
  fi
  if ! java -version 2>&1 | grep -qE 'version "21'; then
    gk_preflight_warn "Java 21 is recommended; gatekeeperd CI builds on Java 21."
  fi
}

gk_preflight_check_privileges() {
  if [[ "${EUID}" -ne 0 ]]; then
    gk_preflight_fail_prereq "Deploy preflight must run as root (use sudo)."
  fi
  command -v sudo >/dev/null 2>&1 || gk_preflight_fail_prereq "sudo is required."
}

gk_preflight_check_gh_auth() {
  [[ "${GK_PREFLIGHT_SKIP_GH:-}" == 1 ]] && return 0
  local gh_dir="${GH_CONFIG_DIR:-}"
  if [[ -z "${gh_dir}" && -r /etc/gatekeeperd/auto-deploy.env ]]; then
    # shellcheck disable=SC1091
    source /etc/gatekeeperd/auto-deploy.env
    gh_dir="${GH_CONFIG_DIR:-}"
  fi
  if [[ -n "${gh_dir}" ]]; then
    export GH_CONFIG_DIR="${gh_dir}"
    env "GH_CONFIG_DIR=${gh_dir}" gh auth status --hostname github.com >/dev/null 2>&1 \
      || gk_preflight_fail_prereq "GitHub CLI is not authenticated for root (GH_CONFIG_DIR=${gh_dir}). Run 'gh auth login' as the owning user, then set GH_CONFIG_DIR in /etc/gatekeeperd/auto-deploy.env."
  else
    gh auth status --hostname github.com >/dev/null 2>&1 \
      || gk_preflight_fail_prereq "GitHub CLI is not authenticated. Run 'gh auth login', or set GH_CONFIG_DIR in /etc/gatekeeperd/auto-deploy.env for the user that completed login."
  fi
}

gk_preflight_check_host_config() {
  local env_file="${DEPLOY_APP_ENV_FILE:-/etc/gatekeeperd/gatekeeperd.env}"
  [[ "${env_file}" =~ ^/[A-Za-z0-9_./-]+$ ]] \
    || gk_preflight_fail_prereq "DEPLOY_APP_ENV_FILE must be an absolute path without spaces (set in ${DEPLOY_CONFIG_FILE:-/etc/gatekeeperd/deploy.env})."
  if [[ ! -r "${env_file}" ]]; then
    gk_preflight_fail_prereq "Application environment file is missing: ${env_file}. Copy .env.example to this path and configure database, Redis, and secrets."
  fi
  local user="${DEPLOY_SERVICE_USER:-$(systemctl show -p User --value "${SERVICE_NAME:-gatekeeperd}" 2>/dev/null)}"
  user="${user:-root}"
  [[ "${user}" =~ ^[A-Za-z_][A-Za-z0-9_.-]*$ ]] || gk_preflight_fail_prereq "Could not resolve a valid service user for ${SERVICE_NAME:-gatekeeperd}."
  sudo -u "${user}" test -r "${env_file}" \
    || gk_preflight_fail_prereq "${env_file} is not readable by service user '${user}'. Adjust ownership or group permissions."

  [[ -n "${GATEWAY_URL:-}" ]] \
    || gk_preflight_fail_prereq "DEPLOY_GATEWAY_URL is not set. Edit ${DEPLOY_CONFIG_FILE:-/etc/gatekeeperd/deploy.env} (see ${TARGET_DIR:-/opt/gatekeeperd}/deploy.env.example)."

  [[ "${BLUE_PORT:-18081}" != "${GREEN_PORT:-18082}" && "${LEGACY_PORT:-8080}" != "${BLUE_PORT:-18081}" && "${LEGACY_PORT:-8080}" != "${GREEN_PORT:-18082}" ]] \
    || gk_preflight_fail_prereq "Blue, green, and legacy ports must be distinct."

  local svc="/etc/systemd/system/${SERVICE_NAME:-gatekeeperd}.service"
  [[ -f "${svc}" ]] \
    || gk_preflight_warn "${svc} not found; first blue/green bootstrap may require a legacy gatekeeperd.service."

  if ! sudo nginx -t >/dev/null 2>&1; then
    gk_preflight_fail_prereq "Nginx configuration is invalid. Run 'sudo nginx -t' and fix errors before deploying."
  fi

  local dump
  dump="$(sudo nginx -T 2>&1)" || gk_preflight_fail_prereq "Could not read nginx configuration (nginx -T failed)."
  local upstream_file="${ACTIVE_UPSTREAM_FILE:-/etc/nginx/conf.d/gatekeeperd-upstream.conf}"
  if ! grep -Fq "${upstream_file}" <<<"${dump}" \
      || ! grep -Fq "proxy_pass http://${GATEWAY_UPSTREAM:-gatekeeperd_backend}" <<<"${dump}"; then
    gk_preflight_fail_prereq "Gatekeeperd nginx vhost must include ${upstream_file} in http {} and use proxy_pass http://${GATEWAY_UPSTREAM:-gatekeeperd_backend};. See docs/staging-deployment.md."
  fi
}

# ---------------------------------------------------------------- prepare

gk_preflight_install_deploy_env_template() {
  local dest="/etc/gatekeeperd/deploy.env" example=""
  sudo install -d -o root -g root -m 0755 /etc/gatekeeperd
  if [[ -f "${dest}" ]]; then
    return 0
  fi
  for example in \
    "${TARGET_DIR}/deploy.env.example" \
    "${GK_DEPLOY_SCRIPT_DIR}/scripts/deploy.env.example" \
    "${GK_DEPLOY_SCRIPT_DIR}/deploy.env.example"; do
    if [[ -f "${example}" ]]; then
      sudo install -o root -g root -m 0644 "${example}" "${dest}"
      gk_preflight_log "Created ${dest} from template; set DEPLOY_GATEWAY_URL before deploying."
      return 0
    fi
  done
  gk_preflight_warn "deploy.env template not found; create ${dest} manually."
}

gk_preflight_prepare_directories() {
  local td="${TARGET_DIR:-/opt/gatekeeperd}"
  sudo install -d -o root -g root -m 0755 "${td}" "${td}/releases" "${td}/slots/blue" "${td}/slots/green"
  sudo install -d -o root -g root -m 0755 "${SLOT_ENV_DIR:-/etc/gatekeeperd/slots}"
  sudo install -d -o root -g root -m 0755 "$(dirname "${LOCK_FILE:-/run/lock/gatekeeperd-deploy.lock}")"
  sudo touch "${LOCK_FILE:-/run/lock/gatekeeperd-deploy.lock}" 2>/dev/null || true
  sudo chmod 0644 "${LOCK_FILE:-/run/lock/gatekeeperd-deploy.lock}" 2>/dev/null || true
  sudo install -d -o root -g root -m 0750 "${DEPLOY_DIAGNOSTICS_DIR:-${td}/deployment-attempts}" 2>/dev/null || true
}

gk_preflight_prepare_upstream_stub() {
  local upstream_file="${ACTIVE_UPSTREAM_FILE:-/etc/nginx/conf.d/gatekeeperd-upstream.conf}"
  local legacy_port="${LEGACY_PORT:-8080}"
  local gateway="${GATEWAY_UPSTREAM:-gatekeeperd_backend}"
  sudo install -d -o root -g root -m 0755 "$(dirname "${upstream_file}")"
  if sudo test -f "${upstream_file}"; then
    return 0
  fi
  gk_preflight_log "Creating nginx upstream stub ${upstream_file} (legacy port ${legacy_port})."
  printf 'upstream %s {\n    server 127.0.0.1:%s;\n    keepalive 32;\n}\n' "${gateway}" "${legacy_port}" \
    | sudo tee "${upstream_file}" >/dev/null
  sudo chmod 0644 "${upstream_file}"
}

gk_preflight_install_slot_unit() {
  local template env_file="${DEPLOY_APP_ENV_FILE:-/etc/gatekeeperd/gatekeeperd.env}"
  template="$(gk_preflight_resolve_slot_template)" \
    || gk_preflight_fail_prepare "Blue/green systemd template is missing. Run deploy from a CI artifact or copy scripts/gatekeeperd-slot@.service to ${TARGET_DIR}/gatekeeperd-slot@.service.in."

  local user group supplementary identity=""
  user="${DEPLOY_SERVICE_USER:-$(sudo systemctl show -p User --value "${SERVICE_NAME:-gatekeeperd}")}"
  user="${user:-root}"
  group="${DEPLOY_SERVICE_GROUP:-$(sudo systemctl show -p Group --value "${SERVICE_NAME:-gatekeeperd}")}"
  supplementary="${DEPLOY_SUPPLEMENTARY_GROUPS:-$(sudo systemctl show -p SupplementaryGroups --value "${SERVICE_NAME:-gatekeeperd}")}"

  local rendered
  rendered="$(mktemp)"
  identity="User=${user}"
  [[ -z "${group}" ]] || identity+=$'\n'"Group=${group}"
  [[ -z "${supplementary}" ]] || identity+=$'\n'"SupplementaryGroups=${supplementary}"
  awk -v identity="${identity}" -v env_file="${env_file}" '
    $0 == "@IDENTITY_DIRECTIVES@" { print identity; next }
    { gsub(/@APP_ENV_FILE@/, env_file); print }
  ' "${template}" >"${rendered}"

  if [[ ! -f /etc/systemd/system/gatekeeperd-slot@.service ]]; then
    gk_preflight_log "Installing gatekeeperd-slot@.service"
    sudo install -o root -g root -m 0644 "${rendered}" /etc/systemd/system/gatekeeperd-slot@.service
  else
    gk_preflight_log "gatekeeperd-slot@.service already present (left unchanged)."
  fi
  rm -f "${rendered}"

  printf 'PORT=%s\n' "${BLUE_PORT:-18081}" | sudo tee "${SLOT_ENV_DIR:-/etc/gatekeeperd/slots}/blue.env" >/dev/null
  printf 'PORT=%s\n' "${GREEN_PORT:-18082}" | sudo tee "${SLOT_ENV_DIR:-/etc/gatekeeperd/slots}/green.env" >/dev/null
  sudo chmod 0644 "${SLOT_ENV_DIR:-/etc/gatekeeperd/slots}/blue.env" "${SLOT_ENV_DIR:-/etc/gatekeeperd/slots}/green.env"
  sudo systemctl daemon-reload
}

gk_preflight_sync_operator_scripts() {
  local root bundle td="${TARGET_DIR:-/opt/gatekeeperd}"
  bundle="$(gk_preflight_resolve_bundle_root)" || return 0
  sudo install -d -o root -g root -m 0755 "${td}/scripts"
  if [[ -f "${bundle}/deploy.sh" ]]; then
    sudo install -o root -g root -m 0755 "${bundle}/deploy.sh" "${td}/deploy.sh"
  fi
  if [[ -f "${bundle}/scripts/gatekeeperd-auto-deploy" ]]; then
    sudo install -o root -g root -m 0755 "${bundle}/scripts/gatekeeperd-auto-deploy" "${td}/gatekeeperd-auto-deploy"
  fi
  if [[ -f "${bundle}/scripts/gatekeeperd-deploy-preflight.sh" ]]; then
    sudo install -o root -g root -m 0755 "${bundle}/scripts/gatekeeperd-deploy-preflight.sh" "${td}/scripts/gatekeeperd-deploy-preflight.sh"
  fi
  if [[ -f "${bundle}/scripts/deploy.env.example" ]]; then
    sudo install -o root -g root -m 0644 "${bundle}/scripts/deploy.env.example" "${td}/deploy.env.example"
  fi
  if [[ -f "${bundle}/scripts/gatekeeperd-slot@.service" ]]; then
    sudo install -o root -g root -m 0644 "${bundle}/scripts/gatekeeperd-slot@.service" "${td}/gatekeeperd-slot@.service.in"
  fi
}

gk_preflight_prepare_systemd_timer_units() {
  local bundle
  bundle="$(gk_preflight_resolve_bundle_root)" || return 0
  for unit in gatekeeperd-auto-deploy.service gatekeeperd-auto-deploy.timer; do
    local src="${bundle}/scripts/${unit}"
    [[ -f "${src}" ]] || continue
    if [[ ! -f "/etc/systemd/system/${unit}" ]]; then
      gk_preflight_log "Installing ${unit}"
      sudo install -o root -g root -m 0644 "${src}" "/etc/systemd/system/${unit}"
    fi
  done
  sudo systemctl daemon-reload 2>/dev/null || true
}

gk_preflight_prepare_host() {
  gk_preflight_log "Preparing host layout (idempotent)..."
  gk_preflight_prepare_directories
  gk_preflight_sync_operator_scripts
  gk_preflight_install_deploy_env_template
  # Re-load deploy settings if we just created deploy.env
  if [[ -r /etc/gatekeeperd/deploy.env ]]; then
    # shellcheck disable=SC1091
    source /etc/gatekeeperd/deploy.env
  fi
  gk_preflight_prepare_upstream_stub
  gk_preflight_install_slot_unit
  gk_preflight_prepare_systemd_timer_units
  gk_preflight_log "Host preparation finished."
}

# ---------------------------------------------------------------- public

gk_preflight_run() {
  [[ "${GK_PREFLIGHT_DONE:-}" == 1 ]] && return 0
  gk_preflight_check_privileges
  gk_preflight_check_binaries
  gk_preflight_check_gh_auth
  gk_preflight_prepare_host
  if [[ -r "${DEPLOY_CONFIG_FILE:-/etc/gatekeeperd/deploy.env}" ]]; then
    # shellcheck disable=SC1090
    source "${DEPLOY_CONFIG_FILE:-/etc/gatekeeperd/deploy.env}"
  fi
  gk_preflight_check_host_config
  gk_preflight_log "Preflight complete; host is ready to deploy."
  export GK_PREFLIGHT_DONE=1
}
