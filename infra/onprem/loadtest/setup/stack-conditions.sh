#!/usr/bin/env bash
# 앱 스택의 측정 조건을 마크다운 표로 찍는다. **맥에서 실행한다**(스택이 여기 있다).
#
# 왜 필요한가: 부하를 다른 머신에서 걸면 `run.sh` 가 스택 정보를 수집하지 못한다
# (앱 이미지·커넥션 풀·데이터 건수는 스택이 있는 쪽에서만 읽을 수 있다).
# 그러면 회차 기록에 "이 숫자가 어떤 조건의 결과인지"가 빠진다.
#
# 사용:
#   ./setup/stack-conditions.sh                    # 화면에 출력
#   ./setup/stack-conditions.sh >> <회차폴더>/conditions.md
#
# **회차 직전에** 실행할 것. 회차 도중 앱을 재기동하면 조건이 달라진다.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

q() { "$@" 2>/dev/null || echo "확인 불가"; }
img="$(q docker inspect dongne-app --format '{{.Config.Image}}')"
built="$(q docker image inspect "$img" --format '{{.Created}}')"
pool="$(curl -s 'http://localhost:9090/api/v1/query?query=hikaricp_connections_max' 2>/dev/null \
        | sed -n 's/.*"value":\[[0-9.]*,"\([0-9]*\)".*/\1/p')"

cat <<EOF

<!-- 아래는 맥(앱 스택)에서 setup/stack-conditions.sh 로 찍은 것이다. 손으로 고치지 않는다. -->

| 항목 (맥 · 앱 스택) | 값 |
|---|---|
| 찍은 시각 | $(date '+%Y-%m-%d %H:%M:%S %Z') |
| 맥 LAN IP | $(q ipconfig getifaddr en0) (en0 · Wi-Fi) |
| 앱 이미지 | \`${img}\` — 빌드 ${built} |
| 요청 제한 | $(q docker exec dongne-app env | grep -E '^RATE_LIMIT' | paste -sd' ' -) |
| 토큰 수명 | $(q docker exec dongne-app env | grep -E '^JWT_ACCESS_TTL' | paste -sd' ' -) |
| DB 커넥션 풀 | 상한 ${pool:-확인 불가} |
| 데이터 | \`[load]\` 상품 $(mysql_q "select count(*) from products where title like '[load]%';") · \`[load-write]\` 잔여 $(mysql_q "select count(*) from products where title like '[load-write]%';") · 회원 $(mysql_q "select count(*) from members where email like 'load-%';") |
| MySQL buffer pool | $(mysql_q 'select @@innodb_buffer_pool_size;') bytes |
| VM 자원 | CPU $(q docker info --format '{{.NCPU}}') · 메모리 $(q docker info --format '{{.MemTotal}}') bytes |
| 배경 컨테이너 | $(docker ps --format '{{.Names}}' | sort | paste -sd' ' -) |
| 앱 스택 git | \`$(q git -C "$ONPREM_DIR" rev-parse --short HEAD)\` |
EOF
