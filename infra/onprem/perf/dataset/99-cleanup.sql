-- 볼륨 데이터만 지운다. 마커가 없는 것(데모 시더가 만든 6건, 마스터 데이터, 실제 계정)은 건드리지 않는다.
--
-- 실행:  bash -c 'source lib/common.sh; mysql_file dataset/99-cleanup.sql'
--
-- 마커: 상품 title '[perf] ', 회원 email 'perf-'

SELECT CONCAT('정리 전 — 볼륨 상품 ',
              (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%'), '건, 볼륨 회원 ',
              (SELECT COUNT(*) FROM members WHERE email LIKE 'perf-%'), '명') AS before_state;

-- 자식 먼저. FK가 걸린 테이블을 남겨두면 상품 삭제가 실패한다.
DELETE FROM comments  WHERE product_id IN (SELECT id FROM products WHERE title LIKE '[perf]%');
DELETE FROM favorites WHERE product_id IN (SELECT id FROM products WHERE title LIKE '[perf]%');

DELETE FROM products WHERE title LIKE '[perf]%';
DELETE FROM members  WHERE email LIKE 'perf-%';

SELECT CONCAT('정리 후 — 볼륨 상품 ',
              (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%'), '건, 남은 상품 전체 ',
              (SELECT COUNT(*) FROM products), '건') AS after_state;

-- 삭제만으로는 테이블 파일이 줄지 않는다(InnoDB는 공간을 재사용용으로 들고 있는다).
-- DB 크기를 실제로 되돌리려면 OPTIMIZE TABLE products; 를 따로 실행할 것.
-- 계단 실험 중에는 굳이 하지 않아도 된다 — 다음 적재가 그 공간을 다시 쓴다.
