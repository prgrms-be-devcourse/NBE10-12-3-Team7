-- 댓글. 두 화면이 대상이다.
--   상품 상세      GET /api/products/{id}/comments  — 인기 상품 하나에 몰린 댓글을 다 반환한다
--   관리자 댓글    GET /api/admin/comments          — findAll() 이라 전체를 통째로 반환한다
--
-- 그래서 두 방향으로 넣는다. 인기 상품 한 개에 집중(화면 성능) + 전체 볼륨(관리자 화면).
--
-- 실행:  /bin/bash -c 'source lib/common.sh; mysql_file dataset/40-comments.sql'
-- 순서: 10-products.sql 뒤.

-- ── 파라미터 ────────────────────────────────────────────────────────────────
SET @hot_rows   = 3000;    -- 인기 상품 1건에 달리는 댓글 수
SET @spread_all = 100000;  -- 전체에 흩뿌릴 댓글 수 (관리자 화면 볼륨)

SET @hot_product = (SELECT id FROM products WHERE title LIKE '[perf]%' ORDER BY view_count DESC LIMIT 1);
-- 상품·회원 id 는 연속이 아니다(중간에 지운 자리에 구멍이 있다). 산술로 id 를 만들면
-- FK 위반이 난다(실제로 겪었다). 실제 id 에 일련번호를 붙인 매핑 테이블을 만들어 조인으로 고른다.
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

DROP TEMPORARY TABLE IF EXISTS _seq6;
CREATE TEMPORARY TABLE _seq6 (n INT PRIMARY KEY);
INSERT INTO _seq6 (n)
SELECT (d6.n*100000 + d5.n*10000 + d4.n*1000 + d3.n*100 + d2.n*10 + d1.n) + 1
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
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d6;

-- ── 1. 인기 상품 하나에 집중 (상세 화면) ────────────────────────────────────
INSERT INTO comments (member_id, product_id, content, created_at, updated_at)
SELECT m.id, @hot_product,
       CONCAT('[perf] 댓글 ', s.n, ' 번입니다. 아직 판매 중인가요? 직거래 가능한지 궁금합니다.'),
       NOW(6) - INTERVAL (s.n % 60) DAY, NOW(6)
FROM _seq6 s JOIN _mem m ON m.rn = 1 + (s.n % @m_cnt)
WHERE s.n <= @hot_rows;

-- ── 2. 전체에 분산 (관리자 화면 볼륨) ───────────────────────────────────────
INSERT INTO comments (member_id, product_id, content, created_at, updated_at)
SELECT m.id, p.id,
       CONCAT('[perf] 댓글 ', s.n, ' 번입니다. 상태 좋아 보이네요.'),
       NOW(6) - INTERVAL (s.n % 300) DAY, NOW(6)
FROM _seq6 s
JOIN _mem  m ON m.rn = 1 + (s.n % @m_cnt)
JOIN _prod p ON p.rn = 1 + ((s.n * 13) % @p_cnt)
WHERE s.n <= @spread_all;

DROP TEMPORARY TABLE IF EXISTS _seq6;
DROP TEMPORARY TABLE IF EXISTS _prod;
DROP TEMPORARY TABLE IF EXISTS _mem;

SELECT CONCAT('댓글 — 전체 ', (SELECT COUNT(*) FROM comments),
              '건 / 인기 상품(id ', @hot_product, ') ',
              (SELECT COUNT(*) FROM comments WHERE product_id = @hot_product), '건') AS result;
