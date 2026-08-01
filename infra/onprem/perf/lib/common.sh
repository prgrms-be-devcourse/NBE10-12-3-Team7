#!/usr/bin/env bash
# perf 스크립트 공통 헬퍼. 모든 스크립트는 이 파일만 읽고, 자격증명을 각자 들고 있지 않는다.
#
# 사용:  source "$(dirname "$0")/../lib/common.sh"
#
# 대상은 기본이 온프레미스 스택이지만 강제하지 않는다 — env로 덮어쓰면 다른 지형에도 쓸 수 있다.
#   BASE_URL=http://192.168.0.10 ./scenarios/xxx.sh

set -euo pipefail

PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONPREM_DIR="$(cd "$PERF_DIR/.." && pwd)"
ENV_FILE="$ONPREM_DIR/.env"

# ── .env 읽기 ────────────────────────────────────────────────────────────────
# `source .env` 를 쓰지 않는다. OAuth redirect URI 값에 <프론트엔드-도메인> 같은 꺾쇠가 들어 있어
# 셸이 리다이렉션으로 해석하고 스크립트가 죽는다(실측 확인). 필요한 키만 문자열로 꺼낸다.
env_get() {
  local key="$1" default="${2:-}" val
  [ -f "$ENV_FILE" ] || { echo "✗ $ENV_FILE 없음. infra/onprem 에서 cp .env.example .env 후 값을 채울 것" >&2; exit 1; }
  val="$(grep -E "^${key}=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true)"
  printf '%s' "${val:-$default}"
}

# ── 대상 ─────────────────────────────────────────────────────────────────────
BASE_URL="${BASE_URL:-http://localhost}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-dongne-mysql}"
MYSQL_DB="${MYSQL_DB:-dongne_market}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3001}"

MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-$(env_get MYSQL_ROOT_PASSWORD)}"

# ── MySQL ────────────────────────────────────────────────────────────────────
# 인자로 받은 SQL 한 줄을 실행하고 결과를 탭 구분으로 돌려준다(헤더 없음).
mysql_q() {
  docker exec -i "$MYSQL_CONTAINER" \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DB" -e "$1" 2>/dev/null
}

# SQL 파일을 통째로 실행한다. 실패하면 즉시 멈춘다.
mysql_file() {
  local f="$1"
  [ -f "$f" ] || { echo "✗ SQL 파일 없음: $f" >&2; return 1; }
  docker exec -i "$MYSQL_CONTAINER" \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DB" < "$f"
}

# ── 관측 ─────────────────────────────────────────────────────────────────────
# PromQL 한 개를 던져 첫 시계열의 현재값만 돌려준다. 값이 없으면 빈 문자열.
prom_q() {
  curl -sG "$PROM_URL/api/v1/query" --data-urlencode "query=$1" \
    | python3 -c 'import sys,json;r=json.load(sys.stdin)["data"]["result"];print(r[0]["value"][1] if r else "")'
}

# 자주 쓰는 지표들.
db_size_mib()      { mysql_q "select round(sum(data_length+index_length)/1048576,1) from information_schema.tables where table_schema='$MYSQL_DB';"; }
row_count()        { mysql_q "select count(*) from \`$1\`;"; }
buffer_hit_rate()  { prom_q '(1 - rate(mysql_global_status_innodb_buffer_pool_reads[5m]) / clamp_min(rate(mysql_global_status_innodb_buffer_pool_read_requests[5m]), 0.001)) * 100'; }

# ── 유틸 ─────────────────────────────────────────────────────────────────────
log()  { printf '\n▶ %s\n' "$*"; }
note() { printf '  %s\n' "$*"; }

# 스택이 실제로 응답하는지. 측정 전에 부른다.
require_stack() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/products" || echo 000)"
  [ "$code" = "200" ] || { echo "✗ $BASE_URL/api/products 가 $code. 스택이 떠 있는지 확인" >&2; exit 1; }
  docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER" \
    || { echo "✗ 컨테이너 $MYSQL_CONTAINER 미기동" >&2; exit 1; }
}
