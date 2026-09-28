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
if [[ ! -f "${DOWNLOADED_JAR}" || ! -f "${SHA_FILE}" ]]; then
  echo "Error: Artifact must contain gatekeeperd-all.jar and gatekeeperd-all.jar.sha256." >&2
  exit 1
fi

EXPECTED_SHA="$(awk 'NR == 1 { print $1 }' "${SHA_FILE}")"
ACTUAL_SHA="$(sha256sum "${DOWNLOADED_JAR}" | awk '{ print $1 }')"
if [[ ! "${EXPECTED_SHA}" =~ ^[0-9a-f]{64}$ || "${EXPECTED_SHA}" != "${ACTUAL_SHA}" ]]; then
  echo "Error: Downloaded JAR checksum does not match its artifact checksum." >&2
  exit 1
fi

sudo install -o root -g root -m 0755 "${DOWNLOADED_JAR}" "${JAR_PATH}"
printf '%s\n' "${COMMIT_SHA}" | sudo tee "${TARGET_DIR}/source-commit.txt" >/dev/null
echo "==> Installed ${JAR_PATH}"
echo "==> Source commit: ${COMMIT_SHA}"
echo "==> JAR SHA-256: ${ACTUAL_SHA}"

SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
if [[ ! -f "${SERVICE_FILE}" ]]; then
  echo "Error: Service unit '${SERVICE_FILE}' not found." >&2
  exit 1
fi

echo "==> Reloading systemd and restarting ${SERVICE_NAME}..."
sudo systemctl daemon-reload
sudo systemctl restart "${SERVICE_NAME}"
sudo systemctl status "${SERVICE_NAME}" --no-pager
echo "==> Deployment complete: commit ${COMMIT_SHA}"
