-- 적재 후 검증. "넣었다"고 믿지 않고 실제로 잰다. 계단마다 실행해 기록에 남긴다.
--
-- 실행:  bash -c 'source lib/common.sh; mysql_file dataset/90-verify.sql'

SELECT '── 건수 ──' AS `check`;
SELECT
  (SELECT COUNT(*) FROM products)                            AS products_total,
  (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%') AS products_perf,
  (SELECT COUNT(*) FROM members  WHERE email LIKE 'perf-%')  AS members_perf,
  (SELECT COUNT(*) FROM favorites)                           AS favorites,
  (SELECT COUNT(*) FROM comments)                            AS comments,
  (SELECT COUNT(*) FROM notifications)                       AS notifications,
  (SELECT COUNT(*) FROM reports)                             AS reports;

-- 이 화면들은 전부 "me" 기준이라 전체 건수보다 한 사람에게 몰린 양이 성능을 좌우한다.
SELECT '── 테스트 계정 집중도 (화면 성능을 좌우하는 값) ──' AS `check`;
SET @tm = (SELECT id FROM members WHERE email = 'onprem-test@example.com' LIMIT 1);
SELECT
  (SELECT COUNT(*) FROM favorites WHERE member_id = @tm)                          AS my_favorites,
  (SELECT COUNT(*) FROM notifications WHERE recipient_id = @tm AND is_read = b'0') AS my_unread,
  (SELECT COUNT(*) FROM reports WHERE reporter_id = @tm)                          AS my_reports,
  (SELECT COUNT(*) FROM comments WHERE product_id =
     (SELECT id FROM products WHERE title LIKE '[perf]%' ORDER BY view_count DESC LIMIT 1)) AS hot_product_comments;

-- information_schema 의 행수·크기는 InnoDB 추정치라 적재 직후에는 갱신돼 있지 않다(0으로 보인다).
-- 계단마다 크기를 기록해야 하므로 먼저 통계를 갱신한다.
ANALYZE TABLE products, members, comments, favorites, notifications, reports;

SELECT '── 크기 (버퍼풀 128 MiB 와 비교할 값) ──' AS `check`;
SELECT
  table_name                                    AS `table`,
  table_rows                                    AS approx_rows,
  ROUND(data_length  / 1048576, 1)              AS data_mib,
  ROUND(index_length / 1048576, 1)              AS index_mib
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('products', 'members', 'comments', 'favorites', 'notifications', 'reports')
ORDER BY data_length DESC;

SELECT ROUND(SUM(data_length + index_length) / 1048576, 1) AS db_total_mib
FROM information_schema.tables WHERE table_schema = DATABASE();

SELECT '── 분포 (균등하면 결과가 낙관적으로 왜곡된다) ──' AS `check`;
SELECT
  ROUND(SUM(category_id IN (1, 4)) * 100 / COUNT(*), 1)                    AS top2_category_pct,
  ROUND(SUM(region_id IN (SELECT id FROM (SELECT id FROM regions WHERE level = 3 ORDER BY id LIMIT 20) t))
        * 100 / COUNT(*), 1)                                              AS top20_region_pct,
  ROUND(SUM(created_at >= NOW() - INTERVAL 30 DAY) * 100 / COUNT(*), 1)    AS recent30d_pct,
  SUM(view_count >= 5000)                                                 AS hot_products,
  MAX(view_count)                                                         AS max_views
FROM products WHERE title LIKE '[perf]%';

SELECT '── 거래상태 ──' AS `check`;
SELECT trade_status, COUNT(*) AS cnt
FROM products WHERE title LIKE '[perf]%' GROUP BY trade_status ORDER BY cnt DESC;
