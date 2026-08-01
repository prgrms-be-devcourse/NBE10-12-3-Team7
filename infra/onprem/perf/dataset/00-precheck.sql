-- 적재 전 전제 확인. 여기서 이상이 보이면 적재하지 않는다.
--
-- 실행:  bash -c 'source lib/common.sh; mysql_file dataset/00-precheck.sql'
--
-- 스키마가 바뀌어 컬럼이 사라졌으면 이 파일이 먼저 죽는다. 적재 SQL이 절반만 들어가
-- 어중간한 상태가 되는 것보다 시작 전에 멈추는 편이 낫다.

SELECT '── 마스터 데이터 (볼륨 데이터의 FK 대상) ──' AS `check`;
SELECT
  (SELECT COUNT(*) FROM categories)              AS categories,
  (SELECT COUNT(*) FROM regions WHERE level = 3) AS regions_lv3,
  (SELECT COUNT(*) FROM members)                 AS members;

-- 하나라도 0이면 적재가 FK 위반으로 실패한다. 앱을 한 번 기동해 마스터 시더를 돌릴 것.
SELECT
  CASE
    WHEN (SELECT COUNT(*) FROM categories) = 0              THEN '✗ categories 비어 있음 — 앱을 기동해 마스터 시더를 돌릴 것'
    WHEN (SELECT COUNT(*) FROM regions WHERE level = 3) = 0 THEN '✗ regions(level 3) 비어 있음 — 동일'
    WHEN (SELECT COUNT(*) FROM members) = 0                 THEN '✗ members 비어 있음 — 재사용할 비밀번호 해시가 없다'
    ELSE '✓ 마스터 데이터 정상'
  END AS master_state;

SELECT '── 현재 적재량 ──' AS `check`;
SELECT
  (SELECT COUNT(*) FROM products)                              AS products_total,
  (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%')   AS products_perf,
  (SELECT COUNT(*) FROM members  WHERE email LIKE 'perf-%')    AS members_perf,
  (SELECT ROUND(SUM(data_length + index_length) / 1048576, 1)
     FROM information_schema.tables WHERE table_schema = DATABASE()) AS db_mib;

-- 볼륨 데이터는 마커로 구분한다. 데모 시더가 만든 6건과 섞이지 않게 하고,
-- 정리할 때 마커만 골라 지울 수 있게 하기 위해서다.
--   상품: title 이 '[perf] ' 로 시작
--   회원: email 이 'perf-' 로 시작

SELECT '── 버퍼풀 (볼륨 설계의 기준선) ──' AS `check`;
SELECT
  ROUND(@@innodb_buffer_pool_size / 1048576) AS buffer_pool_mib,
  @@cte_max_recursion_depth                  AS cte_depth_limit;

-- cte_max_recursion_depth 가 1000이라 재귀 CTE로는 1만 건을 만들 수 없다.
-- 10-products.sql 은 숫자 테이블 CROSS JOIN 방식을 쓴다(재귀 제한이 적용되지 않는다).
