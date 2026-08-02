#!/usr/bin/env bash
# 맥에서 실행하는 스크립트의 공통 헬퍼(데이터 적재·정리). 부하를 거는 노트북에서는 쓰지 않는다.
#
# perf/lib/common.sh 와 같은 방식이지만 일부러 복제했다 — loadtest 는 다른 머신에서
# 독립적으로 굴러가야 해서, 폴더 간 의존을 만들지 않는다.

set -euo pipefail

LOADTEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONPREM_DIR="$(cd "$LOADTEST_DIR/.." && pwd)"
ENV_FILE="$ONPREM_DIR/.env"

# `source .env` 를 쓰지 않는다. OAuth redirect URI 값의 꺾쇠를 셸이 리다이렉션으로 해석해
# 스크립트가 죽는다(perf 에서 실측 확인). 필요한 키만 문자열로 꺼낸다.
env_get() {
  local key="$1" default="${2:-}" val
  [ -f "$ENV_FILE" ] || { echo "✗ $ENV_FILE 없음" >&2; exit 1; }
  val="$(grep -E "^${key}=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true)"
  printf '%s' "${val:-$default}"
}

BASE_URL="${BASE_URL:-http://localhost}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-dongne-mysql}"
MYSQL_DB="${MYSQL_DB:-dongne_market}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-$(env_get MYSQL_ROOT_PASSWORD)}"

mysql_q() {
  docker exec -i "$MYSQL_CONTAINER" \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B "$MYSQL_DB" -e "$1" 2>/dev/null
}
mysql_file() {
  local f="$1"
  [ -f "$f" ] || { echo "✗ SQL 파일 없음: $f" >&2; return 1; }
  docker exec -i "$MYSQL_CONTAINER" \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$MYSQL_DB" < "$f"
}
log()  { printf '\n▶ %s\n' "$*"; }
note() { printf '  %s\n' "$*"; }
