#!/usr/bin/env bash
# 쓰기 회차가 만든 상품을 정리한다. **맥에서 실행한다**(DB 가 여기 있다).
#
# 쓰기 시나리오(s02-product-create)는 매 요청이 행을 만들어 회차 한 번에 수만 건이 쌓인다.
# 정리하지 않으면 다음 회차가 **다른 데이터 크기**에서 시작해 회차 비교가 깨진다.
#
# 마커가 `[load-write]` 라 읽기용 시드(`[load]`)와 perf 데이터(`[perf]`)를 건드리지 않는다.
#
# 사용:  ./setup/unload-write.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

log "쓰기 회차 데이터 정리"

before="$(mysql_q "select count(*) from products where title like '[load-write]%';")"
note "정리 대상 [load-write] 상품: ${before:-0} 건"
if [ "${before:-0}" -eq 0 ]; then
  note "정리할 것이 없다"
  exit 0
fi

t0=$(date +%s)
# 이미지 → 상품 순서. 반대로 지우면 FK 로 막힌다.
mysql_q "delete pi from product_images pi join products p on p.id = pi.product_id
         where p.title like '[load-write]%';" >/dev/null
mysql_q "delete from products where title like '[load-write]%';" >/dev/null
t1=$(date +%s)

after="$(mysql_q "select count(*) from products where title like '[load-write]%';")"
total="$(mysql_q "select count(*) from products;")"
seed="$(mysql_q "select count(*) from products where title like '[load]%';")"

note "삭제 $((before - after)) 건 ($((t1-t0))초)"
note "남은 [load-write]: ${after} · [load] 시드: ${seed} · 전체 상품: ${total}"
[ "${after:-0}" -eq 0 ] || { echo "✗ 정리되지 않은 행이 남았다" >&2; exit 1; }
