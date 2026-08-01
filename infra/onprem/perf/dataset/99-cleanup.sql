-- 볼륨 데이터만 지운다. 마커가 없는 것(데모 시더가 만든 6건, 마스터 데이터, 실제 계정)은 건드리지 않는다.
--
-- 실행:  /bin/bash -c 'source lib/common.sh; mysql_file dataset/99-cleanup.sql'
--
-- 마커 — 상품 title '[perf] ' · 회원 email 'perf-' · 댓글/신고/알림 content·message '[perf]'

SELECT CONCAT('정리 전 — 상품 ', (SELECT COUNT(*) FROM products WHERE title LIKE '[perf]%'),
              ' / 찜 ', (SELECT COUNT(*) FROM favorites),
              ' / 댓글 ', (SELECT COUNT(*) FROM comments WHERE content LIKE '[perf]%'),
              ' / 알림 ', (SELECT COUNT(*) FROM notifications WHERE message LIKE '[perf]%'),
              ' / 신고 ', (SELECT COUNT(*) FROM reports WHERE content LIKE '[perf]%')) AS before_state;

-- 자식 먼저. FK 가 걸린 것을 남기면 상품·회원 삭제가 실패한다.
DELETE FROM notifications WHERE message LIKE '[perf]%';
DELETE FROM reports       WHERE content LIKE '[perf]%';
DELETE FROM comments      WHERE content LIKE '[perf]%';
DELETE FROM favorites     WHERE product_id IN (SELECT id FROM products WHERE title LIKE '[perf]%');
DELETE FROM favorites     WHERE member_id  IN (SELECT id FROM members  WHERE email LIKE 'perf-%');

DELETE FROM member_locations WHERE member_id IN (SELECT id FROM members WHERE email LIKE 'perf-%');
DELETE FROM products WHERE title LIKE '[perf]%';
DELETE FROM members  WHERE email LIKE 'perf-%';

SELECT CONCAT('정리 후 — 남은 상품 ', (SELECT COUNT(*) FROM products),
              ' / 회원 ', (SELECT COUNT(*) FROM members),
              ' / 찜 ', (SELECT COUNT(*) FROM favorites),
              ' / 댓글 ', (SELECT COUNT(*) FROM comments),
              ' / 신고 ', (SELECT COUNT(*) FROM reports)) AS after_state;

-- 삭제만으로는 테이블 파일이 줄지 않는다(InnoDB 가 공간을 재사용용으로 들고 있는다).
-- DB 크기를 실제로 되돌리려면 OPTIMIZE TABLE products, comments, reports, notifications, favorites;
