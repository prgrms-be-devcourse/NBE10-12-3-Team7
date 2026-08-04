#!/usr/bin/env bash
# 지금 어떤 상태인지 한 화면에 보여준다. 새로 붙은 사람(또는 에이전트)이 처음 실행할 것.
#
# 사용:  ./scenarios/status.sh
#
# 사이클을 반복하는 작업이라, 다시 붙었을 때 "데이터가 얼마나 들어 있고 마지막 회차가 뭐였고
# 다음에 뭘 해야 하는지"를 매번 손으로 확인하면 시간이 샌다.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

log "스택"
for c in dongne-app dongne-mysql dongne-grafana dongne-prometheus dongne-grafana-renderer; do
  st="$(docker inspect "$c" --format '{{.State.Status}}' 2>/dev/null || echo '없음')"
  printf '  %-26s %s\n' "$c" "$st"
done
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/products" || echo 000)"
note "$BASE_URL/api/products → HTTP $code"

log "적재 상태"
for t in products members favorites comments notifications reports; do
  printf '  %-16s %10s 행\n' "$t" "$(row_count "$t")"
done
note "그중 볼륨 데이터 — 상품 $(mysql_q "select count(*) from products where title like '[perf]%';")건"
note "DB $(db_size_mib) MiB / 버퍼풀 $(mysql_q 'select round(@@innodb_buffer_pool_size/1048576);') MiB · 히트율 $(buffer_hit_rate)%"

log "회차"
runs=("$PERF_DIR"/results/run-*/)
if [ -d "${runs[0]}" ]; then
  for d in "${runs[@]}"; do
    n="$(basename "$d")"
    steps="$(ls "$d"/step-*.json 2>/dev/null | wc -l | tr -d ' ')"
    shots="$(ls "$d"/step-*-grafana.png 2>/dev/null | wc -l | tr -d ' ')"
    sum="$([ -f "$d/summary.md" ] && echo '요약 있음' || echo '요약 없음')"
    printf '  %-24s 계단 %s · 캡처 %s · %s\n' "$n" "$steps" "$shots" "$sum"
  done
else
  note "아직 회차가 없다"
fi

log "미해결 문제 (FINDINGS.md)"
if [ -f "$PERF_DIR/FINDINGS.md" ]; then
  awk '/^## F-/ { id=$0 } /^- \*\*상태\*\*/ { if ($0 !~ /해결$/ || $0 ~ /미해결|부분해결/) print "  " id " → " $0 }' \
    "$PERF_DIR/FINDINGS.md" | sed 's/## //; s/- \*\*상태\*\*: //'
else
  note "FINDINGS.md 없음"
fi

log "다음에 할 일"
note "1. FINDINGS.md 에서 고칠 문제 하나 고르기 (한 번에 하나만)"
note "2. 개선 적용 후 같은 계단을 다시 밟기 — ./scenarios/run-step.sh <계단> <건수> run-N-<개선명>"
note "3. ./scenarios/compare.sh run-1-baseline run-N-<개선명> 으로 비교"
