# loadtest 아카이브 (환경 구축 이전 버전)

온프레미스 모더나이제이션(Zot·RustFS·Jenkins·관측 스택) 이전에 작성된 k6 자산의 보존본이다.
**실행용이 아니다.** 시나리오를 다시 설계하면서 참고 자료로만 남긴다.

## 남겨둘 가치가 있는 것

- `seed-loadtest.sql` — 상품 10만 / 신고 40 / 댓글 3000 / 알림 3000 을 재귀 CTE로 만드는 통합 시드.
  참조하는 테이블이 현재 스키마와 전부 일치하는 것을 확인했다(2026-08-01). 재사용 가치가 높다.
- `dummy-data.sql` — 소규모 더미 생성.
- 엔드포인트 선정 근거 — README의 "고빈도 × 숨은 조인"(unread-count가 내부적으로 채팅방 3쿼리를
  재사용) 같은 판단은 다시 만들기 어려운 자산이다.
- 8개 k6 스크립트 — 상품/신고/회원/알림 도메인별 시나리오.

## 그대로 쓰면 안 되는 이유

- README가 스크립트 12개를 표로 안내하지만 **실제로는 8개만 존재**한다. 없는 파일:
  `admin-ai-chat.js`, `report-duplicate.js`, `evidence-image-upload.js`,
  `cancel-race.js`, `admin-status-race.js`.
  설계 문서로 언급된 `product-load.plan.md`, `report-load.plan.md` 도 없다.
- 실행 안내가 Windows(PowerShell·winget·`Get-Content`) 기준이다. 현재 호스트는 macOS.
- Grafana 계정을 `admin/admin`으로 안내한다. 실제 값은 `.env`의 `GRAFANA_ADMIN_*`.
- 정리 SQL이 `DELETE FROM comment` 인데 실제 테이블명은 `comments` 다.
- 관측 스택 연동 안내가 "선택 사항"으로 되어 있다. 지금은 exporter 4종 + 4구역 대시보드가
  상시 떠 있으므로, 부하테스트는 이 대시보드를 읽는 것을 전제로 다시 설계한다.
- 한 번에 전 도메인을 때리는 구성이라 "무엇을 확인하려는 부하인지"가 남지 않는다.
