package com.dongnemarket.manner.repository

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.config.JpaAuditingConfig
import com.dongnemarket.manner.entity.MannerRating
import com.dongnemarket.manner.entity.MannerScore
import com.dongnemarket.manner.entity.MannerScoreChangeReason
import com.dongnemarket.manner.entity.MannerScoreHistory
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [통합] manner 리포지토리들의 커스텀 @Query 메서드. 특히 `hasPenaltySince`는 JPQL 안에
 * enum 상수를 완전한 클래스명(FQN)으로 직접 박아 넣은 형태라(문자열이라 컴파일 시점에 검증되지 않음)
 * 실제로 파싱·실행되는지 실행 테스트로 반드시 확인해야 한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig::class)
class MannerRepositoryTest {
    @Autowired
    lateinit var mannerScoreRepository: MannerScoreRepository

    @Autowired
    lateinit var mannerScoreHistoryRepository: MannerScoreHistoryRepository

    @Autowired
    lateinit var mannerRatingRepository: MannerRatingRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Test
    fun `findAllByScoreLessThanEqualOrderByScoreAsc는 threshold 이하인 회원만 낮은 순으로 반환한다`() {
        val low = memberRepository.save(Member.createUser("manner-low@example.com", "pw", "저신뢰"))
        val high = memberRepository.save(Member.createUser("manner-high@example.com", "pw", "고신뢰"))
        saveScore(low, BigDecimal.valueOf(10.0))
        saveScore(high, BigDecimal.valueOf(90.0))

        val result = mannerScoreRepository.findAllByScoreLessThanEqualOrderByScoreAsc(BigDecimal.valueOf(20.0))

        assertThat(result).hasSize(1)
        assertThat(result[0].member.id).isEqualTo(low.id)
    }

    @Test
    fun `hasPenaltySince는 REPORT_CONFIRMED, FALSE_REPORT_PENALTY 이력이 있으면 true를 반환한다`() {
        val member = memberRepository.save(Member.createUser("manner-penalty@example.com", "pw", "회원"))
        mannerScoreHistoryRepository.save(
            MannerScoreHistory.of(member, BigDecimal.valueOf(-1.0), MannerScoreChangeReason.REPORT_CONFIRMED),
        )

        val hasPenalty = mannerScoreHistoryRepository.hasPenaltySince(member.id!!, LocalDateTime.now().minusDays(30))

        assertThat(hasPenalty).isTrue()
    }

    @Test
    fun `hasPenaltySince는 RATING_RECEIVED 같은 페널티 아닌 이력만 있으면 false를 반환한다`() {
        val member = memberRepository.save(Member.createUser("manner-no-penalty@example.com", "pw", "회원"))
        mannerScoreHistoryRepository.save(
            MannerScoreHistory.of(member, BigDecimal.valueOf(0.2), MannerScoreChangeReason.RATING_RECEIVED),
        )

        val hasPenalty = mannerScoreHistoryRepository.hasPenaltySince(member.id!!, LocalDateTime.now().minusDays(30))

        assertThat(hasPenalty).isFalse()
    }

    @Test
    fun `countByMemberAndReasonSince는 지정한 사유의 이력 건수만 센다`() {
        val member = memberRepository.save(Member.createUser("manner-count@example.com", "pw", "회원"))
        mannerScoreHistoryRepository.save(
            MannerScoreHistory.of(member, BigDecimal.valueOf(-1.0), MannerScoreChangeReason.REPORT_CONFIRMED),
        )
        mannerScoreHistoryRepository.save(
            MannerScoreHistory.of(member, BigDecimal.valueOf(0.1), MannerScoreChangeReason.TRADE_COMPLETED),
        )

        val count =
            mannerScoreHistoryRepository.countByMemberAndReasonSince(
                member.id!!,
                MannerScoreChangeReason.REPORT_CONFIRMED,
                LocalDateTime.now().minusDays(90),
            )

        assertThat(count).isEqualTo(1L)
    }

    @Test
    fun `countByRateeSince는 판매자가 받은 별점 건수를 센다`() {
        val seller = memberRepository.save(Member.createUser("manner-seller@example.com", "pw", "판매자"))
        val buyer = memberRepository.save(Member.createUser("manner-buyer@example.com", "pw", "구매자"))
        val category = categoryRepository.save(Category("매너평점테스트카테고리"))
        val region = saveTestRegion()
        val product =
            productRepository.save(Product.create(seller, category, "상품", "설명", BigDecimal.valueOf(10000), region))
        mannerRatingRepository.save(MannerRating.of(product, buyer, seller, 5))

        val count = mannerRatingRepository.countByRateeSince(seller.id!!, LocalDateTime.now().minusDays(30))

        assertThat(count).isEqualTo(1L)
    }

    private fun saveScore(
        member: Member,
        score: BigDecimal,
    ): MannerScore {
        val mannerScore = MannerScore.createDefault(member)
        org.springframework.test.util.ReflectionTestUtils
            .setField(mannerScore, "score", score)
        return mannerScoreRepository.save(mannerScore)
    }

    private fun saveTestRegion(): Region {
        val seoul =
            regionRepository
                .findByCode("1100000000")
                .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }
        val gangnam =
            regionRepository
                .findByCode("1168000000")
                .orElseGet { regionRepository.save(Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")) }
        return regionRepository
            .findByCode("1168010100")
            .orElseGet { regionRepository.save(Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")) }
    }
}
