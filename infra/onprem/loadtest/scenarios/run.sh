#!/usr/bin/env bash
# 회차 실행기 — k6 를 돌리고 **조건과 함께** 회차 폴더에 남긴다.
#
# 왜 래퍼가 필요한가:
#  1. k6 는 `/results/<시나리오>-summary.json` **고정 경로**에 쓴다. 그냥 돌리면 다음 회차가
#     이전 회차를 덮어쓴다. "인덱스 추가 전후" 같은 비교는 이전 측정이 남아 있어야 성립한다.
#  2. 숫자만 남으면 나중에 그 숫자가 **어떤 조건의 결과인지** 판별할 수 없다. 조건을 손으로
#     적으면 반드시 빠뜨리므로, 실행 직전에 실제 값을 읽어 conditions.md 로 남긴다.
#
# 사용(loadtest 폴더에서):
#   ./scenarios/run.sh s01-arrival baseline           # → results/2026-08-04-s01-arrival-baseline/
#   SMOKE=true ./scenarios/run.sh s01-arrival wiring  # 배선 확인용(기록 대상 아님 — 끝나고 지운다)
#   SOAK_RATE=200 ./scenarios/run.sh s01-arrival soak
#
# 맥(스택과 같은 호스트)에서는 오버레이가 자동으로 붙어 nginx:80 으로 들어간다.
# 노트북에서는 온프렘 네트워크가 없으므로 자동으로 빠지고 .env 의 LAN IP 가 쓰인다.
# **어느 쪽으로 갔는지는 conditions.md 에 기록된다** — 두 회차의 절대 수치를 섞으면 안 되기 때문이다.

set -euo pipefail

LOADTEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONPREM_DIR="$(cd "$LOADTEST_DIR/.." && pwd)"
cd "$LOADTEST_DIR"

SCENARIO="${1:-}"
MEMO="${2:-}"
if [ -z "$SCENARIO" ] || [ -z "$MEMO" ]; then
  echo "사용: ./scenarios/run.sh <시나리오> <메모>"
  echo "예:   ./scenarios/run.sh s01-arrival baseline"
  echo
  echo "시나리오:"
  ls scripts/*.js | sed 's|scripts/|  |; s|\.js$||'
  exit 1
fi
[ -f "scripts/${SCENARIO}.js" ] || { echo "✗ scripts/${SCENARIO}.js 없음"; exit 1; }

RUN_DIR="results/$(date +%Y-%m-%d)-${SCENARIO}-${MEMO}"
if [ -e "$RUN_DIR" ]; then
  echo "✗ 이미 있다: $RUN_DIR"
  echo "  회차 기록을 덮어쓰지 않는다. 메모를 다르게 주거나 기존 폴더를 먼저 정리할 것"
  exit 1
fi

# ── 이 머신에 스택이 있는가 ────────────────────────────────────────────────
# 있으면 k6 를 온프렘 네트워크에 붙이고(오버레이), 조건도 실제 값으로 수집할 수 있다.
STACK_HERE=0
COMPOSE=(docker compose -f docker-compose.yml)
if docker network inspect dongnemarket-onprem_default >/dev/null 2>&1; then
  STACK_HERE=1
  COMPOSE+=(-f docker-compose.onprem.yml)
fi

mkdir -p "$RUN_DIR/raw"

# ── 조건 수집 ──────────────────────────────────────────────────────────────
# 손으로 적지 않는다. 실행 직전의 실제 값을 읽어야 기록이 실제와 어긋나지 않는다.
CONDITIONS="$RUN_DIR/conditions.md"
q() { "$@" 2>/dev/null || echo "확인 불가"; }

git_rev="$(q git -C "$ONPREM_DIR" rev-parse --short HEAD)"
git_dirty=""
if ! git -C "$ONPREM_DIR" diff --quiet HEAD -- "$LOADTEST_DIR" 2>/dev/null; then
  git_dirty=" (커밋되지 않은 변경 있음)"
fi
base_url="$(q "${COMPOSE[@]}" config | grep -E '^\s+BASE_URL:' | head -1 | sed 's/.*BASE_URL: *//')"

{
  echo "# 측정 조건 — ${SCENARIO} / ${MEMO}"
  echo
  echo "실행 직전에 스크립트가 실제 값을 읽어 남긴 것이다. 손으로 고치지 않는다."
  echo
  echo "| 항목 | 값 |"
  echo "|---|---|"
  echo "| 실행 시각 | $(date '+%Y-%m-%d %H:%M:%S %Z') |"
  echo "| 스크립트 버전 | \`${git_rev}\`${git_dirty} |"
  echo "| 측정 위치 | $([ "$STACK_HERE" = 1 ] && echo '맥 로컬 (오버레이 적용 — Wi-Fi 를 타지 않는다)' || echo '원격 (LAN 너머 — 사용자 실측)') |"
  echo "| BASE_URL | \`${base_url}\` |"
  echo "| 시나리오 스위치 | SMOKE=\`${SMOKE:-}\` SOAK_RATE=\`${SOAK_RATE:-}\` ALLOWED_RATIO=\`${ALLOWED_RATIO:-기본 3}\` REGION_CODE=\`${REGION_CODE:-없음}\` |"
} > "$CONDITIONS"

if [ "$STACK_HERE" = 1 ]; then
  source "$LOADTEST_DIR/lib/common.sh"
  img="$(q docker image inspect "$(docker inspect dongne-app --format '{{.Config.Image}}' 2>/dev/null)" --format '{{.Created}}')"
  {
    echo "| 앱 이미지 | \`$(q docker inspect dongne-app --format '{{.Config.Image}}')\` — 빌드 ${img} |"
    echo "| 요청 제한 | $(q docker exec dongne-app env | grep -E '^RATE_LIMIT' | paste -sd' ' - ) |"
    echo "| 토큰 수명 | $(q docker exec dongne-app env | grep -E '^JWT_ACCESS_TTL' | paste -sd' ' - ) |"
    echo "| DB 커넥션 풀 | 상한 $(curl -s 'http://localhost:9090/api/v1/query?query=hikaricp_connections_max' 2>/dev/null | sed -n 's/.*"value":\[[0-9.]*,"\([0-9]*\)".*/\1/p' || echo '확인 불가') |"
    echo "| 데이터 | \`[load]\` 상품 $(mysql_q "select count(*) from products where title like '[load]%';") · 회원 $(mysql_q 'select count(*) from members;') · 댓글 $(mysql_q 'select count(*) from comments;') |"
    echo "| MySQL buffer pool | $(mysql_q 'select @@innodb_buffer_pool_size;') bytes |"
    echo "| VM 자원 | CPU $(q docker info --format '{{.NCPU}}') · 메모리 $(q docker info --format '{{.MemTotal}}') bytes |"
    echo "| 배경 컨테이너 | $(docker ps --format '{{.Names}}' | sort | paste -sd' ' -) |"
  } >> "$CONDITIONS"
else
  echo "| 스택 정보 | 이 머신에 스택이 없어 수집하지 않았다. 맥 쪽 기록을 함께 볼 것 |" >> "$CONDITIONS"
fi

echo
echo "▶ ${SCENARIO} (${MEMO}) — ${RUN_DIR}"
sed -n '5,99p' "$CONDITIONS"
echo

# ── Grafana 로 실시간 전송 ─────────────────────────────────────────────────
# k6 는 `-o experimental-prometheus-rw` 를 줘야 실제로 보낸다. 이게 없으면 대시보드의
# k6 패널이 회차 내내 빈 채로 지나가고, 무릎이 초당 몇 진입에서 생겼는지 읽을 방법이 없다
# (요약 JSON 은 계단 전체의 합산값이다).
K6_OUT=()
RW_URL="${K6_PROMETHEUS_RW_SERVER_URL:-$(grep -E '^K6_PROMETHEUS_RW_SERVER_URL=' .env 2>/dev/null | tail -1 | cut -d= -f2-)}"
if [ "$STACK_HERE" = 1 ] && [ -n "$RW_URL" ]; then
  # 맥에서는 k6 가 이미 온프렘 네트워크에 있으므로 컨테이너 이름으로 바로 간다(Wi-Fi 를 타지 않는다).
  RW_URL="http://prometheus:9090/api/v1/write"
fi
if [ -n "$RW_URL" ]; then
  export K6_PROMETHEUS_RW_SERVER_URL="$RW_URL"
  K6_OUT=(-o experimental-prometheus-rw)
else
  echo "⚠️ K6_PROMETHEUS_RW_SERVER_URL 이 없다 — Grafana 의 k6 패널이 비어 있게 된다"
fi

# 회차 꼬리표를 폴더 이름과 맞춘다. Grafana 의 $testid 와 결과 폴더가 1:1 로 대응해야
# 나중에 "이 화면이 어느 회차였나"를 판별할 수 있다.
export K6_TAGS="testid:$(basename "$RUN_DIR")"
echo "| k6 → Prometheus | \`${RW_URL:-없음}\` · testid=\`$(basename "$RUN_DIR")\` |" >> "$CONDITIONS"

# ── CPU 워처 ──────────────────────────────────────────────────────────────
# **부하 생성기가 앱을 왜곡하기 전에 멈춘다.** 맥 한 대에서 돌 때만 켠다.
#
# k6 는 호스트 CPU 를 읽을 수 없으므로 밖에서 감시해야 한다. VM 전체 CPU 가 임계(기본 70%)를
# **연속 3회** 넘으면 k6 를 중단한다 — 한 번 튄 값에 회차가 끝나지 않도록 연속 조건을 둔다.
#
# 왜 90%(폐기 기준)가 아니라 70% 인가: 90% 는 "이미 오염된 뒤"라 그 구간을 버리게 된다.
# 실제로 2회차에서 무릎을 넘긴 뒤 10분을 CPU 90% 로 돌아 통째로 폐기했다.
# 지금까지 찾은 무릎이 전부 57~68% 에서 나왔으므로 70% 는 무릎을 놓치지 않는다.
#
# k6 컨테이너에 CPU 상한(K6_CPUS, 기본 3코어 = 30%)이 걸려 있으므로, 전체가 70% 라는 것은
# **앱+DB 가 최소 40% 를 확보한 상태**라는 뜻이다.
CPU_LIMIT="${CPU_LIMIT_PCT:-70}"
WATCHER_PID=""
if [ "$STACK_HERE" = 1 ]; then
  (
    over=0
    while :; do
      sleep 5
      cpu="$(curl -s --get 'http://localhost:9090/api/v1/query' \
              --data-urlencode 'query=100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[30s])) * 100)' \
            2>/dev/null | sed -n 's/.*"value":\[[0-9.]*,"\([0-9.]*\)".*/\1/p')"
      [ -z "$cpu" ] && continue
      if [ "$(printf '%.0f' "$cpu")" -ge "$CPU_LIMIT" ]; then
        over=$((over + 1))
        if [ "$over" -ge 3 ]; then
          echo "⚠️ VM CPU ${cpu}% — 임계 ${CPU_LIMIT}% 를 연속 3회 넘어 회차를 중단한다." \
            | tee -a "$RUN_DIR/raw/k6.log"
          echo "cpu-watcher" > "$RUN_DIR/raw/.stopped-by"
          docker ps -q --filter "ancestor=grafana/k6:latest" | xargs -r docker stop >/dev/null 2>&1
          exit 0
        fi
      else
        over=0
      fi
    done
  ) &
  WATCHER_PID=$!
  echo "| 중단 조건 | 배수 ${ABORT_RATIO:-10}배 초과 **또는** VM CPU ${CPU_LIMIT}% 연속 초과 · k6 CPU 상한 ${K6_CPUS:-3}코어 |" >> "$CONDITIONS"
fi

# ── 컨테이너별 자원 샘플링 ─────────────────────────────────────────────────
# cAdvisor 는 Docker Desktop 에서 컨테이너별로 갈리지 않는다(name 라벨 없이 루트 cgroup 하나).
# 회차 중 어느 컨테이너가 CPU 를 얼마나 썼는지 사후에 판별할 유일한 수단이다.
STATS_PID=""
if [ "$STACK_HERE" = 1 ]; then
  ( echo "time,name,cpu_pct,mem_usage,net_io"
    while :; do
      ts="$(date +%H:%M:%S)"
      docker stats --no-stream --format "${ts},{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.NetIO}}" 2>/dev/null
      sleep 5
    done ) > "$RUN_DIR/raw/docker-stats.csv" &
  STATS_PID=$!
fi

# ── 실행 ──────────────────────────────────────────────────────────────────
# k6 는 임계값을 넘기면 99 로 끝난다. 그때도 결과는 남겨야 하므로 여기서 중단하지 않는다.
set +e
"${COMPOSE[@]}" run --rm k6 run "${K6_OUT[@]}" "/scripts/${SCENARIO}.js" 2>&1 | tee "$RUN_DIR/raw/k6.log"
K6_EXIT="${PIPESTATUS[0]}"
set -e
[ -n "$WATCHER_PID" ] && kill "$WATCHER_PID" 2>/dev/null
[ -n "$STATS_PID" ] && kill "$STATS_PID" 2>/dev/null

# ── 결과 회수 ──────────────────────────────────────────────────────────────
moved=0
for f in results/*-summary.json; do
  [ -e "$f" ] || continue
  mv "$f" "$RUN_DIR/"
  moved=1
done
[ "$moved" = 1 ] || echo "⚠️ 요약 JSON 이 없다 — 테스트가 중단됐을 수 있다(raw/k6.log 확인)"

[ -f "$RUN_DIR/summary.md" ] || cp results/TEMPLATE-run.md "$RUN_DIR/summary.md" 2>/dev/null || true

echo
echo "▶ 남긴 곳: $RUN_DIR"
echo "  conditions.md      조건(자동)"
echo "  summary.md         결론 — **사람이 채운다.** 비워두면 나중에 이 회차를 못 읽는다"
echo "  raw/k6.log         원시 출력(gitignore)"
[ -f "$RUN_DIR/raw/docker-stats.csv" ] && echo "  raw/docker-stats.csv  컨테이너별 CPU·메모리 시계열(gitignore)"

# **어느 조건으로 끝났는지가 결과 해석을 가른다.** 회차 기록에도 남긴다.
if [ -f "$RUN_DIR/raw/.stopped-by" ]; then
  echo
  echo "  ⚠️ **VM CPU 임계(${CPU_LIMIT}%)로 중단됐다.** 앱이 무너진 것이 아니라 부하 생성기가"
  echo "     앱을 왜곡하기 시작한 지점이다 — '이 환경에서는 여기까지'가 결론이고, 무릎은 못 찾은 것이다."
  echo "| 종료 사유 | **VM CPU ${CPU_LIMIT}% 초과로 중단** — 무릎이 아니라 측정 한계 |" >> "$CONDITIONS"
elif [ "$K6_EXIT" != 0 ]; then
  echo
  echo "  ⚠️ k6 종료 코드 ${K6_EXIT} — 배수 임계를 넘어 중단됐을 수 있다(= 무릎을 찾았을 수 있다)."
  echo "| 종료 사유 | k6 종료 코드 ${K6_EXIT} — 배수 임계 초과(무릎) 가능성 |" >> "$CONDITIONS"
else
  echo "| 종료 사유 | 계단을 끝까지 완주 |" >> "$CONDITIONS"
fi
exit 0
