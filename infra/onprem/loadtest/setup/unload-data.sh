#!/usr/bin/env bash
# 부하테스트 데이터를 정리한다. **맥에서 실행한다.**
# [load] 마커가 붙은 것만 지운다 — perf 데이터와 데모 데이터는 건드리지 않는다.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

log "부하테스트 데이터 정리"
mysql_file "$LOADTEST_DIR/dataset/99-cleanup.sql"

# 삭제만으로는 InnoDB 가 파일을 줄이지 않는다. 다음 적재가 그 공간을 재사용하므로
# 보통은 그대로 두지만, DB 크기를 실제로 되돌리려면 아래를 실행한다.
note "DB 크기를 실제로 되돌리려면: OPTIMIZE TABLE products, members, comments;"
