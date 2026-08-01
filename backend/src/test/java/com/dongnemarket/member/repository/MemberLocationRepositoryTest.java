package com.dongnemarket.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.dongnemarket.global.config.JpaAuditingConfig;
import com.dongnemarket.member.entity.Member;
import com.dongnemarket.member.entity.MemberLocation;
import com.dongnemarket.region.entity.Region;
import com.dongnemarket.region.repository.RegionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class MemberLocationRepositoryTest {

	@Autowired
	MemberLocationRepository memberLocationRepository;

	@Autowired
	MemberRepository memberRepository;

	@Autowired
	RegionRepository regionRepository;

	@Test
	@DisplayName("회원 동네 목록을 정렬 순서 오름차순으로 조회한다")
	void findsMemberLocationsOrderBySortOrderAsc() {
		Member member = saveMember("order");
		MemberLocation second = memberLocationRepository.save(MemberLocation.create(member, saveYeonnam(), 1, false));
		MemberLocation first = memberLocationRepository.save(MemberLocation.create(member, saveYeoksam(), 0, true));

		List<MemberLocation> locations = memberLocationRepository.findAllByMemberIdOrderBySortOrderAsc(member.getId());

		assertThat(locations).containsExactly(first, second);
		assertThat(locations).extracting(MemberLocation::getRegionCode)
				.containsExactly("1168010100", "1144012400");
	}

	@Test
	@DisplayName("회원 ID 기준으로 대상 회원 동네만 삭제하고 다른 회원 동네는 유지한다")
	void deletesOnlyTargetMemberLocationsByMemberId() {
		Member targetMember = saveMember("delete-target");
		Member otherMember = saveMember("delete-other");
		memberLocationRepository.save(MemberLocation.create(targetMember, saveYeoksam(), 0, true));
		memberLocationRepository.save(MemberLocation.create(targetMember, saveYeonnam(), 1, false));
		memberLocationRepository.save(MemberLocation.create(otherMember, saveJamsil(), 0, true));

		memberLocationRepository.deleteAllByMemberId(targetMember.getId());

		assertThat(memberLocationRepository.findAllByMemberIdOrderBySortOrderAsc(targetMember.getId())).isEmpty();
		List<MemberLocation> otherLocations = memberLocationRepository.findAllByMemberIdOrderBySortOrderAsc(otherMember.getId());
		assertThat(otherLocations).hasSize(1);
		assertThat(otherLocations.get(0).getRegionCode()).isEqualTo("1171010100");
		assertThat(otherLocations.get(0).getRegionFullName()).isEqualTo("서울특별시 송파구 잠실동");
		assertThat(otherLocations.get(0).isActive()).isTrue();
	}

	@Test
	@DisplayName("한 회원은 같은 지역을 중복 저장할 수 없다")
	void rejectsDuplicateRegionForSameMember() {
		Member member = saveMember("unique");
		Region region = saveYeoksam();
		memberLocationRepository.saveAndFlush(MemberLocation.create(member, region, 0, true));

		assertThatThrownBy(() -> {
			memberLocationRepository.save(MemberLocation.create(member, region, 1, false));
			memberLocationRepository.flush();
		}).isInstanceOf(DataIntegrityViolationException.class);
	}

	private Member saveMember(String prefix) {
		String unique = UUID.randomUUID().toString();
		String email = prefix + "-" + unique + "@example.com";
		String nickname = prefix.substring(0, Math.min(prefix.length(), 8)) + unique.substring(0, 8);
		return memberRepository.save(Member.createUser(email, "encodedPassword", nickname));
	}

	private Region saveSeoul() {
		return regionRepository.findByCode("1100000000")
				.orElseGet(() -> regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")));
	}

	private Region saveGangnam() {
		return regionRepository.findByCode("1168000000")
				.orElseGet(() -> regionRepository.save(Region.child("1168000000", 2, saveSeoul(), "서울특별시 강남구", "강남구")));
	}

	private Region saveMapo() {
		return regionRepository.findByCode("1144000000")
				.orElseGet(() -> regionRepository.save(Region.child("1144000000", 2, saveSeoul(), "서울특별시 마포구", "마포구")));
	}

	private Region saveSongpa() {
		return regionRepository.findByCode("1171000000")
				.orElseGet(() -> regionRepository.save(Region.child("1171000000", 2, saveSeoul(), "서울특별시 송파구", "송파구")));
	}

	private Region saveYeoksam() {
		return saveDong(saveGangnam(), "1168010100", "서울특별시 강남구 역삼동", "역삼동");
	}

	private Region saveYeonnam() {
		return saveDong(saveMapo(), "1144012400", "서울특별시 마포구 연남동", "연남동");
	}

	private Region saveJamsil() {
		return saveDong(saveSongpa(), "1171010100", "서울특별시 송파구 잠실동", "잠실동");
	}

	private Region saveDong(Region parent, String code, String fullName, String displayName) {
		return regionRepository.findByCode(code)
				.orElseGet(() -> regionRepository.save(Region.child(code, 3, parent, fullName, displayName)));
	}
}
