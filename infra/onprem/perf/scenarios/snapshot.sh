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

# 측정 대상. 성격이 다른 것을 같이 재야 "무엇이 볼륨에 취약한가"가 갈린다.
#   목록      커서 페이징. PK 정렬이라 인덱스를 탄다
#   검색      lower(title) LIKE '%키워드%' — 선행 와일드카드라 인덱스가 무용지물
#   카테고리  페이징이 없어 해당 카테고리 상품을 통째로 반환한다
#   검색(흔함) 거의 모든 상품이 매칭돼 30건을 찾는 즉시 스캔이 끝난다 — 실제 사용자 패턴에 가깝다
#   검색(희귀) 매칭 0건이라 전체를 끝까지 훑는다 — LIKE 풀스캔의 진짜 최악
declare -a NAMES=(list search_common search_rare category)
declare -a PATHS=(
  "/api/products"
  "/api/products?keyword=%EC%A4%91%EA%B3%A0"
  "/api/products?keyword=zzznothing"
  "/api/categories/1/products"
)

# 응답시간 N회를 재고 중앙값을 돌려준다(ms). 첫 회는 워밍업으로 버린다 —
# JIT 컴파일과 커넥션 풀 초기화 때문에 첫 요청만 유독 느려 중앙값을 왜곡한다.
# 반드시 HTTP 200 인지 확인한다. 거부 응답(429·5xx)은 본문이 없어 빠르게 돌아오므로,
# 상태를 안 보면 "빨라졌다"로 잘못 기록된다 — 실제로 겪었다. 앱의 요청 제한은
# 기본 10초당 60건(초당 6건)이라, 다른 트래픽을 동시에 흘리면 측정이 429로 오염된다.
measure() {
  local path="$1" i t code
  curl -s -o /dev/null "$BASE_URL$path" || true          # 워밍업
  local times=()
  for ((i = 0; i < SAMPLES; i++)); do
    read -r t code <<< "$(curl -s -o /dev/null -w '%{time_total} %{http_code}' "$BASE_URL$path")"
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
