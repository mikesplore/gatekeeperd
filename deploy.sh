#!/usr/bin/env bash
set -euo pipefail

REPO="mikesplore/gatekeeperd"
BRANCH="${DEPLOY_BRANCH:-main}"
WORKFLOW="build.yml"
ARTIFACT_NAME="gatekeeperd-all-jar"
TARGET_DIR="/opt/gatekeeperd"
JAR_PATH="${TARGET_DIR}/gatekeeperd-all.jar"
SERVICE_NAME="gatekeeperd"

sudo mkdir -p "${TARGET_DIR}"

if ! command -v gh >/dev/null 2>&1; then
  echo "Error: GitHub CLI ('gh') is required to fetch workflow artifacts." >&2
  exit 1
fi

echo "==> Finding the latest successful ${WORKFLOW} run on ${BRANCH}..."
RUN_INFO="$(gh run list \
  --repo "${REPO}" \
  --workflow "${WORKFLOW}" \
  --branch "${BRANCH}" \
  --status success \
  --limit 1 \
  --json databaseId,headSha \
  --jq '.[0] | [.databaseId, .headSha] | @tsv')"

if [[ -z "${RUN_INFO}" ]]; then
  echo "Error: No successful ${WORKFLOW} run found on ${BRANCH}." >&2
  exit 1
fi

IFS=$'\t' read -r RUN_ID COMMIT_SHA <<<"${RUN_INFO}"
if [[ ! "${RUN_ID}" =~ ^[0-9]+$ || ! "${COMMIT_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
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
if [[ ! -f "${DOWNLOADED_JAR}" || ! -f "${SHA_FILE}" || ! -f "${CERTBOT_HELPER}" || ! -f "${CERTBOT_SHA_FILE}" ]]; then
  echo "Error: Artifact must contain the Gatekeeperd JAR, checksums, and gatekeeperd-certbot helper." >&2
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

sudo install -o root -g root -m 0755 "${DOWNLOADED_JAR}" "${JAR_PATH}"
printf '%s\n' "${COMMIT_SHA}" | sudo tee "${TARGET_DIR}/source-commit.txt" >/dev/null
echo "==> Installed ${JAR_PATH}"
echo "==> Source commit: ${COMMIT_SHA}"
echo "==> JAR SHA-256: ${ACTUAL_SHA}"

echo "==> Reloading systemd and restarting ${SERVICE_NAME}..."
sudo systemctl daemon-reload
sudo systemctl restart "${SERVICE_NAME}"
sudo systemctl status "${SERVICE_NAME}" --no-pager
echo "==> Deployment complete: commit ${COMMIT_SHA}"
