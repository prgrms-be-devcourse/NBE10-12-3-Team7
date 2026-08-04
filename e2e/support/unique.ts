import { randomUUID } from 'node:crypto';

/**
 * 테스트끼리 절대 겹치지 않는 식별자를 만든다.
 *
 * e2e 는 실 DB 를 공유하기 때문에 고정 이메일("test@test.com")이나 고정 닉네임을 쓰는 순간
 * 병렬 실행에서 DUPLICATE_EMAIL / DUPLICATE_NICKNAME 으로 깨진다. 데이터를 지워서 격리하는
 * 대신 "겹칠 수 없는 값을 매번 새로 만든다"로 격리한다 — 6명이 동시에 돌려도 안전하다.
 */

/** 예: e2e-a1b2c3d4@e2e.local */
export function uniqueEmail(tag = 'e2e'): string {
  return `${tag}-${randomUUID().slice(0, 8)}@e2e.local`;
}

/** 닉네임 제약: 2~20자 (SignupRequest @Size). tag 는 짧게 유지할 것. */
export function uniqueNickname(tag = 'e2e'): string {
  return `${tag}${randomUUID().slice(0, 6)}`;
}

/**
 * 회원가입 비밀번호 제약(SignupRequest):
 *   10~64자 + 영문 + 숫자 + 특수문자 + 공백 불가
 * 테스트마다 다를 이유가 없어 상수로 고정한다.
 */
export const TEST_PASSWORD = 'E2ePass!234';

/** 상품명·게시글 제목 등 "그냥 겹치지만 않으면 되는" 문자열용. */
export function uniqueTitle(prefix: string): string {
  return `${prefix}-${randomUUID().slice(0, 8)}`;
}
