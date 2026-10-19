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
HEALTH_URL="${DEPLOY_HEALTH_URL:-http://127.0.0.1:8080/api/health/live}"
READY_URL="${DEPLOY_READY_URL:-${HEALTH_URL}}"   # point at a deeper check (DB etc.) if one exists
SOAK_SECONDS="${DEPLOY_SOAK_SECONDS:-30}"        # must stay healthy and not restart for this long
PRE_HOOK="${DEPLOY_PRE_HOOK:-}"                  # e.g. a pg_dump command; deploy aborts if it fails

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
probe_all() { probe "${HEALTH_URL}" && probe "${READY_URL}"; }

# Wait for health, then soak: service must stay active, keep the same PID,
# and not restart. Catches crash loops that a single successful probe misses.
verify_service() {
  local ok=false pid restarts end
  for _ in $(seq 1 30); do
    if sudo systemctl is-active --quiet "${SERVICE_NAME}" && probe_all; then ok=true; break; fi
    sleep 2
  done
  if [[ "${ok}" != true ]]; then
    echo "Error: ${SERVICE_NAME} did not become healthy (${HEALTH_URL}, ${READY_URL})." >&2
    return 1
  fi
  pid="$(sudo systemctl show -p MainPID --value "${SERVICE_NAME}")"
  restarts="$(sudo systemctl show -p NRestarts --value "${SERVICE_NAME}")"
  end=$((SECONDS + SOAK_SECONDS))
  while (( SECONDS < end )); do
    sleep 2
    if ! sudo systemctl is-active --quiet "${SERVICE_NAME}" || ! probe_all; then
      echo "Error: ${SERVICE_NAME} became unhealthy during the ${SOAK_SECONDS}s soak." >&2
      return 1
    fi
  done
  if [[ "$(sudo systemctl show -p MainPID --value "${SERVICE_NAME}")" != "${pid}" \
     || "$(sudo systemctl show -p NRestarts --value "${SERVICE_NAME}")" != "${restarts}" ]]; then
    echo "Error: ${SERVICE_NAME} restarted during the soak window." >&2
    return 1
  fi
  return 0
}

# Point everything at a release and restart. Caller verifies.
activate_release() {
  local id="$1" dir="${RELEASES_DIR}/$1" commit
  sudo test -f "${dir}/gatekeeperd-all.jar" || { echo "Error: release ${id} has no JAR." >&2; return 1; }
  if sudo test -f "${dir}/gatekeeperd-certbot"; then
    sudo install -o root -g root -m 0755 "${dir}/gatekeeperd-certbot" /usr/local/sbin/gatekeeperd-certbot
  fi
  sudo ln -sfn "releases/${id}" "${CURRENT_LINK}.new"
  sudo mv -T "${CURRENT_LINK}.new" "${CURRENT_LINK}"
  commit="$(release_commit "${id}")"
  if [[ -n "${commit}" ]]; then
    printf '%s\n' "${commit}" | sudo tee "${TARGET_DIR}/source-commit.txt" >/dev/null
  else
    sudo rm -f "${TARGET_DIR}/source-commit.txt"
  fi
  log "Restarting ${SERVICE_NAME} on release ${id}..."
  sudo systemctl restart "${SERVICE_NAME}" || { echo "Error: restart failed on release ${id}." >&2; return 1; }
}

prune_releases() {
  local cur ids n drop i
  cur="$(current_id)"
  mapfile -t ids < <(release_ids)
  n=${#ids[@]}
  drop=$((n - KEEP_RELEASES))
  for ((i = 0; i < drop; i++)); do
    if [[ "${ids[i]}" != "${cur}" && -n "${ids[i]}" ]]; then
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
      deploy.sh|scripts/gatekeeperd-auto-deploy|scripts/gatekeeperd-auto-deploy.service|scripts/gatekeeperd-auto-deploy.timer) ;;
      *) die "Unexpected path in deployment tools archive: ${member}" ;;
    esac
  done < <(tar -tzf "${tools}")
  tar -xzf "${tools}" -C "${tools_dir}" --no-same-owner
  for f in deploy.sh scripts/gatekeeperd-auto-deploy scripts/gatekeeperd-auto-deploy.service scripts/gatekeeperd-auto-deploy.timer; do
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
  sudo systemctl status "${SERVICE_NAME}" --no-pager || true
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