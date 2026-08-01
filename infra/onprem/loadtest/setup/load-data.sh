#!/usr/bin/env bash
# 부하테스트 데이터를 적재한다. **맥에서 실행한다**(DB 가 여기 있다).
#
# 사용:  ./setup/load-data.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

log "부하테스트 데이터 적재"

leftover="$(mysql_q "select count(*) from products where title like '[load]%';")"
if [ "${leftover:-0}" -gt 0 ]; then
  note "이미 [load] 상품이 ${leftover}건 있다. 먼저 ./setup/unload-data.sh 로 정리할 것"
  exit 1
fi

perf_left="$(mysql_q "select count(*) from products where title like '[perf]%';")"
if [ "${perf_left:-0}" -gt 0 ]; then
  note "⚠️ perf 데이터가 ${perf_left}건 남아 있다. 볼륨 조건이 흐려지므로 정리를 권한다"
  note "   ../perf 에서: /bin/bash -c 'source lib/common.sh; mysql_file dataset/99-cleanup.sql'"
fi

t0=$(date +%s)
mysql_file "$LOADTEST_DIR/dataset/10-seed.sql"
t1=$(date +%s)
note "적재 $((t1-t0))초"

log "검증"
mysql_file "$LOADTEST_DIR/dataset/90-verify.sql"

log "앱이 이 데이터를 읽는지 확인"
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/products?size=30")"
[ "$code" = "200" ] || { echo "✗ /api/products 가 $code" >&2; exit 1; }
note "GET /api/products → 200"
note "이제 다른 노트북에서 부하를 걸 수 있다. README 의 '노트북에서' 절 참고"
