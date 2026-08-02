-- 동네 설정. 사용자 화면의 상품 목록은 "내 동네" 기준으로 필터되므로, 이게 없으면
-- 실제 사용 경로를 재현할 수 없다(지금 프로브의 regionCodes 는 손으로 넣은 임의값이다).
--
-- 실행:  /bin/bash -c 'source lib/common.sh; mysql_file dataset/20-member-locations.sql'
--
-- 순서: 10-products.sql 뒤. 회원과 지역이 모두 있어야 한다.

SET @test_member = (SELECT id FROM members WHERE email = 'onprem-test@example.com' LIMIT 1);
-- 상품이 가장 많이 몰린 동네. 여기를 내 동네로 잡아야 필터가 실제로 일을 한다.
SET @hot_region = (
  SELECT p.region_id FROM products p
  GROUP BY p.region_id ORDER BY COUNT(*) DESC LIMIT 1
);

-- 테스트 계정 — 활성 동네 1개
INSERT INTO member_locations (member_id, region_id, active, sort_order, created_at, updated_at)
SELECT @test_member, @hot_region, b'1', 0, NOW(6), NOW(6)
WHERE @test_member IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM member_locations WHERE member_id = @test_member);

-- 볼륨 회원 — 각자 동네 하나씩. 상위 20개 동네에 몰아 실제 분포에 맞춘다.
INSERT INTO member_locations (member_id, region_id, active, sort_order, created_at, updated_at)
SELECT m.id,
       (SELECT r.id FROM regions r WHERE r.level = 3 ORDER BY r.id LIMIT 1 OFFSET 0) + (m.id % 20),
       b'1', 0, NOW(6), NOW(6)
FROM members m
WHERE m.email LIKE 'perf-%'
  AND NOT EXISTS (SELECT 1 FROM member_locations ml WHERE ml.member_id = m.id);

SELECT CONCAT('동네 설정 — 전체 ', (SELECT COUNT(*) FROM member_locations),
              '건 / 테스트 계정 동네 코드 ',
              COALESCE((SELECT r.code FROM member_locations ml JOIN regions r ON r.id = ml.region_id
                        WHERE ml.member_id = @test_member LIMIT 1), '없음')) AS result;
