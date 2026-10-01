#!/usr/bin/env bash
# Cấu hình nhánh và MR cho một project GitLab theo quy ước Nexus.
# Dùng: GITLAB_TOKEN=... [GITLAB_URL=https://gitlab.com] ./setup-gitlab-project.sh group/project
set -euo pipefail

PROJECT_PATH="${1:?Usage: $0 <group/project>}"
GITLAB_URL="${GITLAB_URL:-https://gitlab.com}"
: "${GITLAB_TOKEN:?Set GITLAB_TOKEN (scope: api)}"

API="$GITLAB_URL/api/v4"
PID="$(printf '%s' "$PROJECT_PATH" | sed 's#/#%2F#g')"
auth=(--header "PRIVATE-TOKEN: $GITLAB_TOKEN")

echo "==> Ensure develop branch exists"
curl -fsS "${auth[@]}" "$API/projects/$PID/repository/branches/develop" >/dev/null 2>&1 \
  || curl -fsS -X POST "${auth[@]}" "$API/projects/$PID/repository/branches?branch=develop&ref=main" >/dev/null

echo "==> Project settings"
curl -fsS -X PUT "${auth[@]}" "$API/projects/$PID" \
  --data-urlencode "default_branch=develop" \
  --data "squash_option=default_on" \
  --data "remove_source_branch_after_merge=true" \
  --data "only_allow_merge_if_pipeline_succeeds=true" \
  --data "only_allow_merge_if_all_discussions_are_resolved=true" \
  --data "merge_method=merge" >/dev/null

protect() { # branch, merge_access_level
  local branch="$1" merge_level="$2"
  curl -sS -X DELETE "${auth[@]}" "$API/projects/$PID/protected_branches/$branch" >/dev/null || true
  curl -fsS -X POST "${auth[@]}" "$API/projects/$PID/protected_branches" \
    --data "name=$branch" \
    --data "push_access_level=0" \
    --data "merge_access_level=$merge_level" \
    --data "allow_force_push=false" >/dev/null
  echo "    protected: $branch (merge level $merge_level)"
}

echo "==> Protected branches (0=no one, 30=Developer, 40=Maintainer)"
protect main 40
protect develop 30

echo "Done. Cấu hình approval tối thiểu 1 người cần làm thủ công (xem docs/GITLAB_SETUP.md)."
