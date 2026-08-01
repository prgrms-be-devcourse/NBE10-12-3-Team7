-- 볼륨 상품 적재. 계단마다 이 파일을 다시 실행해 **누적**한다(초기화하지 않는다).
--
-- 실행:  bash -c 'source lib/common.sh; mysql_file dataset/10-products.sql'
--        건수를 바꾸려면 아래 @add_rows 만 고친다.
--
-- 데모 시더가 만든 6건과 섞이지 않도록 마커를 넣는다 — 상품 title '[perf] ', 회원 email 'perf-'.
-- 정리는 99-cleanup.sql 이 이 마커로만 지운다.

-- ── 파라미터 ────────────────────────────────────────────────────────────────
SET @add_rows    = 20000;   -- 이번에 추가할 상품 수
SET @vol_members = 500;     -- 볼륨 회원 수(상품 소유자 풀). 상품:회원 = 20:1 정도면 충분하다
SET @hot_ratio   = 100;     -- 조회수 상위 비율의 역수. 100이면 1%가 인기 상품

-- ── 1. 볼륨 회원 ────────────────────────────────────────────────────────────
-- BCrypt 해시는 SQL로 만들 수 없다. 기존 계정의 해시를 그대로 복사한다 —
-- 이 계정들로 로그인할 일이 없으므로(상품 소유자 역할만) 문제되지 않는다.
SET @pw_hash = (SELECT password FROM members WHERE password IS NOT NULL LIMIT 1);
SET @m_offset = (SELECT COUNT(*) FROM members WHERE email LIKE 'perf-%');

INSERT INTO members (email, password, nickname, role, status, local_login_enabled, created_at, updated_at)
SELECT
  CONCAT('perf-', LPAD(@m_offset + seq, 7, '0'), '@loadtest.local'),
  @pw_hash,
  CONCAT('부하유저', LPAD(@m_offset + seq, 7, '0')),
  'ROLE_USER', 'ACTIVE', b'1', NOW(6), NOW(6)
FROM (
  SELECT (d3.n * 100 + d2.n * 10 + d1.n) + 1 AS seq
  FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
) nums
WHERE seq <= @vol_members AND @m_offset = 0;   -- 회원 풀은 최초 1회만 만든다

-- ── 2. 편중 대상 고르기 ─────────────────────────────────────────────────────
-- 균등 분포는 실제 서비스와 다르다. 지역은 특정 동네에, 카테고리는 인기 종류에 몰린다.
-- 균등하게 뿌리면 인덱스 선택도가 비현실적으로 좋아져 부하테스트 결과가 낙관적으로 왜곡된다.
DROP TEMPORARY TABLE IF EXISTS _hot_regions;
CREATE TEMPORARY TABLE _hot_regions (rn INT PRIMARY KEY, region_id BIGINT);
INSERT INTO _hot_regions (rn, region_id)
SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM regions WHERE level = 3 LIMIT 20;

DROP TEMPORARY TABLE IF EXISTS _all_regions;
CREATE TEMPORARY TABLE _all_regions (rn INT PRIMARY KEY, region_id BIGINT);
INSERT INTO _all_regions (rn, region_id)
SELECT ROW_NUMBER() OVER (ORDER BY id), id FROM regions WHERE level = 3;

SET @hot_cnt = (SELECT COUNT(*) FROM _hot_regions);
SET @all_cnt = (SELECT COUNT(*) FROM _all_regions);
SET @cat_cnt = (SELECT COUNT(*) FROM categories);
SET @mem_cnt = (SELECT COUNT(*) FROM members WHERE email LIKE 'perf-%');
SET @p_offset = (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%');

-- ── 3. 상품 ─────────────────────────────────────────────────────────────────
INSERT INTO products (
  member_id, category_id, region_id, title, description, price,
  view_count, favorite_count, hidden, trade_status, created_at, updated_at
)
SELECT
  -- 소유자: 볼륨 회원 풀에서 순환
  (SELECT id FROM members WHERE email LIKE 'perf-%' ORDER BY id LIMIT 1 OFFSET 0)
    + (seq % @mem_cnt),
  -- 카테고리: 40%를 1·4번(디지털기기·의류)에 몰아준다
  CASE WHEN seq % 10 < 4 THEN 1 + (seq % 2) * 3 ELSE 1 + (seq % @cat_cnt) END,
  -- 지역: 60%를 상위 20개 동네에 몰아준다
  CASE WHEN seq % 10 < 6
       THEN (SELECT region_id FROM _hot_regions WHERE rn = 1 + (seq % @hot_cnt))
       ELSE (SELECT region_id FROM _all_regions WHERE rn = 1 + (seq % @all_cnt))
  END,
  CONCAT('[perf] 중고 상품 ', LPAD(@p_offset + seq, 7, '0'), ' 팝니다'),
  CONCAT('부하테스트용 더미 설명입니다. 상태 양호하고 직거래 가능합니다. ',
         REPEAT('상세 설명 문장을 채워 실제 본문 길이에 가깝게 만듭니다. ', 4),
         '문의는 채팅으로 주세요. 일련번호 ', @p_offset + seq),
  5000 + (seq % 100) * 5000,
  -- 조회수: 1%가 인기 상품. 상세 조회 시 같은 row 에 UPDATE 경합이 생기는 구간을 만든다
  CASE WHEN seq % @hot_ratio = 0 THEN 5000 + (seq % 5000) ELSE seq % 100 END,
  seq % 30,
  b'0',
  CASE WHEN seq % 20 = 0 THEN 'RESERVED' WHEN seq % 50 = 0 THEN 'COMPLETED' ELSE 'ON_SALE' END,
  -- 등록 시각: 절반을 최근 30일에. 커서 페이징이 최신부터 훑으므로 분포가 결과를 좌우한다
  CASE WHEN seq % 2 = 0
       THEN NOW(6) - INTERVAL (seq % 30) DAY
       ELSE NOW(6) - INTERVAL (30 + (seq % 335)) DAY
  END,
  NOW(6)
FROM (
  SELECT (d6.n * 100000 + d5.n * 10000 + d4.n * 1000 + d3.n * 100 + d2.n * 10 + d1.n) + 1 AS seq
  FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d5
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d6
) nums
WHERE seq <= @add_rows;

DROP TEMPORARY TABLE IF EXISTS _hot_regions;
DROP TEMPORARY TABLE IF EXISTS _all_regions;

SELECT CONCAT('적재 완료 — 이번 추가 ', @add_rows, '건, 볼륨 상품 누계 ',
              (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%'), '건') AS result;
