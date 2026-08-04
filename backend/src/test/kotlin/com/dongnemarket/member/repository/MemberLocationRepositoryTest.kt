package com.dongnemarket.member.repository

import com.dongnemarket.global.config.JpaAuditingConfig
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberLocation
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig::class)
class MemberLocationRepositoryTest {

    @Autowired
    lateinit var memberLocationRepository: MemberLocationRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Test
    fun `회원 동네 목록을 정렬 순서 오름차순으로 조회한다`() {
        val member = saveMember("order")
        val second = memberLocationRepository.save(MemberLocation.create(member, saveYeonnam(), 1, false))
        val first = memberLocationRepository.save(MemberLocation.create(member, saveYeoksam(), 0, true))

        val locations = memberLocationRepository.findAllByMemberIdOrderBySortOrderAsc(member.id)

        assertThat(locations).containsExactly(first, second)
        assertThat(locations.map { it.regionCode })
            .containsExactly("1168010100", "1144012400")
    }

    @Test
    fun `회원 ID 기준으로 대상 회원 동네만 삭제하고 다른 회원 동네는 유지한다`() {
        val targetMember = saveMember("delete-target")
        val otherMember = saveMember("delete-other")
        memberLocationRepository.save(MemberLocation.create(targetMember, saveYeoksam(), 0, true))
        memberLocationRepository.save(MemberLocation.create(targetMember, saveYeonnam(), 1, false))
        memberLocationRepository.save(MemberLocation.create(otherMember, saveJamsil(), 0, true))

        memberLocationRepository.deleteAllByMemberId(targetMember.id)

        assertThat(memberLocationRepository.findAllByMemberIdOrderBySortOrderAsc(targetMember.id)).isEmpty()
        val otherLocations = memberLocationRepository.findAllByMemberIdOrderBySortOrderAsc(otherMember.id)
        assertThat(otherLocations).hasSize(1)
        assertThat(otherLocations[0].regionCode).isEqualTo("1171010100")
        assertThat(otherLocations[0].regionFullName).isEqualTo("서울특별시 송파구 잠실동")
        assertThat(otherLocations[0].isActive).isTrue()
    }

    @Test
    fun `한 회원은 같은 지역을 중복 저장할 수 없다`() {
        val member = saveMember("unique")
        val region = saveYeoksam()
        memberLocationRepository.saveAndFlush(MemberLocation.create(member, region, 0, true))

        assertThatThrownBy {
            memberLocationRepository.save(MemberLocation.create(member, region, 1, false))
            memberLocationRepository.flush()
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun saveMember(prefix: String): Member {
        val unique = UUID.randomUUID().toString()
        val email = "$prefix-$unique@example.com"
        val nickname = prefix.substring(0, minOf(prefix.length, 8)) + unique.substring(0, 8)
        return memberRepository.save(Member.createUser(email, "encodedPassword", nickname))
    }

    private fun saveSeoul(): Region =
        regionRepository.findByCode("1100000000")
            .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }

    private fun saveGangnam(): Region =
        regionRepository.findByCode("1168000000")
            .orElseGet { regionRepository.save(Region.child("1168000000", 2, saveSeoul(), "서울특별시 강남구", "강남구")) }

    private fun saveMapo(): Region =
        regionRepository.findByCode("1144000000")
            .orElseGet { regionRepository.save(Region.child("1144000000", 2, saveSeoul(), "서울특별시 마포구", "마포구")) }

    private fun saveSongpa(): Region =
        regionRepository.findByCode("1171000000")
            .orElseGet { regionRepository.save(Region.child("1171000000", 2, saveSeoul(), "서울특별시 송파구", "송파구")) }

    private fun saveYeoksam(): Region = saveDong(saveGangnam(), "1168010100", "서울특별시 강남구 역삼동", "역삼동")

    private fun saveYeonnam(): Region = saveDong(saveMapo(), "1144012400", "서울특별시 마포구 연남동", "연남동")

    private fun saveJamsil(): Region = saveDong(saveSongpa(), "1171010100", "서울특별시 송파구 잠실동", "잠실동")

    private fun saveDong(
        parent: Region,
        code: String,
        fullName: String,
        displayName: String,
    ): Region =
        regionRepository.findByCode(code)
            .orElseGet { regionRepository.save(Region.child(code, 3, parent, fullName, displayName)) }
}
