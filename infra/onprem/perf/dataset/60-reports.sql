-- 신고. 두 화면이 대상이다.
--   신고내역      GET /api/members/me/reports  — 내가 낸 신고 목록
--   관리자 신고   GET /api/admin/reports       — findAll() 후 전체를 메모리에서 가공한다
--
-- 관리자 쪽 서비스가 신고를 전부 불러온 뒤 대상 회원 id 를 모아 다시 조회하는 구조라
-- N+1 이 의심되는 지점이다. 볼륨을 넣어야 드러난다.
--
-- ⚠️ 재실행 전에는 반드시 정리해야 한다. reports 에 (reporter_id, target_product_id) 와
--    (reporter_id, target_member_id) 유니크가 걸려 있어 같은 조합을 두 번 넣을 수 없다.
--    INSERT IGNORE 로 넘기지 않는다 — 조용히 버려진 행을 나중에 알아채지 못하기 때문이다.
--      mysql_q "delete from reports where content like '[perf]%';"
--
-- 실행:  /bin/bash -c 'source lib/common.sh; mysql_file dataset/60-reports.sql'
-- 순서: 10-products.sql 뒤.

-- ── 파라미터 ────────────────────────────────────────────────────────────────
SET @my_rows             = 500;    -- 테스트 계정이 낸 신고 (신고내역 화면이 반환하는 양)
SET @product_report_rows = 50000;  -- 상품 신고 (관리자 화면 볼륨)
SET @member_report_rows  = 10000;  -- 회원 신고. 회원이 500명이라 쌍의 상한은 500×499 다

SET @test_member = (SELECT id FROM members WHERE email = 'onprem-test@example.com' LIMIT 1);

-- 상품·회원 id 는 연속이 아니다(지운 자리에 구멍이 있다). 산술로 id 를 만들면 FK 위반이 난다.
-- 실제 id 에 일련번호를 붙인 매핑 테이블을 만들어 조인으로 고른다.
DROP TEMPORARY TABLE IF EXISTS _prod;
CREATE TEMPORARY TABLE _prod (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _prod (rn, id)
SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM products WHERE title LIKE '[perf]%';

DROP TEMPORARY TABLE IF EXISTS _mem;
CREATE TEMPORARY TABLE _mem (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _mem (rn, id)
SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM members WHERE email LIKE 'perf-%';

-- MySQL 은 한 쿼리에서 같은 임시 테이블을 두 번 참조하지 못한다("Can't reopen table").
-- 회원 신고는 신고자와 대상이 모두 회원이므로 매핑 복사본이 하나 더 필요하다.
DROP TEMPORARY TABLE IF EXISTS _mem2;
CREATE TEMPORARY TABLE _mem2 (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _mem2 (rn, id) SELECT rn, id FROM _mem;

SET @p_cnt = (SELECT COUNT(*) FROM _prod);
SET @m_cnt = (SELECT COUNT(*) FROM _mem);

DROP TEMPORARY TABLE IF EXISTS _seqr;
CREATE TEMPORARY TABLE _seqr (n INT PRIMARY KEY);
INSERT INTO _seqr (n)
SELECT (d5.n*10000 + d4.n*1000 + d3.n*100 + d2.n*10 + d1.n) + 1
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d5;

-- ── 1. 테스트 계정이 낸 신고 (신고내역 화면) ────────────────────────────────
-- 상태 4종·사유 5종을 섞는다. 관리자 화면이 상태별로 거르는 경우를 재현하기 위해서다.
INSERT INTO reports (reporter_id, report_type, target_product_id, target_member_id,
                     reason, status, content, created_at, updated_at)
SELECT @test_member, 'PRODUCT', p.id, NULL,
       ELT(1 + (s.n % 5), 'FRAUD_SUSPECTED', 'FAKE_ITEM', 'PROHIBITED_ITEM', 'INAPPROPRIATE_CONTENT', 'ETC'),
       ELT(1 + (s.n % 4), 'RECEIVED', 'REVIEWING', 'COMPLETED', 'REJECTED'),
       CONCAT('[perf] 내 신고 ', s.n, ' — 확인 부탁드립니다.'),
       NOW(6) - INTERVAL (s.n % 90) DAY, NOW(6)
FROM _seqr s JOIN _prod p ON p.rn = 1 + ((s.n * 17) % @p_cnt)
WHERE s.n <= @my_rows AND @test_member IS NOT NULL;

-- ── 2. 상품 신고 분산 (관리자 화면 볼륨) ────────────────────────────────────
-- 상품이 50만 개라 신고자를 돌려써도 (신고자, 상품) 쌍이 겹치지 않는다.
INSERT INTO reports (reporter_id, report_type, target_product_id, target_member_id,
                     reason, status, content, created_at, updated_at)
SELECT m.id, 'PRODUCT', p.id, NULL,
       ELT(1 + (s.n % 5), 'FRAUD_SUSPECTED', 'FAKE_ITEM', 'PROHIBITED_ITEM', 'INAPPROPRIATE_CONTENT', 'ETC'),
       ELT(1 + (s.n % 4), 'RECEIVED', 'REVIEWING', 'COMPLETED', 'REJECTED'),
       CONCAT('[perf] 상품 신고 ', s.n),
       NOW(6) - INTERVAL (s.n % 365) DAY, NOW(6)
FROM _seqr s
JOIN _mem  m ON m.rn = 1 + (s.n % @m_cnt)
JOIN _prod p ON p.rn = 1 + (s.n % @p_cnt)
-- 상품 수보다 많이 만들면 (신고자, 상품) 쌍이 반복돼 유니크 위반이 난다.
-- 계단이 작을 때(상품 1만) 특히 걸리므로 상품 수로 상한을 건다.
WHERE s.n <= LEAST(@product_report_rows, @p_cnt);

-- ── 3. 회원 신고 분산 ───────────────────────────────────────────────────────
-- 회원이 500명뿐이라 (신고자, 대상) 쌍을 몫과 나머지로 갈라야 겹치지 않는다.
-- 한 공식으로 만들면 회원 수 주기로 반복돼 유니크 위반이 난다(실제로 겪었다).
-- 자기 자신을 신고하는 행은 뺀다.
INSERT INTO reports (reporter_id, report_type, target_product_id, target_member_id,
                     reason, status, content, created_at, updated_at)
SELECT m.id, 'MEMBER', NULL, m2.id,
       ELT(1 + (s.n % 5), 'FRAUD_SUSPECTED', 'FAKE_ITEM', 'PROHIBITED_ITEM', 'INAPPROPRIATE_CONTENT', 'ETC'),
       ELT(1 + (s.n % 4), 'RECEIVED', 'REVIEWING', 'COMPLETED', 'REJECTED'),
       CONCAT('[perf] 회원 신고 ', s.n),
       NOW(6) - INTERVAL (s.n % 365) DAY, NOW(6)
FROM _seqr s
JOIN _mem  m  ON m.rn  = 1 + ((s.n DIV @m_cnt) % @m_cnt)
JOIN _mem2 m2 ON m2.rn = 1 + (s.n % @m_cnt)
WHERE s.n <= @member_report_rows AND m.id <> m2.id;

DROP TEMPORARY TABLE IF EXISTS _seqr;
DROP TEMPORARY TABLE IF EXISTS _prod;
DROP TEMPORARY TABLE IF EXISTS _mem;
DROP TEMPORARY TABLE IF EXISTS _mem2;

SELECT CONCAT('신고 — 전체 ', (SELECT COUNT(*) FROM reports),
              '건 / 테스트 계정 ', (SELECT COUNT(*) FROM reports WHERE reporter_id = @test_member),
              '건') AS result;
