#!/usr/bin/env bash
# Gatekeeperd deploy and rollback.
#
#   deploy.sh [--run-id ID] [--force]   deploy latest successful build (or a specific run)
#   deploy.sh --rollback [RELEASE|SHA]  switch to previous (or named) local release; no network needed
#   deploy.sh --list                    list local releases (* = current)
#
# Layout under /opt/gatekeeperd:
#   releases/<UTC>-<sha12>/{gatekeeperd-all.jar,gatekeeperd-certbot,commit,jar.sha256}
#   current -> releases/<id>              (atomic symlink switch)
#   gatekeeperd-all.jar -> current/gatekeeperd-all.jar   (path the systemd unit already uses)
#   auto-deploy.hold                      (commit that was rolled back; auto-deploy will not redeploy it)
set -euo pipefail

# Optional, root-owned deployment settings. This file contains paths and the
# public Gatekeeperd origin, never application secrets.
DEPLOY_CONFIG_FILE="${DEPLOY_CONFIG_FILE:-/etc/gatekeeperd/deploy.env}"
if [[ -r "${DEPLOY_CONFIG_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${DEPLOY_CONFIG_FILE}"
fi

REPO="mikesplore/gatekeeperd"
BRANCH="${DEPLOY_BRANCH:-main}"
WORKFLOW="build.yml"
ARTIFACT_NAME="gatekeeperd-all-jar"
TARGET_DIR="/opt/gatekeeperd"
JAR_PATH="${TARGET_DIR}/gatekeeperd-all.jar"
RELEASES_DIR="${TARGET_DIR}/releases"
CURRENT_LINK="${TARGET_DIR}/current"
HOLD_FILE="${TARGET_DIR}/auto-deploy.hold"
SERVICE_NAME="gatekeeperd"
LOCK_FILE="/run/lock/gatekeeperd-deploy.lock"
KEEP_RELEASES="${DEPLOY_KEEP_RELEASES:-5}"
SOAK_SECONDS="${DEPLOY_SOAK_SECONDS:-30}"        # must stay healthy and not restart for this long
NGINX_DRAIN_TIMEOUT_SECONDS="${DEPLOY_NGINX_DRAIN_TIMEOUT_SECONDS:-620}"
PRE_HOOK="${DEPLOY_PRE_HOOK:-}"                  # e.g. a pg_dump command; deploy aborts if it fails
APP_ENV_FILE="${DEPLOY_APP_ENV_FILE:-/etc/gatekeeperd/gatekeeperd.env}"
SLOT_ENV_DIR="${DEPLOY_SLOT_ENV_DIR:-/etc/gatekeeperd/slots}"
SLOT_DIR="${TARGET_DIR}/slots"
ACTIVE_UPSTREAM_FILE="${DEPLOY_NGINX_UPSTREAM_FILE:-/etc/nginx/conf.d/gatekeeperd-upstream.conf}"
GATEWAY_UPSTREAM="gatekeeperd_backend"
LEGACY_PORT="${DEPLOY_LEGACY_PORT:-8080}"
BLUE_PORT="${DEPLOY_BLUE_PORT:-18081}"
GREEN_PORT="${DEPLOY_GREEN_PORT:-18082}"
READY_PATH="${DEPLOY_READY_PATH:-/api/health/ready}"
LIVE_PATH="${DEPLOY_LIVE_PATH:-/api/health/live}"
GATEWAY_URL="${DEPLOY_GATEWAY_URL:-}"
DEPLOY_TOOLS_DIR=""

MODE="deploy"
RUN_ID=""
FORCE=false
ROLLBACK_TARGET=""
TMP_DIR=""
SUDOERS_TMP=""

log() { echo "==> $*"; }
warn() { echo "Warning: $*" >&2; }
die() { echo "Error: $*" >&2; exit 1; }

cleanup() {
  [[ -n "${SUDOERS_TMP}" ]] && rm -f "${SUDOERS_TMP}"
  [[ -n "${TMP_DIR}" ]] && rm -rf "${TMP_DIR}"
  return 0
}
trap cleanup EXIT

usage() {
  echo "Usage: $0 [--run-id ID] [--force] | --rollback [RELEASE|SHA] | --list" >&2
  exit 2
}

# ---------------------------------------------------------------- releases

release_ids() { sudo ls -1 "${RELEASES_DIR}" 2>/dev/null | sort || true; }

current_id() {
  if sudo test -L "${CURRENT_LINK}"; then
    basename "$(sudo readlink "${CURRENT_LINK}")"
  fi
}

release_commit() { sudo cat "${RELEASES_DIR}/$1/commit" 2>/dev/null || true; }

list_releases() {
  local cur id mark
  cur="$(current_id)"
  while IFS= read -r id; do
    [[ -n "${id}" ]] || continue
    mark=" "
    if [[ "${id}" == "${cur}" ]]; then mark="*"; fi
    printf '%s %s  %s\n' "${mark}" "${id}" "$(release_commit "${id}" | cut -c1-12)"
  done < <(release_ids)
}

probe() { curl --fail --silent --show-error --max-time 3 "$1" >/dev/null 2>&1; }

slot_port() {
  case "$1" in
    blue) echo "${BLUE_PORT}" ;;
    green) echo "${GREEN_PORT}" ;;
    *) return 1 ;;
  esac
}

other_slot() { [[ "$1" == blue ]] && echo green || echo blue; }
slot_service() { echo "gatekeeperd-slot@$1.service"; }

slot_for_port() {
  case "$1" in
    "${BLUE_PORT}") echo blue ;;
    "${GREEN_PORT}") echo green ;;
    "${LEGACY_PORT}") echo legacy ;;
    *) return 1 ;;
  esac
}

active_port() {
  sudo grep -Eo 'server[[:space:]]+127\.0\.0\.1:[0-9]+' "${ACTIVE_UPSTREAM_FILE}" 2>/dev/null \
    | head -n1 | grep -Eo '[0-9]+$'
}

gateway_probe_at() {
  [[ -n "${GATEWAY_URL}" ]] || return 1
  local path="$1" origin="${GATEWAY_URL%/}" authority host_port port
  [[ "${origin}" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] || return 1
  authority="${origin#*://}"
  if [[ "${authority}" == *:* ]]; then
    host_port="${authority}"
  elif [[ "${origin}" == https://* ]]; then
    host_port="${authority}:443"
  else
    host_port="${authority}:80"
  fi
  port="${host_port##*:}"
  curl --fail --silent --show-error --max-time 4 \
    --resolve "${host_port}:127.0.0.1" "${origin}${path}" >/dev/null 2>&1
}

gateway_probe() { gateway_probe_at "${READY_PATH}"; }

write_upstream() {
  local port="$1" prior="" had_file=false tmp
  if sudo test -f "${ACTIVE_UPSTREAM_FILE}"; then
    had_file=true
    prior="$(sudo cat "${ACTIVE_UPSTREAM_FILE}")"
  fi
  sudo install -d -o root -g root -m 0755 "$(dirname "${ACTIVE_UPSTREAM_FILE}")"
  tmp="$(sudo mktemp "${ACTIVE_UPSTREAM_FILE}.XXXXXX")"
  printf 'upstream %s {\n    server 127.0.0.1:%s;\n    keepalive 32;\n}\n' "${GATEWAY_UPSTREAM}" "${port}" \
    | sudo tee "${tmp}" >/dev/null
  sudo chmod 0644 "${tmp}"
  sudo mv -f "${tmp}" "${ACTIVE_UPSTREAM_FILE}"
  if ! sudo nginx -t >/dev/null 2>&1; then
    warn "nginx -t rejected upstream port ${port}; restoring the previous upstream."
    if [[ "${had_file}" == true ]]; then printf '%s\n' "${prior}" | sudo tee "${ACTIVE_UPSTREAM_FILE}" >/dev/null
    else sudo rm -f "${ACTIVE_UPSTREAM_FILE}"; fi
    return 1
  fi
  if ! sudo systemctl reload nginx; then
    warn "Nginx reload failed for upstream port ${port}; restoring the previous upstream."
    if [[ "${had_file}" == true ]]; then printf '%s\n' "${prior}" | sudo tee "${ACTIVE_UPSTREAM_FILE}" >/dev/null
    else sudo rm -f "${ACTIVE_UPSTREAM_FILE}"; fi
    sudo nginx -t >/dev/null 2>&1 && sudo systemctl reload nginx || true
    return 1
  fi
}

nginx_has_draining_workers() {
  sudo ps -eo args= | grep -F 'nginx: worker process is shutting down' | grep -qv grep
}

wait_for_nginx_drain() {
  local end=$((SECONDS + NGINX_DRAIN_TIMEOUT_SECONDS))
  while nginx_has_draining_workers; do
    if (( SECONDS >= end )); then
      warn "Nginx still has old workers after ${NGINX_DRAIN_TIMEOUT_SECONDS}s; keeping the retired Gatekeeperd slot running and delaying this deployment."
      return 1
    fi
    sleep 2
  done
}

retire_slot_after_cutover() {
  local slot="$1" service
  service="$(slot_service "${slot}")"
  if wait_for_nginx_drain; then
    sudo systemctl disable --now "${service}" >/dev/null 2>&1 || true
  else
    # Keep the process alive for connections owned by old Nginx workers. It is
    # disabled so systemd will not automatically start it on the next boot.
    sudo systemctl disable "${service}" >/dev/null 2>&1 || true
  fi
}

ensure_proxy_configuration() {
  local dump
  [[ -n "${GATEWAY_URL}" ]] || die "Set DEPLOY_GATEWAY_URL in ${DEPLOY_CONFIG_FILE} to the public Gatekeeperd origin (for example https://gateapi.example.net)."
  [[ "${BLUE_PORT}" != "${GREEN_PORT}" && "${LEGACY_PORT}" != "${BLUE_PORT}" && "${LEGACY_PORT}" != "${GREEN_PORT}" ]] \
    || die "DEPLOY_LEGACY_PORT, DEPLOY_BLUE_PORT, and DEPLOY_GREEN_PORT must be distinct."
  sudo install -d -o root -g root -m 0755 "$(dirname "${ACTIVE_UPSTREAM_FILE}")"
  if ! sudo test -f "${ACTIVE_UPSTREAM_FILE}"; then
    printf 'upstream %s {\n    server 127.0.0.1:%s;\n    keepalive 32;\n}\n' "${GATEWAY_UPSTREAM}" "${LEGACY_PORT}" \
      | sudo tee "${ACTIVE_UPSTREAM_FILE}" >/dev/null
    sudo chmod 0644 "${ACTIVE_UPSTREAM_FILE}"
  fi
  dump="$(sudo nginx -T 2>&1)" || die "Nginx configuration is invalid. Run 'sudo nginx -t' and fix it before deploying."
  if ! grep -Fq "${ACTIVE_UPSTREAM_FILE}" <<<"${dump}" \
      || ! grep -Fq "proxy_pass http://${GATEWAY_UPSTREAM}" <<<"${dump}"; then
    die "Configure Gatekeeperd's Nginx vhost to include ${ACTIVE_UPSTREAM_FILE} in the http context and use 'proxy_pass http://${GATEWAY_UPSTREAM};' before enabling blue/green deployments. The current listener is left untouched."
  fi
}

install_slot_unit() {
  local source rendered user group supplementary identity="" env_file="${APP_ENV_FILE}"
  if [[ -n "${DEPLOY_TOOLS_DIR}" && -f "${DEPLOY_TOOLS_DIR}/scripts/gatekeeperd-slot@.service" ]]; then
    source="${DEPLOY_TOOLS_DIR}/scripts/gatekeeperd-slot@.service"
  else
    source="${TARGET_DIR}/gatekeeperd-slot@.service.in"
  fi
  sudo test -f "${source}" || die "Blue/green systemd template is missing: ${source}. Deploy a build containing gatekeeperd-slot@.service first."
  [[ "${env_file}" =~ ^/[A-Za-z0-9_./-]+$ ]] || die "DEPLOY_APP_ENV_FILE must be an absolute path without spaces or shell metacharacters."
  user="${DEPLOY_SERVICE_USER:-$(sudo systemctl show -p User --value "${SERVICE_NAME}")}"; user="${user:-root}"
  group="${DEPLOY_SERVICE_GROUP:-$(sudo systemctl show -p Group --value "${SERVICE_NAME}")}"
  supplementary="${DEPLOY_SUPPLEMENTARY_GROUPS:-$(sudo systemctl show -p SupplementaryGroups --value "${SERVICE_NAME}")}"
  [[ "${user}" =~ ^[A-Za-z_][A-Za-z0-9_.-]*$ ]] || die "Invalid DEPLOY_SERVICE_USER value."
  [[ -z "${group}" || "${group}" =~ ^[A-Za-z_][A-Za-z0-9_.-]*$ ]] || die "Invalid DEPLOY_SERVICE_GROUP value."
  sudo test -r "${env_file}" && sudo -u "${user}" test -r "${env_file}" \
    || die "Shared application environment file is not readable by ${user}: ${env_file}. Set DEPLOY_APP_ENV_FILE and adjust its read permissions."
  for group_name in ${supplementary}; do
    [[ "${group_name}" =~ ^[A-Za-z_][A-Za-z0-9_.-]*$ ]] || die "Invalid supplementary service group."
  done
  identity="User=${user}"
  [[ -z "${group}" ]] || identity+=$'\n'"Group=${group}"
  [[ -z "${supplementary}" ]] || identity+=$'\n'"SupplementaryGroups=${supplementary}"
  rendered="$(mktemp)"
  awk -v identity="${identity}" -v env_file="${env_file}" '
    $0 == "@IDENTITY_DIRECTIVES@" { print identity; next }
    { gsub(/@APP_ENV_FILE@/, env_file); print }
  ' "${source}" >"${rendered}"
  sudo install -o root -g root -m 0644 "${rendered}" /etc/systemd/system/gatekeeperd-slot@.service
  rm -f "${rendered}"
  sudo install -d -o root -g root -m 0755 "${SLOT_ENV_DIR}" "${SLOT_DIR}/blue" "${SLOT_DIR}/green"
  printf 'PORT=%s\n' "${BLUE_PORT}" | sudo tee "${SLOT_ENV_DIR}/blue.env" >/dev/null
  printf 'PORT=%s\n' "${GREEN_PORT}" | sudo tee "${SLOT_ENV_DIR}/green.env" >/dev/null
  sudo chmod 0644 "${SLOT_ENV_DIR}/blue.env" "${SLOT_ENV_DIR}/green.env"
  sudo systemctl daemon-reload
}

verify_slot_service() {
  local slot="$1" port pid restarts end ok=false service
  port="$(slot_port "${slot}")"
  service="$(slot_service "${slot}")"
  for _ in $(seq 1 60); do
    if sudo systemctl is-active --quiet "${service}" && probe "http://127.0.0.1:${port}${READY_PATH}"; then ok=true; break; fi
    sleep 2
  done
  [[ "${ok}" == true ]] || { echo "Error: ${service} did not become ready on port ${port}." >&2; return 1; }
  pid="$(sudo systemctl show -p MainPID --value "${service}")"
  restarts="$(sudo systemctl show -p NRestarts --value "${service}")"
  end=$((SECONDS + SOAK_SECONDS))
  while (( SECONDS < end )); do
    sleep 2
    if ! sudo systemctl is-active --quiet "${service}" || ! probe "http://127.0.0.1:${port}${READY_PATH}"; then
      echo "Error: ${service} became unready during the ${SOAK_SECONDS}s pre-cutover soak." >&2
      return 1
    fi
    if [[ "$(sudo systemctl show -p MainPID --value "${service}")" != "${pid}" \
       || "$(sudo systemctl show -p NRestarts --value "${service}")" != "${restarts}" ]]; then
      echo "Error: ${service} restarted during its pre-cutover soak." >&2
      return 1
    fi
  done
}

verify_gateway() {
  local end=$((SECONDS + SOAK_SECONDS))
  for _ in $(seq 1 30); do
    gateway_probe && break
    sleep 2
  done
  gateway_probe || { echo "Error: the public Gatekeeperd Nginx route is not ready at ${GATEWAY_URL}${READY_PATH}." >&2; return 1; }
  while (( SECONDS < end )); do
    sleep 2
    gateway_probe || { echo "Error: the Gatekeeperd Nginx route became unhealthy during post-cutover verification." >&2; return 1; }
  done
}

verify_service() {
  local port slot
  port="$(active_port)"
  slot="$(slot_for_port "${port}")" || { echo "Error: Nginx upstream has an unknown Gatekeeperd port: ${port:-unset}." >&2; return 1; }
  if [[ "${slot}" == legacy ]]; then
    sudo systemctl is-active --quiet "${SERVICE_NAME}" \
      && probe "http://127.0.0.1:${port}${LIVE_PATH}" \
      && gateway_probe_at "${LIVE_PATH}"
    return $?
  fi
  sudo systemctl is-active --quiet "$(slot_service "${slot}")" \
    && probe "http://127.0.0.1:${port}${READY_PATH}" && gateway_probe
}

stage_slot_release() {
  local slot="$1" id="$2" service
  service="$(slot_service "${slot}")"
  sudo systemctl stop "${service}" >/dev/null 2>&1 || true
  sudo install -d -o root -g root -m 0755 "${SLOT_DIR}/${slot}"
  sudo ln -sfn "../../releases/${id}" "${SLOT_DIR}/${slot}/current.new"
  sudo mv -Tf "${SLOT_DIR}/${slot}/current.new" "${SLOT_DIR}/${slot}/current"
}

bootstrap_legacy_service() {
  local id="$1" slot=blue port
  [[ "$(slot_for_port "$(active_port)")" == legacy ]] || return 0
  sudo systemctl is-active --quiet "${SERVICE_NAME}" \
    || die "Nginx still routes to ${LEGACY_PORT}, but ${SERVICE_NAME} is not active; refusing to cut over."
  log "Starting release ${id} in a blue/green slot before changing Nginx traffic..."
  stage_slot_release "${slot}" "${id}"
  sudo systemctl enable --now "$(slot_service "${slot}")"
  verify_slot_service "${slot}" || { sudo systemctl disable --now "$(slot_service "${slot}")" >/dev/null 2>&1 || true; return 1; }
  port="$(slot_port "${slot}")"
  if ! write_upstream "${port}" || ! verify_gateway; then
    warn "The blue/green bootstrap route did not pass verification; returning Nginx to the still-running legacy service."
    if ! write_upstream "${LEGACY_PORT}" || ! gateway_probe_at "${LIVE_PATH}"; then
      echo "CRITICAL: Could not restore or verify the legacy Gatekeeperd route. Both processes are being left running for recovery." >&2
      return 1
    fi
    retire_slot_after_cutover "${slot}"
    return 1
  fi
  if wait_for_nginx_drain; then
    sudo systemctl disable --now "${SERVICE_NAME}" >/dev/null 2>&1 \
      || warn "The legacy ${SERVICE_NAME} service could not be stopped; Nginx now routes to the verified blue slot."
  else
    sudo systemctl disable "${SERVICE_NAME}" >/dev/null 2>&1 || true
    warn "The legacy process remains running until its old Nginx workers drain."
  fi
  log "Blue/green mode is active on port ${port}; the legacy process is no longer on the request path."
}

# Switch Nginx only after an inactive systemd slot has passed readiness and soak.
# The old slot remains running after the switch as the immediate rollback target.
activate_release() {
  local id="$1" dir="${RELEASES_DIR}/$1" active_port_value active_slot candidate_slot candidate_port old_service new_service commit
  sudo test -f "${dir}/gatekeeperd-all.jar" || { echo "Error: release ${id} has no JAR." >&2; return 1; }
  ensure_proxy_configuration
  install_slot_unit
  if sudo test -f "${dir}/gatekeeperd-certbot"; then
    sudo install -o root -g root -m 0755 "${dir}/gatekeeperd-certbot" /usr/local/sbin/gatekeeperd-certbot
  fi
  bootstrap_legacy_service "${id}" || return 1
  active_port_value="$(active_port)"
  active_slot="$(slot_for_port "${active_port_value}")" || { echo "Error: Nginx routes to unknown backend port ${active_port_value:-unset}." >&2; return 1; }
  [[ "${active_slot}" != legacy ]] || { echo "Error: blue/green bootstrap did not move traffic off the legacy listener." >&2; return 1; }
  candidate_slot="$(other_slot "${active_slot}")"
  candidate_port="$(slot_port "${candidate_slot}")"
  if [[ "$(sudo readlink "${SLOT_DIR}/${active_slot}/current" 2>/dev/null | xargs basename 2>/dev/null || true)" == "${id}" ]]; then
    ln -sfn "releases/${id}" "${CURRENT_LINK}.new"
    sudo mv -Tf "${CURRENT_LINK}.new" "${CURRENT_LINK}"
    commit="$(release_commit "${id}")"
    if [[ -n "${commit}" ]]; then printf '%s\n' "${commit}" | sudo tee "${TARGET_DIR}/source-commit.txt" >/dev/null
    else sudo rm -f "${TARGET_DIR}/source-commit.txt"; fi
    return 0
  fi
  wait_for_nginx_drain || return 1
  log "Staging release ${id} in inactive ${candidate_slot} slot on port ${candidate_port}; ${active_slot} continues serving traffic."
  stage_slot_release "${candidate_slot}" "${id}"
  sudo systemctl enable --now "$(slot_service "${candidate_slot}")"
  if ! verify_slot_service "${candidate_slot}"; then
    sudo systemctl disable --now "$(slot_service "${candidate_slot}")" >/dev/null 2>&1 || true
    return 1
  fi
  if ! write_upstream "${candidate_port}" || ! verify_gateway; then
    warn "Candidate ${candidate_slot} failed post-cutover checks; restoring traffic to ${active_slot}."
    if ! write_upstream "${active_port_value}" || ! verify_gateway; then
      echo "CRITICAL: Could not verify restoration to ${active_slot}. Both Gatekeeperd slots are being left running for recovery." >&2
      return 1
    fi
    retire_slot_after_cutover "${candidate_slot}"
    return 1
  fi
  sudo systemctl disable "$(slot_service "${active_slot}")" >/dev/null 2>&1 || true
  sudo ln -sfn "releases/${id}" "${CURRENT_LINK}.new"
  sudo mv -Tf "${CURRENT_LINK}.new" "${CURRENT_LINK}"
  commit="$(release_commit "${id}")"
  if [[ -n "${commit}" ]]; then printf '%s\n' "${commit}" | sudo tee "${TARGET_DIR}/source-commit.txt" >/dev/null
  else sudo rm -f "${TARGET_DIR}/source-commit.txt"; fi
  log "Nginx now routes to ${candidate_slot} (${candidate_port}); ${active_slot} remains running for rollback."
}

prune_releases() {
  local cur blue green ids n drop i
  cur="$(current_id)"
  blue="$(sudo readlink "${SLOT_DIR}/blue/current" 2>/dev/null | xargs basename 2>/dev/null || true)"
  green="$(sudo readlink "${SLOT_DIR}/green/current" 2>/dev/null | xargs basename 2>/dev/null || true)"
  mapfile -t ids < <(release_ids)
  n=${#ids[@]}
  drop=$((n - KEEP_RELEASES))
  for ((i = 0; i < drop; i++)); do
    if [[ "${ids[i]}" != "${cur}" && "${ids[i]}" != "${blue}" && "${ids[i]}" != "${green}" && -n "${ids[i]}" ]]; then
      sudo rm -rf -- "${RELEASES_DIR:?}/${ids[i]}"
    fi
  done
}

# One-time migration from the old single-file layout.
bootstrap_layout() {
  local id dir
  sudo install -d -o root -g root -m 0755 "${RELEASES_DIR}"
  if ! sudo test -L "${CURRENT_LINK}" && sudo test -f "${JAR_PATH}" && ! sudo test -L "${JAR_PATH}"; then
    id="$(sudo date -u -r "${JAR_PATH}" +%Y%m%dT%H%M%SZ)-legacy"
    dir="${RELEASES_DIR}/${id}"
    log "Importing existing JAR as release ${id}..."
    sudo install -d -o root -g root -m 0755 "${dir}"
    sudo install -o root -g root -m 0755 "${JAR_PATH}" "${dir}/gatekeeperd-all.jar"
    if sudo test -f /usr/local/sbin/gatekeeperd-certbot; then
      sudo install -o root -g root -m 0755 /usr/local/sbin/gatekeeperd-certbot "${dir}/gatekeeperd-certbot"
    fi
    (sudo cat "${TARGET_DIR}/source-commit.txt" 2>/dev/null || echo unknown) | sudo tee "${dir}/commit" >/dev/null
    sudo ln -sfn "releases/${id}" "${CURRENT_LINK}.new"
    sudo mv -T "${CURRENT_LINK}.new" "${CURRENT_LINK}"
  fi
  if ! sudo test -L "${JAR_PATH}"; then
    sudo ln -sfn "current/gatekeeperd-all.jar" "${JAR_PATH}.new"
    sudo mv -T "${JAR_PATH}.new" "${JAR_PATH}"
  fi
}

# ---------------------------------------------------------------- rollback

resolve_rollback_target() {
  local want="$1" cur ids i id
  cur="$(current_id)"
  [[ -n "${cur}" ]] || die "No current release found; nothing to roll back from."
  mapfile -t ids < <(release_ids)
  if [[ -n "${want}" ]]; then
    for id in "${ids[@]}"; do
      if [[ "${id}" == "${want}" || "${id}" == *"-${want}"* ]]; then echo "${id}"; return 0; fi
    done
    die "No local release matches '${want}'. Run --list."
  fi
  for i in "${!ids[@]}"; do
    if [[ "${ids[i]}" == "${cur}" ]]; then
      (( i > 0 )) || die "No older release available."
      echo "${ids[i-1]}"
      return 0
    fi
  done
  die "Current release ${cur} is not in ${RELEASES_DIR}."
}

do_rollback() {
  local from to bad
  from="$(current_id)"
  to="$(resolve_rollback_target "${ROLLBACK_TARGET}")"
  [[ "${to}" != "${from}" ]] || die "Already on ${to}."
  bad="$(release_commit "${from}")"
  log "Rolling back ${from} -> ${to}"
  if activate_release "${to}" && verify_service; then
    if [[ -n "${bad}" && "${bad}" != "unknown" ]]; then
      printf '%s\n' "${bad}" | sudo tee "${HOLD_FILE}" >/dev/null
      log "Auto-deploy will skip commit ${bad:0:12} until a newer commit builds (or you use --force)."
    fi
    log "Rollback complete: now on ${to}."
    return 0
  fi
  echo "Error: rollback target ${to} failed; returning to ${from}." >&2
  activate_release "${from}" || true
  verify_service || echo "CRITICAL: ${SERVICE_NAME} is unhealthy on both releases. Manual intervention needed." >&2
  exit 1
}

# ---------------------------------------------------------------- sudoers

configure_sudoers() {
  local service_user sudoers_file marker backup
  service_user="$(sudo systemctl show --property=User --value "${SERVICE_NAME}")"
  sudoers_file="/etc/sudoers.d/gatekeeperd-certbot"
  marker="# Managed by Gatekeeperd deploy.sh. Do not edit."
  backup="${TMP_DIR}/gatekeeperd-certbot.sudoers.backup"
  if [[ -z "${service_user}" || "${service_user}" == "root" ]]; then
    log "${SERVICE_NAME} runs as root; no Certbot sudoers rule is needed."
    if sudo test -f "${sudoers_file}" && sudo grep -Fq "${marker}" "${sudoers_file}"; then
      sudo rm "${sudoers_file}"
      sudo visudo -c
    fi
    return 0
  fi
  if [[ ! "${service_user}" =~ ^[a-zA-Z_][a-zA-Z0-9_.-]*\$?$ ]] || ! id "${service_user}" >/dev/null 2>&1; then
    die "Cannot safely resolve the systemd service user '${service_user}'. Configure ${sudoers_file} manually."
  fi
  SUDOERS_TMP="$(mktemp)"
  if sudo test -f "${sudoers_file}"; then
    sudo grep -Fq "${marker}" "${sudoers_file}" || die "${sudoers_file} exists and is not managed by deploy.sh; review it manually."
    sudo cp -p "${sudoers_file}" "${backup}"
  fi
  {
    printf '%s\n' "${marker}"
    printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/gatekeeperd-certbot *\n' "${service_user}"
    printf '%s ALL=(root) NOPASSWD: /bin/systemctl start --no-block gatekeeperd-auto-deploy.service\n' "${service_user}"
  } >"${SUDOERS_TMP}"
  chmod 0440 "${SUDOERS_TMP}"
  sudo visudo -cf "${SUDOERS_TMP}"
  sudo install -o root -g root -m 0440 "${SUDOERS_TMP}" "${sudoers_file}"
  if ! sudo visudo -c; then
    if [[ -f "${backup}" ]]; then
      sudo install -o root -g root -m 0440 "${backup}" "${sudoers_file}"
    else
      sudo rm -f "${sudoers_file}"
    fi
    die "Sudoers validation failed; previous configuration was restored."
  fi
  log "Configured restricted Certbot access for service user ${service_user}."
}

# ---------------------------------------------------------------- deploy

install_auto_deploy() {
  local tools_dir="$1" gh_dir
  log "Installing automatic deployment checker..."
  sudo install -o root -g root -m 0755 "${tools_dir}/deploy.sh" "${TARGET_DIR}/deploy.sh"
  sudo install -o root -g root -m 0644 "${tools_dir}/scripts/gatekeeperd-slot@.service" "${TARGET_DIR}/gatekeeperd-slot@.service.in"
  sudo install -o root -g root -m 0755 "${tools_dir}/scripts/gatekeeperd-auto-deploy" "${TARGET_DIR}/gatekeeperd-auto-deploy"
  sudo install -o root -g root -m 0644 "${tools_dir}/scripts/gatekeeperd-auto-deploy.service" /etc/systemd/system/gatekeeperd-auto-deploy.service
  sudo install -o root -g root -m 0644 "${tools_dir}/scripts/gatekeeperd-auto-deploy.timer" /etc/systemd/system/gatekeeperd-auto-deploy.timer

  if [[ -n "${GH_CONFIG_DIR:-}" ]]; then gh_dir="${GH_CONFIG_DIR}"
  elif [[ -n "${XDG_CONFIG_HOME:-}" ]]; then gh_dir="${XDG_CONFIG_HOME}/gh"
  else gh_dir="${HOME}/.config/gh"; fi

  if [[ "${gh_dir}" != *[$'\n\r ']* ]] && sudo env "GH_CONFIG_DIR=${gh_dir}" gh auth status --hostname github.com >/dev/null 2>&1; then
    sudo install -d -o root -g root -m 0755 /etc/gatekeeperd
    printf 'GH_CONFIG_DIR=%s\n' "${gh_dir}" >"${TMP_DIR}/auto-deploy.env"
    sudo install -o root -g root -m 0600 "${TMP_DIR}/auto-deploy.env" /etc/gatekeeperd/auto-deploy.env
    if sudo systemctl daemon-reload; then
      if sudo systemctl enable --now gatekeeperd-auto-deploy.timer; then
        log "Automatic deployment enabled; successful main builds are checked every five minutes."
      else
        warn "Deployed, but the auto-deploy timer could not be enabled. Run 'sudo systemctl enable --now gatekeeperd-auto-deploy.timer'."
      fi
    else
      warn "Deployed, but systemd could not reload the auto-deploy units. Run 'sudo systemctl daemon-reload && sudo systemctl enable --now gatekeeperd-auto-deploy.timer'."
    fi
  else
    warn "Auto-deploy not enabled: root cannot use the current gh authentication. Configure /etc/gatekeeperd/auto-deploy.env with a readable GH_CONFIG_DIR, then run: sudo systemctl enable --now gatekeeperd-auto-deploy.timer"
  fi
}

check_sha() { # file sha-file label
  local expected actual
  expected="$(awk 'NR == 1 { print $1 }' "$2")"
  actual="$(sha256sum "$1" | awk '{ print $1 }')"
  [[ "${expected}" =~ ^[0-9a-f]{64}$ && "${expected}" == "${actual}" ]] || die "$3 checksum does not match its artifact checksum."
  echo "${actual}"
}

do_deploy() {
  local run_info run_status run_conclusion head_branch previous held current_commit
  command -v gh >/dev/null 2>&1 || die "GitHub CLI ('gh') is required to fetch workflow artifacts."
  command -v curl >/dev/null 2>&1 || die "curl is required for post-deploy health verification."

  if [[ -n "${RUN_ID}" ]]; then
    log "Verifying workflow run ${RUN_ID}..."
    run_info="$(gh run view "${RUN_ID}" --repo "${REPO}" --json databaseId,headSha,headBranch,status,conclusion --jq '[.databaseId, .headSha, .headBranch, .status, .conclusion] | @tsv')"
  else
    log "Finding the latest successful ${WORKFLOW} run on ${BRANCH}..."
    run_info="$(gh run list --repo "${REPO}" --workflow "${WORKFLOW}" --branch "${BRANCH}" --status success --limit 1 \
      --json databaseId,headSha,headBranch,status,conclusion \
      --jq '.[0] // empty | [.databaseId, .headSha, .headBranch, .status, .conclusion] | @tsv')"
  fi
  [[ -n "${run_info}" ]] || die "No successful ${WORKFLOW} run found on ${BRANCH}."

  IFS=$'\t' read -r RUN_ID COMMIT_SHA head_branch run_status run_conclusion <<<"${run_info}"
  if [[ ! "${RUN_ID}" =~ ^[0-9]+$ || ! "${COMMIT_SHA}" =~ ^[0-9a-f]{40}$ || "${head_branch}" != "${BRANCH}" || "${run_status}" != "completed" || "${run_conclusion}" != "success" ]]; then
    die "GitHub returned invalid workflow metadata: ${run_info}"
  fi

  # Do not redeploy a commit that was rolled back, and do not redeploy what is already live.
  if [[ "${FORCE}" != true ]]; then
    held="$(sudo cat "${HOLD_FILE}" 2>/dev/null || true)"
    if [[ -n "${held}" && "${held}" == "${COMMIT_SHA}" ]]; then
      log "Commit ${COMMIT_SHA:0:12} was rolled back and is on hold. Use --force to deploy it anyway."
      return 0
    fi
    current_commit="$(sudo cat "${TARGET_DIR}/source-commit.txt" 2>/dev/null || true)"
    if [[ "${current_commit}" == "${COMMIT_SHA}" ]]; then
      log "Commit ${COMMIT_SHA:0:12} is already deployed."
      return 0
    fi
  fi

  TMP_DIR="$(mktemp -d)"
  log "Downloading artifact from workflow run ${RUN_ID} (commit ${COMMIT_SHA})..."
  gh run download "${RUN_ID}" --repo "${REPO}" --name "${ARTIFACT_NAME}" --dir "${TMP_DIR}"

  local jar="${TMP_DIR}/gatekeeperd-all.jar" helper="${TMP_DIR}/gatekeeperd-certbot" tools="${TMP_DIR}/gatekeeperd-deploy-tools.tar.gz" f
  for f in "${jar}" "${jar}.sha256" "${helper}" "${TMP_DIR}/gatekeeperd-certbot.sha256" "${tools}" "${tools}.sha256"; do
    [[ -f "${f}" ]] || die "Artifact must contain the Gatekeeperd JAR, checksums, Certbot helper, and deployment tools."
  done
  JAR_SHA="$(check_sha "${jar}" "${jar}.sha256" "Downloaded JAR")"
  check_sha "${helper}" "${TMP_DIR}/gatekeeperd-certbot.sha256" "Certbot helper" >/dev/null
  check_sha "${tools}" "${tools}.sha256" "Deployment tools archive" >/dev/null

  local tools_dir="${TMP_DIR}/deployment-tools" member
  mkdir -p "${tools_dir}"
  while IFS= read -r member; do
    case "${member}" in
      deploy.sh|scripts/gatekeeperd-auto-deploy|scripts/gatekeeperd-auto-deploy.service|scripts/gatekeeperd-auto-deploy.timer|scripts/gatekeeperd-slot@.service) ;;
      *) die "Unexpected path in deployment tools archive: ${member}" ;;
    esac
  done < <(tar -tzf "${tools}")
  tar -xzf "${tools}" -C "${tools_dir}" --no-same-owner
  DEPLOY_TOOLS_DIR="${tools_dir}"
  for f in deploy.sh scripts/gatekeeperd-auto-deploy scripts/gatekeeperd-auto-deploy.service scripts/gatekeeperd-auto-deploy.timer scripts/gatekeeperd-slot@.service; do
    [[ -f "${tools_dir}/${f}" ]] || die "Deployment tools archive is missing ${f##*/}."
  done

  local service_file="/etc/systemd/system/${SERVICE_NAME}.service"
  [[ -f "${service_file}" ]] || die "Service unit '${service_file}' not found."
  sudo systemctl show -p ExecStart --value "${SERVICE_NAME}" | grep -Fq "${JAR_PATH}" \
    || die "${SERVICE_NAME} ExecStart does not reference ${JAR_PATH}; the release symlink scheme would not take effect."

  configure_sudoers
  bootstrap_layout

  local rel_id rel_dir
  rel_id="$(date -u +%Y%m%dT%H%M%SZ)-${COMMIT_SHA:0:12}"
  rel_dir="${RELEASES_DIR}/${rel_id}"
  sudo install -d -o root -g root -m 0755 "${rel_dir}"
  sudo install -o root -g root -m 0755 "${jar}" "${rel_dir}/gatekeeperd-all.jar"
  sudo install -o root -g root -m 0755 "${helper}" "${rel_dir}/gatekeeperd-certbot"
  printf '%s\n' "${COMMIT_SHA}" | sudo tee "${rel_dir}/commit" >/dev/null
  printf '%s\n' "${JAR_SHA}" | sudo tee "${rel_dir}/jar.sha256" >/dev/null
  log "Staged release ${rel_id} (JAR SHA-256: ${JAR_SHA})"

  if [[ -n "${PRE_HOOK}" ]]; then
    log "Running pre-deploy hook..."
    bash -c "${PRE_HOOK}" || die "Pre-deploy hook failed; nothing was changed."
  fi

  previous="$(current_id)"
  if activate_release "${rel_id}" && verify_service; then
    :
  else
    echo "Error: new release failed verification." >&2
    if [[ -n "${previous}" && "${previous}" != "${rel_id}" ]]; then
      echo "Restoring ${previous}..." >&2
      activate_release "${previous}" || true
      verify_service || echo "CRITICAL: ${SERVICE_NAME} is unhealthy on the previous release too. Manual intervention needed." >&2
    fi
    exit 1
  fi

  sudo rm -f "${HOLD_FILE}"
  log "Source commit: ${COMMIT_SHA}"
  local active_slot="$(slot_for_port "$(active_port)")"
  if [[ "${active_slot}" == blue || "${active_slot}" == green ]]; then
    sudo systemctl status "$(slot_service "${active_slot}")" --no-pager || true
  fi
  prune_releases
  install_auto_deploy "${tools_dir}"
  log "Deployment complete: commit ${COMMIT_SHA}, release ${rel_id}. Roll back with: ${TARGET_DIR}/deploy.sh --rollback"
}

# ---------------------------------------------------------------- main

main() {
  while (($#)); do
    case "$1" in
      --run-id)
        [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || { echo "Error: --run-id requires a numeric workflow run ID." >&2; exit 2; }
        RUN_ID="$2"; shift 2 ;;
      --force) FORCE=true; shift ;;
      --rollback)
        MODE="rollback"; shift
        if [[ $# -ge 1 && "$1" != --* ]]; then ROLLBACK_TARGET="$1"; shift; fi ;;
      --list) MODE="list"; shift ;;
      *) usage ;;
    esac
  done

  if [[ "${MODE}" == "list" ]]; then list_releases; return 0; fi

  # One deploy/rollback at a time (timer and manual runs can overlap).
  sudo mkdir -p "$(dirname "${LOCK_FILE}")"
  [[ -e "${LOCK_FILE}" ]] || sudo touch "${LOCK_FILE}"
  sudo chmod 0644 "${LOCK_FILE}"
  exec 9<"${LOCK_FILE}"
  flock -n 9 || die "Another deploy or rollback is already running."

  sudo mkdir -p "${TARGET_DIR}"

  case "${MODE}" in
    rollback) do_rollback ;;
    deploy) do_deploy ;;
  esac
}

main "$@"
exit $?
