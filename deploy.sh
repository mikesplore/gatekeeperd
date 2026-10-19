#!/usr/bin/env bash
set -euo pipefail

REPO="mikesplore/gatekeeperd"
BRANCH="${DEPLOY_BRANCH:-main}"
WORKFLOW="build.yml"
ARTIFACT_NAME="gatekeeperd-all-jar"
TARGET_DIR="/opt/gatekeeperd"
JAR_PATH="${TARGET_DIR}/gatekeeperd-all.jar"
SERVICE_NAME="gatekeeperd"
RUN_ID=""

while (($#)); do
  case "$1" in
    --run-id)
      [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || { echo "Error: --run-id requires a numeric workflow run ID." >&2; exit 2; }
      RUN_ID="$2"
      shift 2
      ;;
    *)
      echo "Usage: $0 [--run-id WORKFLOW_RUN_ID]" >&2
      exit 2
      ;;
  esac
done

sudo mkdir -p "${TARGET_DIR}"

if ! command -v gh >/dev/null 2>&1; then
  echo "Error: GitHub CLI ('gh') is required to fetch workflow artifacts." >&2
  exit 1
fi

if [[ -n "${RUN_ID}" ]]; then
  echo "==> Verifying workflow run ${RUN_ID}..."
  RUN_INFO="$(gh run view "${RUN_ID}" --repo "${REPO}" --json databaseId,headSha,headBranch,status,conclusion --jq '[.databaseId, .headSha, .headBranch, .status, .conclusion] | @tsv')"
else
  echo "==> Finding the latest successful ${WORKFLOW} run on ${BRANCH}..."
  RUN_INFO="$(gh run list \
    --repo "${REPO}" \
    --workflow "${WORKFLOW}" \
    --branch "${BRANCH}" \
    --status success \
    --limit 1 \
    --json databaseId,headSha,headBranch,status,conclusion \
    --jq '.[0] | [.databaseId, .headSha, .headBranch, .status, .conclusion] | @tsv')"
fi

if [[ -z "${RUN_INFO}" ]]; then
  echo "Error: No successful ${WORKFLOW} run found on ${BRANCH}." >&2
  exit 1
fi

IFS=$'\t' read -r RUN_ID COMMIT_SHA HEAD_BRANCH RUN_STATUS RUN_CONCLUSION <<<"${RUN_INFO}"
if [[ ! "${RUN_ID}" =~ ^[0-9]+$ || ! "${COMMIT_SHA}" =~ ^[0-9a-f]{40}$ || "${HEAD_BRANCH}" != "${BRANCH}" || "${RUN_STATUS}" != "completed" || "${RUN_CONCLUSION}" != "success" ]]; then
  echo "Error: GitHub returned invalid workflow metadata: ${RUN_INFO}" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

echo "==> Downloading artifact from workflow run ${RUN_ID} (commit ${COMMIT_SHA})..."
gh run download "${RUN_ID}" --repo "${REPO}" --name "${ARTIFACT_NAME}" --dir "${TMP_DIR}"

DOWNLOADED_JAR="${TMP_DIR}/gatekeeperd-all.jar"
SHA_FILE="${TMP_DIR}/gatekeeperd-all.jar.sha256"
CERTBOT_HELPER="${TMP_DIR}/gatekeeperd-certbot"
CERTBOT_SHA_FILE="${TMP_DIR}/gatekeeperd-certbot.sha256"
DEPLOY_TOOLS="${TMP_DIR}/gatekeeperd-deploy-tools.tar.gz"
DEPLOY_TOOLS_SHA_FILE="${TMP_DIR}/gatekeeperd-deploy-tools.tar.gz.sha256"
if [[ ! -f "${DOWNLOADED_JAR}" || ! -f "${SHA_FILE}" || ! -f "${CERTBOT_HELPER}" || ! -f "${CERTBOT_SHA_FILE}" || ! -f "${DEPLOY_TOOLS}" || ! -f "${DEPLOY_TOOLS_SHA_FILE}" ]]; then
  echo "Error: Artifact must contain the Gatekeeperd JAR, checksums, Certbot helper, and deployment tools." >&2
  exit 1
fi

EXPECTED_SHA="$(awk 'NR == 1 { print $1 }' "${SHA_FILE}")"
ACTUAL_SHA="$(sha256sum "${DOWNLOADED_JAR}" | awk '{ print $1 }')"
if [[ ! "${EXPECTED_SHA}" =~ ^[0-9a-f]{64}$ || "${EXPECTED_SHA}" != "${ACTUAL_SHA}" ]]; then
  echo "Error: Downloaded JAR checksum does not match its artifact checksum." >&2
  exit 1
fi

EXPECTED_HELPER_SHA="$(awk 'NR == 1 { print $1 }' "${CERTBOT_SHA_FILE}")"
ACTUAL_HELPER_SHA="$(sha256sum "${CERTBOT_HELPER}" | awk '{ print $1 }')"
if [[ ! "${EXPECTED_HELPER_SHA}" =~ ^[0-9a-f]{64}$ || "${EXPECTED_HELPER_SHA}" != "${ACTUAL_HELPER_SHA}" ]]; then
  echo "Error: Downloaded Certbot helper checksum does not match its artifact checksum." >&2
  exit 1
fi

EXPECTED_TOOLS_SHA="$(awk 'NR == 1 { print $1 }' "${DEPLOY_TOOLS_SHA_FILE}")"
ACTUAL_TOOLS_SHA="$(sha256sum "${DEPLOY_TOOLS}" | awk '{ print $1 }')"
if [[ ! "${EXPECTED_TOOLS_SHA}" =~ ^[0-9a-f]{64}$ || "${EXPECTED_TOOLS_SHA}" != "${ACTUAL_TOOLS_SHA}" ]]; then
  echo "Error: Deployment tools archive checksum does not match its artifact checksum." >&2
  exit 1
fi

TOOLS_DIR="${TMP_DIR}/deployment-tools"
mkdir -p "${TOOLS_DIR}"
tar -tzf "${DEPLOY_TOOLS}" | while IFS= read -r MEMBER; do
  case "${MEMBER}" in
    deploy.sh|scripts/gatekeeperd-auto-deploy|scripts/gatekeeperd-auto-deploy.service|scripts/gatekeeperd-auto-deploy.timer) ;;
    *) echo "Error: Unexpected path in deployment tools archive: ${MEMBER}" >&2; exit 1 ;;
  esac
done
tar -xzf "${DEPLOY_TOOLS}" -C "${TOOLS_DIR}"
for REQUIRED_TOOL in \
  "${TOOLS_DIR}/deploy.sh" \
  "${TOOLS_DIR}/scripts/gatekeeperd-auto-deploy" \
  "${TOOLS_DIR}/scripts/gatekeeperd-auto-deploy.service" \
  "${TOOLS_DIR}/scripts/gatekeeperd-auto-deploy.timer"; do
  [[ -f "${REQUIRED_TOOL}" ]] || { echo "Error: Deployment tools archive is missing ${REQUIRED_TOOL##*/}." >&2; exit 1; }
done

SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
if [[ ! -f "${SERVICE_FILE}" ]]; then
  echo "Error: Service unit '${SERVICE_FILE}' not found." >&2
  exit 1
fi

echo "==> Installing the restricted Certbot helper..."
sudo install -o root -g root -m 0755 "${CERTBOT_HELPER}" /usr/local/sbin/gatekeeperd-certbot
SERVICE_USER="$(sudo systemctl show --property=User --value "${SERVICE_NAME}")"
SUDOERS_FILE="/etc/sudoers.d/gatekeeperd-certbot"
SUDOERS_MARKER="# Managed by Gatekeeperd deploy.sh. Do not edit."
if [[ -z "${SERVICE_USER}" || "${SERVICE_USER}" == "root" ]]; then
  echo "==> ${SERVICE_NAME} runs as root; no Certbot sudoers rule is needed."
  if sudo test -f "${SUDOERS_FILE}" && sudo grep -Fq "${SUDOERS_MARKER}" "${SUDOERS_FILE}"; then
    sudo rm "${SUDOERS_FILE}"
    sudo visudo -c
  fi
else
  if [[ ! "${SERVICE_USER}" =~ ^[a-zA-Z_][a-zA-Z0-9_.-]*\$?$ ]] || ! id "${SERVICE_USER}" >/dev/null 2>&1; then
    echo "Error: Cannot safely resolve the systemd service user '${SERVICE_USER}'. Configure ${SUDOERS_FILE} manually." >&2
    exit 1
  fi
  SUDOERS_TMP="$(mktemp)"
  SUDOERS_BACKUP="${TMP_DIR}/gatekeeperd-certbot.sudoers.backup"
  trap 'rm -f "${SUDOERS_TMP:-}"; rm -rf "${TMP_DIR}"' EXIT
  if sudo test -f "${SUDOERS_FILE}"; then
    if ! sudo grep -Fq "${SUDOERS_MARKER}" "${SUDOERS_FILE}"; then
      echo "Error: ${SUDOERS_FILE} already exists and is not managed by deploy.sh; review it manually." >&2
      exit 1
    fi
    sudo cp -p "${SUDOERS_FILE}" "${SUDOERS_BACKUP}"
  fi
  {
    printf '%s\n' "${SUDOERS_MARKER}"
    printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/gatekeeperd-certbot *\n' "${SERVICE_USER}"
    printf '%s ALL=(root) NOPASSWD: /bin/systemctl start --no-block gatekeeperd-auto-deploy.service\n' "${SERVICE_USER}"
  } >"${SUDOERS_TMP}"
  chmod 0440 "${SUDOERS_TMP}"
  sudo visudo -cf "${SUDOERS_TMP}"
  sudo install -o root -g root -m 0440 "${SUDOERS_TMP}" "${SUDOERS_FILE}"
  if ! sudo visudo -c; then
    if [[ -f "${SUDOERS_BACKUP}" ]]; then
      sudo install -o root -g root -m 0440 "${SUDOERS_BACKUP}" "${SUDOERS_FILE}"
    else
      sudo rm -f "${SUDOERS_FILE}"
    fi
    echo "Error: Sudoers validation failed; previous configuration was restored." >&2
    exit 1
  fi
  echo "==> Configured restricted Certbot access for service user ${SERVICE_USER}."
fi

PREVIOUS_JAR="${TMP_DIR}/previous-gatekeeperd-all.jar"
PREVIOUS_COMMIT="$(sudo cat "${TARGET_DIR}/source-commit.txt" 2>/dev/null || true)"
if sudo test -f "${JAR_PATH}"; then
  sudo cp "${JAR_PATH}" "${PREVIOUS_JAR}"
fi

restore_previous_jar() {
  if [[ ! -f "${PREVIOUS_JAR}" ]]; then
    echo "Error: No previous JAR backup is available; leaving the current service process untouched." >&2
    return 1
  fi

  sudo install -o root -g root -m 0755 "${PREVIOUS_JAR}" "${JAR_PATH}"
  if [[ -n "${PREVIOUS_COMMIT}" ]]; then
    printf '%s\n' "${PREVIOUS_COMMIT}" | sudo tee "${TARGET_DIR}/source-commit.txt" >/dev/null
  else
    sudo rm -f "${TARGET_DIR}/source-commit.txt"
  fi
}

restart_previous_service() {
  if restore_previous_jar; then
    if ! sudo systemctl restart "${SERVICE_NAME}"; then
      echo "Error: Previous JAR was restored, but ${SERVICE_NAME} could not be restarted." >&2
    fi
  fi
}

sudo install -o root -g root -m 0755 "${DOWNLOADED_JAR}" "${JAR_PATH}"
echo "==> Installed ${JAR_PATH}"
echo "==> JAR SHA-256: ${ACTUAL_SHA}"

echo "==> Restarting ${SERVICE_NAME}..."
if ! sudo systemctl restart "${SERVICE_NAME}"; then
  echo "Error: ${SERVICE_NAME} did not restart; restoring the previous JAR." >&2
  restart_previous_service
  exit 1
fi

HEALTH_URL="${DEPLOY_HEALTH_URL:-http://127.0.0.1:8080/api/health/live}"
if command -v curl >/dev/null 2>&1; then
  HEALTHY=false
  for _ in {1..30}; do
    if curl --fail --silent --show-error --max-time 2 "${HEALTH_URL}" >/dev/null 2>&1; then HEALTHY=true; break; fi
    sleep 2
  done
  if [[ "${HEALTHY}" != true ]]; then
    echo "Error: Gatekeeperd did not become healthy at ${HEALTH_URL}; restoring the previous JAR." >&2
    restart_previous_service
    exit 1
  fi
else
  if ! sudo systemctl is-active --quiet "${SERVICE_NAME}"; then
    echo "Error: ${SERVICE_NAME} is not active after restart; restoring the previous JAR." >&2
    restart_previous_service
    exit 1
  fi
fi

printf '%s\n' "${COMMIT_SHA}" | sudo tee "${TARGET_DIR}/source-commit.txt" >/dev/null
echo "==> Source commit: ${COMMIT_SHA}"
sudo systemctl status "${SERVICE_NAME}" --no-pager

echo "==> Installing automatic deployment checker..."
sudo install -o root -g root -m 0755 "${TOOLS_DIR}/deploy.sh" "${TARGET_DIR}/deploy.sh"
sudo install -o root -g root -m 0755 "${TOOLS_DIR}/scripts/gatekeeperd-auto-deploy" "${TARGET_DIR}/gatekeeperd-auto-deploy"
sudo install -o root -g root -m 0644 "${TOOLS_DIR}/scripts/gatekeeperd-auto-deploy.service" /etc/systemd/system/gatekeeperd-auto-deploy.service
sudo install -o root -g root -m 0644 "${TOOLS_DIR}/scripts/gatekeeperd-auto-deploy.timer" /etc/systemd/system/gatekeeperd-auto-deploy.timer

if [[ -n "${GH_CONFIG_DIR:-}" ]]; then
  GH_CONFIG_DIR_VALUE="${GH_CONFIG_DIR}"
elif [[ -n "${XDG_CONFIG_HOME:-}" ]]; then
  GH_CONFIG_DIR_VALUE="${XDG_CONFIG_HOME}/gh"
else
  GH_CONFIG_DIR_VALUE="${HOME}/.config/gh"
fi
if [[ "${GH_CONFIG_DIR_VALUE}" != *[$'\n\r ']* ]] && sudo env "GH_CONFIG_DIR=${GH_CONFIG_DIR_VALUE}" gh auth status --hostname github.com >/dev/null 2>&1; then
  sudo install -d -o root -g root -m 0755 /etc/gatekeeperd
  printf 'GH_CONFIG_DIR=%s\n' "${GH_CONFIG_DIR_VALUE}" >"${TMP_DIR}/auto-deploy.env"
  sudo install -o root -g root -m 0600 "${TMP_DIR}/auto-deploy.env" /etc/gatekeeperd/auto-deploy.env
  if sudo systemctl daemon-reload; then
    if sudo systemctl enable --now gatekeeperd-auto-deploy.timer; then
      echo "==> Automatic deployment enabled; successful main builds are checked every five minutes."
    else
      echo "Warning: Gatekeeperd was deployed, but the automatic deployment timer could not be enabled. Run 'sudo systemctl enable --now gatekeeperd-auto-deploy.timer' after resolving the systemd issue." >&2
    fi
  else
    echo "Warning: Gatekeeperd was deployed, but systemd could not reload the automatic deployment timer unit. The application deployment succeeded; resolve systemd and run 'sudo systemctl daemon-reload && sudo systemctl enable --now gatekeeperd-auto-deploy.timer'." >&2
  fi
else
  echo "Warning: Automatic deployment was not enabled because root cannot use the current gh authentication. Configure /etc/gatekeeperd/auto-deploy.env with a readable GH_CONFIG_DIR and run: sudo systemctl enable --now gatekeeperd-auto-deploy.timer" >&2
fi
echo "==> Deployment complete: commit ${COMMIT_SHA}"
