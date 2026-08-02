import { expect, type APIResponse } from '@playwright/test';

/**
 * 백엔드 공통 응답 포맷 헬퍼.
 *
 * 성공: { status, message, data }        — global/response/ApiResponse.kt
 * 실패: { status, error, message, timestamp } — global/response/ErrorResponse.kt
 *       ("error" 는 ErrorCode enum 상수명. 예: DUPLICATE_EMAIL, AUTH_019)
 *
 * 테스트에서 res.json() 을 직접 파헤치면 매번 data 를 벗기는 코드가 반복되고,
 * 실패했을 때 "expected 200, received 400" 만 보여서 원인을 못 찾는다. 그래서 항상 이걸 쓴다.
 */

export interface ApiEnvelope<T> {
  status: number;
  message: string;
  data: T;
}

export interface ApiErrorBody {
  status: number;
  error: string;
  message: string;
  timestamp: string;
}

/**
 * 성공 응답에서 data 만 꺼낸다. 기대한 상태코드가 아니면 응답 본문을 통째로 붙여 실패시킨다.
 *
 * @example
 * const product = await unwrap<ProductResponse>(res, 201);
 */
export async function unwrap<T>(res: APIResponse, expectedStatus = 200): Promise<T> {
  const raw = await res.text();
  expect(res.status(), `${res.url()}\n실제 응답 → ${raw}`).toBe(expectedStatus);
  return (JSON.parse(raw) as ApiEnvelope<T>).data;
}

/**
 * 에러 응답을 검증한다. HTTP 상태코드만 보면 "400 이면 다 통과"가 되어버리므로
 * ErrorCode 까지 못 박는다 — 이게 e2e 를 실행 명세서로 만드는 핵심이다.
 *
 * @example
 * await expectError(res, 409, 'DUPLICATE_EMAIL');
 */
export async function expectError(
  res: APIResponse,
  expectedStatus: number,
  expectedErrorCode?: string,
): Promise<ApiErrorBody> {
  const raw = await res.text();
  expect(res.status(), `${res.url()}\n실제 응답 → ${raw}`).toBe(expectedStatus);

  const body = JSON.parse(raw) as ApiErrorBody;
  if (expectedErrorCode) {
    expect(body.error, `에러 코드 불일치 (message: ${body.message})`).toBe(expectedErrorCode);
  }
  return body;
}

/** Set-Cookie 헤더에서 특정 쿠키 값을 꺼낸다. refreshToken(HttpOnly) 검증용. */
export function readSetCookie(res: APIResponse, name: string): string | undefined {
  const headers = res.headersArray().filter((h) => h.name.toLowerCase() === 'set-cookie');
  for (const header of headers) {
    const match = new RegExp(`(?:^|;\\s*)${name}=([^;]*)`).exec(header.value);
    if (match) return match[1];
  }
  return undefined;
}
