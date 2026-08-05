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

-- Kotlin 전환으로 들어온 테이블들. **여기가 빠져 있어서 회원 삭제가 FK 로 막힌 적이 있다**
-- (2026-08-05, manner_scores 2건). `members` 를 참조하는 FK 가 18개인데 이 스크립트는 6개만
-- 알고 있었다. 스키마가 늘어나면 이 목록도 늘어나야 한다 — 아래 확인 쿼리로 잡는다.
DELETE FROM manner_score_histories WHERE member_id IN (SELECT id FROM members WHERE email LIKE 'load-%');
DELETE FROM manner_ratings        WHERE rater_id IN (SELECT id FROM members WHERE email LIKE 'load-%')
                                     OR ratee_id IN (SELECT id FROM members WHERE email LIKE 'load-%');
DELETE FROM manner_scores         WHERE member_id IN (SELECT id FROM members WHERE email LIKE 'load-%');
DELETE FROM chat_messages         WHERE sender_id IN (SELECT id FROM members WHERE email LIKE 'load-%');
DELETE FROM chat_rooms            WHERE buyer_id IN (SELECT id FROM members WHERE email LIKE 'load-%')
                                     OR seller_id IN (SELECT id FROM members WHERE email LIKE 'load-%');
DELETE FROM escrows               WHERE buyer_id IN (SELECT id FROM members WHERE email LIKE 'load-%')
                                     OR seller_id IN (SELECT id FROM members WHERE email LIKE 'load-%');
DELETE FROM member_agreements     WHERE member_id IN (SELECT id FROM members WHERE email LIKE 'load-%');
DELETE FROM member_social_accounts WHERE member_id IN (SELECT id FROM members WHERE email LIKE 'load-%');

DELETE FROM products WHERE title LIKE '[load]%';
-- 쓰기 회차(s02)가 만든 것. setup/unload-write.sh 와 같은 마커다.
DELETE pi FROM product_images pi JOIN products p ON p.id = pi.product_id WHERE p.title LIKE '[load-write]%';
DELETE FROM products WHERE title LIKE '[load-write]%';
DELETE FROM members  WHERE email LIKE 'load-%';

SELECT CONCAT('정리 후 — 남은 상품 ', (SELECT COUNT(*) FROM products),
              ' (그중 [perf] ', (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%'), ')') AS after_state;
