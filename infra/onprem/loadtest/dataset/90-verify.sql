-- 적재 후 검증. "넣었다"고 믿지 않고 실제로 잰다.

ANALYZE TABLE products, members, comments;

SELECT '── 건수 ──' AS `check`;
SELECT (SELECT COUNT(*) FROM products WHERE title LIKE '[load]%') AS products_load,
       (SELECT COUNT(*) FROM members  WHERE email LIKE 'load-%')  AS members_load,
       (SELECT COUNT(*) FROM comments WHERE content LIKE '[load]%') AS comments_load,
       (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%') AS products_perf_leftover;

SELECT '── 크기 (버퍼풀과 비교할 값) ──' AS `check`;
SELECT ROUND(SUM(data_length + index_length) / 1048576, 1) AS db_mib,
       ROUND(@@innodb_buffer_pool_size / 1048576) AS buffer_pool_mib
FROM information_schema.tables WHERE table_schema = DATABASE();

SELECT '── 분포 (균등하면 결과가 낙관적으로 왜곡된다) ──' AS `check`;
SELECT ROUND(SUM(category_id IN (1,4)) * 100 / COUNT(*), 1) AS top2_category_pct,
       ROUND(SUM(region_id IN (SELECT id FROM (SELECT id FROM regions WHERE level=3 ORDER BY id LIMIT 20) t))
             * 100 / COUNT(*), 1) AS top20_region_pct,
       ROUND(SUM(created_at >= NOW() - INTERVAL 30 DAY) * 100 / COUNT(*), 1) AS recent30d_pct
FROM products WHERE title LIKE '[load]%';

SELECT '── k6 가 탐색할 대상 (하드코딩하지 않는다) ──' AS `check`;
SELECT (SELECT r.code FROM products p JOIN regions r ON r.id = p.region_id
        WHERE p.title LIKE '[load]%' GROUP BY r.code ORDER BY COUNT(*) DESC LIMIT 1) AS busiest_region,
       (SELECT id FROM products WHERE title LIKE '[load]%' ORDER BY view_count DESC LIMIT 1) AS hot_product_id;
