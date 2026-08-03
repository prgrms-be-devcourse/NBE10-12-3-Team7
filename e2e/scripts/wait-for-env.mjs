/**
 * e2e 환경이 "떴는지"가 아니라 "준비됐는지"를 확인한다.
 *
 * 컨테이너가 start 된 것과 요청을 받을 수 있는 것은 다르다. 이 간극을 안 막으면 CI 에서
 * 첫 테스트만 산발적으로 실패하고, 원인을 못 찾아 며칠을 태운다.
 *
 * 실패 메시지를 구체적으로 내는 것이 이 스크립트의 두 번째 목적이다.
 * 팀원이 빨간 화면을 봤을 때 "내 스펙이 틀렸나"와 "환경이 안 떴나"를 즉시 구분할 수 있어야 한다.
 *
 *   node scripts/wait-for-env.mjs        # 백엔드 + Mailpit
 *   node scripts/wait-for-env.mjs --ui   # + 프론트엔드
 */

const BACKEND = process.env.E2E_BACKEND_URL ?? 'http://localhost:18080';
const FRONTEND = process.env.E2E_FRONTEND_URL ?? 'http://localhost:13000';
const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025';

const TIMEOUT_MS = 180_000; // 백엔드 첫 기동은 스키마 생성 + 시딩이 있어 넉넉히 잡는다
const INTERVAL_MS = 2_000;

/** 하나의 대기 대상. check 가 true 를 돌려주면 준비된 것으로 본다. */
const targets = [
  {
    name: '백엔드',
    url: `${BACKEND}/actuator/health`,
    check: async (res) => {
      const body = await res.json();
      return body.status === 'UP';
    },
    hint:
      'docker compose -f docker-compose.e2e.yml logs backend --tail 50\n' +
      '    → 컴파일 에러라면 develop 이 깨진 것이지 스펙 문제가 아니다.',
  },
  {
    name: 'Mailpit',
    url: `${MAILPIT}/api/v1/info`,
    check: () => true,
    hint: 'docker compose -f docker-compose.e2e.yml logs mailpit --tail 30',
  },
  {
    // 시더는 health 가 UP 된 뒤에 돈다 — 그 2초를 안 기다리면 지역이 0건이다.
    name: '마스터 데이터',
    url: `${BACKEND}/api/regions`,
    check: async (res) => (await res.json()).data?.length > 0,
    hint: 'docker compose -f docker-compose.e2e.yml logs backend --tail 50 | grep Seed',
  },
];

if (process.argv.includes('--ui')) {
  targets.push({
    name: '프론트엔드',
    url: FRONTEND,
    check: () => true,
    hint:
      'docker compose -f docker-compose.e2e.yml --profile ui logs frontend --tail 50\n' +
      '    → UI 스펙을 안 돌린다면 --profile ui 없이 기동하면 된다.',
  });
}

async function probe(target) {
  try {
    const res = await fetch(target.url, { signal: AbortSignal.timeout(3_000) });
    if (!res.ok) return false;
    return await target.check(res);
  } catch {
    return false;
  }
}

async function waitFor(target) {
  const startedAt = Date.now();
  process.stdout.write(`  ${target.name} 대기 중`);

  while (Date.now() - startedAt < TIMEOUT_MS) {
    if (await probe(target)) {
      const seconds = Math.round((Date.now() - startedAt) / 1000);
      console.log(` → 준비됨 (${seconds}s)`);
      return true;
    }
    process.stdout.write('.');
    await new Promise((resolve) => setTimeout(resolve, INTERVAL_MS));
  }

  console.log(' → 실패');
  console.error(`\n✗ ${target.name} 가 ${TIMEOUT_MS / 1000}초 안에 준비되지 않았습니다.`);
  console.error(`  확인 대상: ${target.url}`);
  console.error(`  진단:\n    ${target.hint}\n`);
  return false;
}

console.log('e2e 환경 준비 확인');
for (const target of targets) {
  if (!(await waitFor(target))) process.exit(1);
}
console.log('\n✓ 환경이 준비되었습니다.\n');
