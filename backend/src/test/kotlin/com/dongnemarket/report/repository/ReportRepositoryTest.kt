package com.dongnemarket.report.repository

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.config.JpaAuditingConfig
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportType
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * findAllByReporter의 N+1 방지(fetch join EntityGraph)를 실제 쿼리 통계로 검증한다.
 * targetProduct/targetMember 지연 로딩 필드를 신고 건수만큼 순회하며 읽어도,
 * 실행되는 SQL 문 개수가 신고 건수에 비례해 늘어나지 않아야 한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig::class)
class ReportRepositoryTest {
    @Autowired
    lateinit var reportRepository: ReportRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var entityManager: EntityManager

    @Test
    fun `신고가 여러 건이어도 targetProduct와 targetMember를 모두 읽는 데 쿼리 수가 늘어나지 않는다 (N+1 방지)`() {
        val reporter = memberRepository.save(Member.createUser("reporter@example.com", "pw", "신고자"))
        val seller = memberRepository.save(Member.createUser("seller@example.com", "pw", "판매자"))
        val targetMember = memberRepository.save(Member.createUser("target@example.com", "pw", "신고대상"))
        // 실제 시드 데이터(CategorySeeder)의 카테고리명과 겹치면, 같은 테스트 세션에서 먼저 뜬
        // @SpringBootTest 컨텍스트가 이미 커밋해 둔 행과 유니크 제약이 충돌할 수 있어 테스트 전용 이름을 쓴다.
        val category = categoryRepository.save(Category("리포트N+1테스트카테고리"))

        val productReportCount = 5
        repeat(productReportCount) { i ->
            val product =
                productRepository.save(
                    Product.create(seller, category, "상품 $i", "설명", java.math.BigDecimal.valueOf(10000), saveYeoksam()),
                )
            reportRepository.save(Report.ofProduct(reporter, product, ReportReason.FAKE_ITEM, "신고 $i"))
        }
        reportRepository.save(Report.ofMember(reporter, targetMember, ReportReason.FRAUD_SUSPECTED, "회원 신고"))
        entityManager.flush()
        entityManager.clear()

        val sessionFactory = entityManager.entityManagerFactory.unwrap(SessionFactory::class.java)
        val statistics = sessionFactory.statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        val reports = reportRepository.findAllByReporter(reporter)
        reports.forEach { report ->
            if (report.reportType == ReportType.PRODUCT) {
                report.targetProduct!!.id
            } else {
                report.targetMember!!.id
            }
        }

        val queryCount = statistics.prepareStatementCount

        assertThat(reports).hasSize(productReportCount + 1)
        // EntityGraph 없이 지연 로딩만 썼다면 목록 조회 1 + 신고 건수(6)만큼의 추가 SELECT가 필요했을 것.
        // fetch join으로 신고 건수와 무관하게 쿼리 수가 일정하게(2건 이하) 유지되는지 확인한다.
        assertThat(queryCount).isLessThanOrEqualTo(2)
    }

    private fun saveYeoksam(): Region {
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
