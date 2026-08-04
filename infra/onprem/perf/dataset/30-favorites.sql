-- 찜. 나의 마켓온 > 찜 목록(GET /api/members/me/favorites)이 대상이다.
--
-- 이 화면들은 전부 "me" 기준이라 전체 건수보다 **한 사람에게 몰린 건수**가 성능을 좌우한다.
-- 30만 건이 회원 500명에게 골고루 퍼지면 1인당 600건이라 아무 일도 안 일어난다.
-- 그래서 테스트 계정 한 명에게 집중시킨다.
--
-- 실행:  /bin/bash -c 'source lib/common.sh; mysql_file dataset/30-favorites.sql'
-- 순서: 10-products.sql 뒤.

-- ── 파라미터 ────────────────────────────────────────────────────────────────
SET @my_rows    = 3000;    -- 테스트 계정 한 명의 찜 개수 (화면이 이만큼을 반환한다)
SET @spread_per = 200;     -- 볼륨 회원 1인당 찜 개수 (전체 테이블 크기를 만드는 쪽)

SET @test_member = (SELECT id FROM members WHERE email = 'onprem-test@example.com' LIMIT 1);
-- 상품·회원 id 는 연속이 아니다(중간에 지운 자리에 구멍이 있다). 산술로 id 를 만들면
-- FK 위반이 나거나, INSERT IGNORE 를 쓰면 실패한 행이 조용히 버려진다(실제로 겪었다).
-- 실제 id 에 일련번호를 붙인 매핑 테이블을 만들어 조인으로 고른다.
DROP TEMPORARY TABLE IF EXISTS _prod;
CREATE TEMPORARY TABLE _prod (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _prod (rn, id)
SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM products WHERE title LIKE '[perf]%';

DROP TEMPORARY TABLE IF EXISTS _mem;
CREATE TEMPORARY TABLE _mem (rn INT PRIMARY KEY, id BIGINT);
INSERT INTO _mem (rn, id)
SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM members WHERE email LIKE 'perf-%';

SET @p_cnt = (SELECT COUNT(*) FROM _prod);
SET @m_cnt = (SELECT COUNT(*) FROM _mem);

DROP TEMPORARY TABLE IF EXISTS _seq;
CREATE TEMPORARY TABLE _seq (n INT PRIMARY KEY);
INSERT INTO _seq (n)
SELECT (d4.n * 1000 + d3.n * 100 + d2.n * 10 + d1.n) + 1
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4;

-- ── 1. 테스트 계정에 집중 ───────────────────────────────────────────────────
-- (member_id, product_id) 유니크라 IGNORE 로 중복을 흘려보낸다 → 재실행해도 안전하다.
INSERT IGNORE INTO favorites (member_id, product_id, created_at, updated_at)
SELECT @test_member, p.id, NOW(6), NOW(6)
FROM _seq s JOIN _prod p ON p.rn = 1 + ((s.n * 7) % @p_cnt)
WHERE s.n <= @my_rows AND @test_member IS NOT NULL;

-- ── 2. 볼륨 회원에게 분산 (테이블 자체를 키우는 쪽) ─────────────────────────
INSERT IGNORE INTO favorites (member_id, product_id, created_at, updated_at)
SELECT m.id, p.id, NOW(6), NOW(6)
FROM _mem m CROSS JOIN _seq s
JOIN _prod p ON p.rn = 1 + (((m.id * 131) + s.n * 17) % @p_cnt)
WHERE s.n <= @spread_per;

DROP TEMPORARY TABLE IF EXISTS _seq;
DROP TEMPORARY TABLE IF EXISTS _prod;
DROP TEMPORARY TABLE IF EXISTS _mem;

SELECT CONCAT('찜 — 전체 ', (SELECT COUNT(*) FROM favorites),
              '건 / 테스트 계정 ', (SELECT COUNT(*) FROM favorites WHERE member_id = @test_member),
              '건') AS result;
