import { defineConfig, devices } from '@playwright/test';

/**
 * MarketON e2e 설정.
 *
 * 프로젝트 3개
 *  - smoke : 환경이 정상인지만 확인한다(팀장 소유). 스펙이 깨졌을 때 "환경 문제인가"를 먼저 가른다.
 *  - api   : 브라우저 없이 백엔드 HTTP 만 때린다. 백엔드 로직 검증은 전부 여기 (팀원 작업 구역).
 *  - ui    : 실제 브라우저로 프론트를 조작한다. REST 로 못 찌르는 것만 (채팅·경매 STOMP).
 *
 * 환경은 docker-compose.e2e.yml 이 담당한다. `npm run e2e:up` 으로 먼저 띄운다.
 * webServer 로 묶지 않는 이유: 기동 실패와 테스트 실패가 뒤섞여 원인 판별이 어려워진다.
 */

import { BACKEND_URL, FRONTEND_URL } from './support/env';

export default defineConfig({
  testDir: './specs',

  // 각 테스트가 자기 데이터를 직접 만들기 때문에(고정 식별자 금지 규칙) 병렬이 안전하다.
  fullyParallel: true,
  workers: process.env.CI ? 2 : 4,

  // 재시도로 flaky 를 덮지 않는다. 깜빡이는 테스트는 팀 전체가 e2e 를 불신하게 만드는
  // 가장 큰 원인이라, 실패하면 그 자리에서 드러내고 24시간 안에 고치거나 skip + 이슈로 뺀다.
  retries: 0,

  timeout: 30_000,
  expect: { timeout: 5_000 },

  // 실수로 test.only 를 커밋해 CI 가 나머지를 통째로 건너뛰는 사고를 막는다.
  forbidOnly: !!process.env.CI,

  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],

  use: {
    trace: 'retain-on-failure',
  },

  projects: [
    {
      name: 'smoke',
      testDir: './specs/smoke',
      use: { baseURL: BACKEND_URL },
    },
    {
      name: 'api',
      testDir: './specs/api',
      use: { baseURL: BACKEND_URL },
      // Content-Type 을 전역으로 박지 않는다. 상품 이미지 업로드처럼 multipart 를 쓰는
      // 요청이 있어서, Playwright 가 요청별로 알아서 붙이도록 둔다.
    },
    {
      name: 'ui',
      testDir: './specs/ui',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: FRONTEND_URL,
        // develop → main 게이트에서 "브라우저에서 어떻게 동작하는지"를 실제로 보기 위한 설정.
        // 성공한 테스트도 녹화한다 — 리뷰어가 흐름을 확인하는 것이 목적이지 실패 분석만이 아니다.
        video: 'on',
        screenshot: 'only-on-failure',
        trace: 'on',
      },
    },
  ],
});
