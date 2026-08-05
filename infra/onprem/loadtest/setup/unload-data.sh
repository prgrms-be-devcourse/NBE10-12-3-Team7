#!/usr/bin/env bash
# 부하테스트 데이터를 정리한다. **맥에서 실행한다.**
# [load] 마커가 붙은 것만 지운다 — perf 데이터와 데모 데이터는 건드리지 않는다.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

log "부하테스트 데이터 정리"
mysql_file "$LOADTEST_DIR/dataset/99-cleanup.sql"

# 삭제만으로는 InnoDB 가 파일을 줄이지 않는다. 다음 적재가 그 공간을 재사용하므로
# 보통은 그대로 두지만, DB 크기를 실제로 되돌리려면 아래를 실행한다.
# **삭제가 정말 끝났는지 확인한다.** `members` 를 참조하는 FK 가 18개인데 정리 SQL 이 그중
# 일부만 알고 있으면 FK 오류로 중간에 멈춘다 — 실제로 겪었다(manner_scores).
# 스키마가 늘어나도 여기서 잡히도록, 남은 행이 있으면 어느 테이블인지 알려준다.
left="$(mysql_q "select count(*) from members where email like 'load-%';")"
if [ "${left:-0}" -gt 0 ]; then
  echo "✗ load-* 회원 ${left} 명이 남았다 — 아래 테이블이 참조 중이다:" >&2
  mysql_q "SELECT CONCAT('   ', TABLE_NAME, '.', COLUMN_NAME)
           FROM information_schema.KEY_COLUMN_USAGE
           WHERE REFERENCED_TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME='members';" >&2
  echo "   dataset/99-cleanup.sql 에 해당 테이블 DELETE 를 추가할 것" >&2
  exit 1
fi
note "확인: load-* 회원 0명, [load]·[load-write] 상품 0건"

note "DB 크기를 실제로 되돌리려면: OPTIMIZE TABLE products, members, comments;"
