#!/usr/bin/env bash
# 계단 하나를 처음부터 끝까지 실행한다. 적재 → 안정화 → 측정 → 대시보드 캡처.
#
# 사용:
#   ./scenarios/run-step.sh 1 10000              # 계단 1, 상품 1만 건까지 채운다
#   ./scenarios/run-step.sh 2 30000 my-run       # 실행 폴더 이름 지정
#
# 상품은 "목표 건수까지" 채운다(누적). 나머지 테이블(찜·댓글·알림·신고)은 계단마다 다시 만든다 —
# 상품 id 를 참조하므로 새로 들어온 상품에도 골고루 붙어야 하기 때문이다.
#
# 절차를 스크립트에 고정하는 이유: 손으로 하면 계단마다 대기 시간·호출 순서가 달라져
# 계단 간 비교가 성립하지 않는다.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

STEP="${1:?사용법: ./scenarios/run-step.sh <계단번호> <목표 상품수> [실행폴더명]}"
TARGET="${2:?목표 상품 건수를 지정할 것}"
RUN_NAME="${3:-$(date +%Y-%m-%d)-volume}"
RUN_DIR="$PERF_DIR/results/$RUN_NAME"
SETTLE="${SETTLE:-30}"       # 적재 후 안정화 대기(초). 버퍼풀이 자리를 잡을 시간을 준다

mkdir -p "$RUN_DIR"
require_stack

log "계단 $STEP — 목표 상품 $TARGET 건"

# ── 1. 상품을 목표치까지 채운다 ─────────────────────────────────────────────
have="$(mysql_q "select count(*) from products where title like '[perf]%';")"
need=$(( TARGET - have ))
if [ "$need" -gt 0 ]; then
  note "상품 $have → $TARGET (추가 $need)"
  sed "s/^SET @add_rows .*/SET @add_rows    = $need;/" "$PERF_DIR/dataset/10-products.sql" > /tmp/_step-products.sql
  t0=$(date +%s); mysql_file /tmp/_step-products.sql > /dev/null; t1=$(date +%s)
  note "상품 적재 $((t1-t0))초"
else
  note "상품 이미 $have 건 — 추가 없음"
fi

# ── 2. 나머지 테이블 재구성 ─────────────────────────────────────────────────
# 신고는 유니크 제약 때문에 반드시 지우고 다시 넣어야 한다(파일 주석 참고).
note "찜·댓글·알림·신고 재구성"
t0=$(date +%s)
mysql_q "delete from notifications where message like '[perf]%';
         delete from reports where content like '[perf]%';
         delete from comments where content like '[perf]%';
         delete from favorites;" > /dev/null
for f in 20-member-locations 30-favorites 40-comments 50-notifications 60-reports; do
  mysql_file "$PERF_DIR/dataset/$f.sql" > /dev/null
done
t1=$(date +%s)
note "부속 데이터 적재 $((t1-t0))초"

# ── 3. 안정화 ───────────────────────────────────────────────────────────────
note "안정화 ${SETTLE}초 대기"
sleep "$SETTLE"

# ── 4. 측정 ─────────────────────────────────────────────────────────────────
PROBE_ADMIN="${PROBE_ADMIN:-true}" "$PERF_DIR/scenarios/snapshot.sh" "$STEP" "$RUN_NAME"

# ── 5. 대시보드 캡처 ────────────────────────────────────────────────────────
# 렌더러가 서버에서 PNG 를 만든다 — 브라우저 스크린샷과 달리 URL 하나로 재현된다.
shot="$RUN_DIR/step-${STEP}-grafana.png"
code="$(curl -s -u "admin:$(env_get GRAFANA_ADMIN_PASSWORD)" -o "$shot" -w '%{http_code}' \
  "$GRAFANA_URL/render/d/onprem-overview?orgId=1&from=now-30m&to=now&width=1600&height=1400&kiosk&tz=Asia%2FSeoul")"
if [ "$code" = "200" ] && head -c4 "$shot" | grep -q PNG; then
  note "대시보드 캡처: ${shot#"$PERF_DIR/"} ($(($(wc -c < "$shot") / 1024)) KB)"
else
  echo "✗ 대시보드 캡처 실패 (HTTP $code)" >&2
  rm -f "$shot"
fi

log "계단 $STEP 완료"
