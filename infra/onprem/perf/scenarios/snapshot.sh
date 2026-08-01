#!/usr/bin/env bash
# 현재 상태를 한 번에 찍어 results/ 에 남긴다. 계단마다 적재 직후 실행한다.
#
# 사용:
#   ./scenarios/snapshot.sh 1                     # results/<오늘>-volume/step-1.json
#   ./scenarios/snapshot.sh 3 my-run              # results/my-run/step-3.json
#
# 손으로 curl 을 반복하면 계단이 늘어날수록 조건이 흐트러진다(호출 횟수·순서·워밍업 여부).
# 이 스크립트가 그 절차를 고정한다.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

STEP="${1:?사용법: ./scenarios/snapshot.sh <계단번호> [실행폴더명]}"
RUN_NAME="${2:-$(date +%Y-%m-%d)-volume}"
RUN_DIR="$PERF_DIR/results/$RUN_NAME"
OUT="$RUN_DIR/step-${STEP}.json"
SAMPLES="${SAMPLES:-7}"   # 홀수로 두면 중앙값이 실제 관측치가 된다

mkdir -p "$RUN_DIR"
require_stack

# 측정 대상 — **사용자가 화면에서 실제로 부르는 API만** 잰다.
# 백엔드에 존재해도 프론트가 호출하지 않는 엔드포인트는 재지 않는다. 한때
# /api/categories/{id}/products 를 재고 "카테고리 목록이 느리다"고 판단했는데,
# 프론트를 뒤져보니 그 경로를 부르는 화면이 없었다 — 아무도 겪지 않는 지연이었다.
#
# 화면 → API 대응 (frontend/src/app 기준)
#   상품 목록 진입      GET /api/products?size=30              (+ 동네 설정 시 regionCodes)
#   목록 스크롤         GET /api/products?size=30&cursor=...
#   상품 상세 클릭      GET /api/products/{id}                 (조회수 UPDATE 포함)
#   상세 진입 시 동시   GET /api/products/{id}/comments
#
# 카테고리 탭과 검색창은 서버를 부르지 않는다 — 이미 받아온 배열을 클라이언트에서 거른다
# (products/page.tsx). 그래서 프로브에 넣지 않는다.
#
# ID·지역코드는 데이터에 따라 달라지므로 env로 덮어쓸 수 있게 둔다.
PROBE_REGION="${PROBE_REGION:-1111010300}"   # 상품이 가장 많은 동네
PROBE_HOT_ID="${PROBE_HOT_ID:-6108}"         # 조회수 최상위 — 같은 row UPDATE 경합 구간
PROBE_ID="${PROBE_ID:-134}"                  # 일반 상품
PROBE_CURSOR="${PROBE_CURSOR:-26516}"        # 스크롤을 한참 내린 상태

declare -a NAMES=(list_first list_region list_deep detail_hot comments)
declare -a PATHS=(
  "/api/products?size=30"
  "/api/products?size=30&regionCodes=$PROBE_REGION"
  "/api/products?size=30&cursor=$PROBE_CURSOR"
  "/api/products/$PROBE_HOT_ID"
  "/api/products/$PROBE_ID/comments"
)

# 관리자 화면. 관리자도 사람이고 매일 그 화면을 쓴다 — 오히려 사용자 화면보다 볼륨에 취약하다.
# 관리자 컨트롤러 8개가 전부 페이징이 없어 목록을 통째로 반환한다.
# 무거워서 샘플을 줄이고, 순서상 **맨 뒤**에 둔다 — 81 MB 응답이 버퍼풀을 휩쓸어
# 앞선 측정에 영향을 주지 않게 하기 위해서다.
# 관리자 프로브는 기본으로 돌리지 않는다. 문제(페이징 없음 → 응답 수십 MB)를 이미 확인했고,
# 매 계단마다 81 MB 를 7번 내려받으면 측정 시간과 버퍼풀 오염만 커진다.
# 관리자 화면을 다시 볼 때만 켠다:  PROBE_ADMIN=true ./scenarios/snapshot.sh <계단>
PROBE_ADMIN="${PROBE_ADMIN:-false}"
ADMIN_SAMPLES="${ADMIN_SAMPLES:-3}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@dongnemarket.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin1234!}"

declare -a ADMIN_NAMES=(admin_products admin_members admin_dashboard)
declare -a ADMIN_PATHS=(
  "/api/admin/products"
  "/api/admin/members"
  "/api/admin/dashboard"
)

# 응답시간 N회를 재고 중앙값을 돌려준다(ms). 첫 회는 워밍업으로 버린다 —
# JIT 컴파일과 커넥션 풀 초기화 때문에 첫 요청만 유독 느려 중앙값을 왜곡한다.
# 반드시 HTTP 200 인지 확인한다. 거부 응답(429·5xx)은 본문이 없어 빠르게 돌아오므로,
# 상태를 안 보면 "빨라졌다"로 잘못 기록된다 — 실제로 겪었다. 앱의 요청 제한은
# 기본 10초당 60건(초당 6건)이라, 다른 트래픽을 동시에 흘리면 측정이 429로 오염된다.
measure() {
  local path="$1" i t code
  curl -s -o /dev/null ${AUTH_HEADER:+-H "$AUTH_HEADER"} "$BASE_URL$path" || true   # 워밍업
  local times=()
  for ((i = 0; i < SAMPLES; i++)); do
    read -r t code <<< "$(curl -s -o /dev/null -w '%{time_total} %{http_code}' ${AUTH_HEADER:+-H "$AUTH_HEADER"} "$BASE_URL$path")"
    if [ "$code" != "200" ]; then
      echo "✗ $path 가 HTTP $code — 측정 중단. 동시에 도는 트래픽이 있는지 확인할 것" >&2
      exit 1
    fi
    times+=("$t")
  done
  printf '%s\n' "${times[@]}" | python3 -c '
import sys
v = sorted(float(x) * 1000 for x in sys.stdin if x.strip())
print(f"{v[len(v)//2]:.1f} {v[0]:.1f} {v[-1]:.1f}")'
}

log "계단 $STEP 스냅샷 — 샘플 $SAMPLES 회"

products_total="$(row_count products)"
products_perf="$(mysql_q "select count(*) from products where title like '[perf]%';")"
members_perf="$(mysql_q "select count(*) from members where email like 'perf-%';")"
mysql_q "analyze table products;" > /dev/null
db_mib="$(db_size_mib)"
hit="$(buffer_hit_rate)"
pool_mib="$(mysql_q 'select round(@@innodb_buffer_pool_size/1048576);')"

note "상품 $products_total (볼륨 $products_perf) · 회원(볼륨) $members_perf"
note "DB ${db_mib} MiB / 버퍼풀 ${pool_mib} MiB · 히트율 ${hit}%"

ep_json=""
for i in "${!NAMES[@]}"; do
  read -r med min max <<< "$(measure "${PATHS[$i]}")"
  note "$(printf '%-9s 중앙 %8s ms   (최소 %s / 최대 %s)' "${NAMES[$i]}" "$med" "$min" "$max")"
  [ -n "$ep_json" ] && ep_json="$ep_json,"
  ep_json="$ep_json\"${NAMES[$i]}\":{\"path\":\"${PATHS[$i]}\",\"median_ms\":$med,\"min_ms\":$min,\"max_ms\":$max}"
done

# ── 관리자 화면 (기본 꺼짐) ──────────────────────────────────────────────────
if [ "$PROBE_ADMIN" = "true" ]; then
token="$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | python3 -c 'import sys,json
try: print(json.load(sys.stdin)["data"]["accessToken"])
except Exception: print("")')"

if [ -z "$token" ]; then
  echo "✗ 관리자 로그인 실패 — ADMIN_EMAIL/ADMIN_PASSWORD 확인" >&2
  exit 1
fi

AUTH_HEADER="Authorization: Bearer $token"
SAVED_SAMPLES=$SAMPLES
SAMPLES=$ADMIN_SAMPLES
for i in "${!ADMIN_NAMES[@]}"; do
  read -r med min max <<< "$(measure "${ADMIN_PATHS[$i]}")"
  note "$(printf '%-16s 중앙 %8s ms   (최소 %s / 최대 %s)' "${ADMIN_NAMES[$i]}" "$med" "$min" "$max")"
  ep_json="$ep_json,\"${ADMIN_NAMES[$i]}\":{\"path\":\"${ADMIN_PATHS[$i]}\",\"median_ms\":$med,\"min_ms\":$min,\"max_ms\":$max,\"auth\":true}"
done
SAMPLES=$SAVED_SAMPLES
unset AUTH_HEADER
else
  note "관리자 프로브 건너뜀 (PROBE_ADMIN=true 로 켠다)"
fi

cat > "$OUT" <<JSON
{
  "step": "$STEP",
  "at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "samples": $SAMPLES,
  "data": {
    "products_total": $products_total,
    "products_perf": $products_perf,
    "members_perf": $members_perf,
    "db_mib": $db_mib
  },
  "db": {
    "buffer_pool_mib": $pool_mib,
    "buffer_hit_rate_pct": ${hit:-null}
  },
  "endpoints": { $ep_json }
}
JSON

log "기록: ${OUT#"$PERF_DIR/"}"
