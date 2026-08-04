# 측정 조건 — s03-throughput-ceiling / laptop-min-response

실행 직전에 스크립트가 실제 값을 읽어 남긴 것이다. 손으로 고치지 않는다.

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-04 16:30:59     |
| 스크립트 버전 | `확인 불가` (커밋되지 않은 변경 있음) |
| 측정 위치 | 원격 (LAN 너머 — 사용자 실측) |
| BASE_URL | `http://192.168.200.167` |
| 시나리오 스위치 | SMOKE=`` SOAK_RATE=`` ALLOWED_RATIO=`기본 3` REGION_CODE=`없음` |
| 스택 정보 | 이 머신에 스택이 없어 수집하지 않았다. 맥 쪽 기록을 함께 볼 것 |
| k6 → Prometheus | `http://192.168.200.167:9090/api/v1/write` · testid=`2026-08-04-s03-throughput-ceiling-laptop-min-response` |
