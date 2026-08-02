-- 부하테스트 데이터만 지운다. 마커가 `[load]` 인 것만 건드린다 —
-- perf 의 `[perf]` 데이터나 데모 시더가 만든 6건은 그대로 둔다.
--
-- 실행:  ./setup/unload-data.sh

SELECT CONCAT('정리 전 — 상품 ', (SELECT COUNT(*) FROM products WHERE title LIKE '[load]%'),
              ' / 회원 ', (SELECT COUNT(*) FROM members WHERE email LIKE 'load-%'),
              ' / 댓글 ', (SELECT COUNT(*) FROM comments WHERE content LIKE '[load]%')) AS before_state;

-- 자식 먼저. FK 가 걸린 것을 남기면 상품·회원 삭제가 실패한다.
DELETE FROM comments  WHERE content LIKE '[load]%';
DELETE FROM favorites WHERE product_id IN (SELECT id FROM products WHERE title LIKE '[load]%');
DELETE FROM favorites WHERE member_id  IN (SELECT id FROM members  WHERE email LIKE 'load-%');
DELETE FROM notifications WHERE product_id IN (SELECT id FROM products WHERE title LIKE '[load]%');
DELETE FROM reports   WHERE target_product_id IN (SELECT id FROM products WHERE title LIKE '[load]%');
DELETE FROM member_locations WHERE member_id IN (SELECT id FROM members WHERE email LIKE 'load-%');

DELETE FROM products WHERE title LIKE '[load]%';
DELETE FROM members  WHERE email LIKE 'load-%';

SELECT CONCAT('정리 후 — 남은 상품 ', (SELECT COUNT(*) FROM products),
              ' (그중 [perf] ', (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%'), ')') AS after_state;
