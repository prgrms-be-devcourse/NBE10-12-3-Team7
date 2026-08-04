# 측정 조건 — s01-arrival / pool-20

실행 직전에 스크립트가 실제 값을 읽어 남긴 것이다. 손으로 고치지 않는다.

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-04 14:18:41 KST |
| 스크립트 버전 | `e742659` |
| 측정 위치 | 맥 로컬 (오버레이 적용 — Wi-Fi 를 타지 않는다) |
| BASE_URL | `http://nginx:80` |
| 시나리오 스위치 | SMOKE=`` SOAK_RATE=`` ALLOWED_RATIO=`기본 3` REGION_CODE=`1111010100` |
| 앱 이미지 | `localhost:5000/dongnemarket-app:latest` — 빌드 2026-08-01T09:40:57.558964752Z |
| 요청 제한 | RATE_LIMIT_WINDOW_SECONDS=10 RATE_LIMIT_CAPACITY=100000 |
| 토큰 수명 | JWT_ACCESS_TTL=3600 |
| DB 커넥션 풀 | 상한 20 |
| 데이터 | `[load]` 상품 100000 · 회원 308 · 댓글 505 |
| MySQL buffer pool | 134217728 bytes |
| VM 자원 | CPU 10 · 메모리 8321712128 bytes |
| 배경 컨테이너 | dongne-app dongne-cadvisor dongne-grafana dongne-grafana-renderer dongne-jenkins dongne-loki dongne-mysql dongne-mysqld-exporter dongne-next dongne-nginx dongne-node-exporter dongne-ollama dongne-prometheus dongne-promtail dongne-redis dongne-rustfs dongne-zot |
| k6 → Prometheus | `http://prometheus:9090/api/v1/write` · testid=`2026-08-04-s01-arrival-pool-20` |
