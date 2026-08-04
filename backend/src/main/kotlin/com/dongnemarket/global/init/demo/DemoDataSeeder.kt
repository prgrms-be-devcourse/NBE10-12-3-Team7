package com.dongnemarket.global.init.demo

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.comment.entity.Comment
import com.dongnemarket.comment.repository.CommentRepository
import com.dongnemarket.global.init.DataSeeder
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.repository.ReportRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * [개발/검증 전용] 관리자 콘솔 확인용 더미 데이터 시더.
 *
 * 실행 조건: `app.seed.demo=true` 이면서 test 프로파일이 아닐 때만.
 * SeedOrchestrator 가 마스터/부트스트랩 시더(order 10~20) 커밋 이후(order 30) 호출하므로
 * 카테고리·지역 존재가 보장된다. 멱등 가드로 재실행 시 중복 시딩을 막는다.
 *
 * ⚠️ `@Profile("!test")` + `@ConditionalOnProperty` 라 테스트가 이 클래스를 한 번도 실행하지 않는다.
 * 전환의 방어선이 컴파일러뿐이므로 `app.seed.demo=true` 로 앱을 띄워 수동 확인해야 한다.
 * `member` 도메인이 아직 Java 라 `Member.createUser/createAdmin` 은 플랫폼 타입으로 다룬다.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = ["app.seed.demo"], havingValue = "true")
class DemoDataSeeder(
    private val memberRepository: MemberRepository,
    private val categoryRepository: CategoryRepository,
    private val productRepository: ProductRepository,
    private val regionRepository: RegionRepository,
    private val commentRepository: CommentRepository,
    private val reportRepository: ReportRepository,
    private val passwordEncoder: PasswordEncoder,
) : DataSeeder {
    override fun order(): Int = 30

    @Transactional
    override fun seed() {
        // 멱등 가드: 이미 시드돼 있으면 아무것도 하지 않는다.
        if (memberRepository.existsByEmail(SENTINEL_EMAIL)) {
            return
        }

        // ── 2단계: 회원 6명 ──────────────────────────────
        val userPw = passwordEncoder.encode("user1234!")
        val adminPw = passwordEncoder.encode("admin1234!")

        val user01 = memberRepository.save(Member.createUser("user01@dongnemarket.com", userPw, "상민"))
        val user02 = memberRepository.save(Member.createUser("user02@dongnemarket.com", userPw, "지훈"))
        val user03 = memberRepository.save(Member.createUser("user03@dongnemarket.com", userPw, "민서"))

        val user04 = memberRepository.save(Member.createUser("user04@dongnemarket.com", userPw, "철수"))
        user04.changeStatus(MemberStatus.SUSPENDED) // 정지

        val user05 = memberRepository.save(Member.createUser("user05@dongnemarket.com", userPw, "영희"))
        user05.changeStatus(MemberStatus.DELETED) // 소프트삭제(deletedAt 자동)

        memberRepository.save(Member.createAdmin("admin2@dongnemarket.com", adminPw, "부관리자"))

        // ── 3단계: 상품 6건 (기존 카테고리 8종을 이름으로 재사용) ──
        // getValue 는 없으면 NoSuchElementException 을 던진다. Map.get 의 `Category?` 는
        // Product.create(category: Category) 에 넣을 수 없다 — product 가 Kotlin 이 되며 non-null 로 확정됐다.
        // (원본 Java 는 cat.get(...) 이 null 이어도 그대로 넘어가 나중에 터졌을 것이다.)
        val cat: Map<String, Category> = categoryRepository.findAllByOrderByIdAsc().associateBy { it.name }

        val p1 =
            productRepository.save(
                Product.create(
                    user03,
                    cat.getValue("디지털기기"),
                    "아이폰 13 128GB",
                    "생활기스 있으나 정상 작동합니다.",
                    BigDecimal.valueOf(450_000L),
                    requiredRegion("1168010100"),
                ),
            )
        addViews(p1, 152)

        val p2 =
            productRepository.save(
                Product.create(
                    user01,
                    cat.getValue("가구/인테리어"),
                    "원목 책상 의자",
                    "1년 사용, 상태 양호.",
                    BigDecimal.valueOf(60_000L),
                    requiredRegion("1144012400"),
                ),
            )
        p2.changeTradeStatus(TradeStatus.RESERVED)
        addViews(p2, 43)

        val p3 =
            productRepository.save(
                Product.create(
                    user03,
                    cat.getValue("디지털기기"),
                    "에어팟 프로 2세대",
                    "정품, 구성품 모두 포함.",
                    BigDecimal.valueOf(180_000L),
                    requiredRegion("1168010100"),
                ),
            )
        p3.complete()
        addViews(p3, 88)

        val p4 =
            productRepository.save(
                Product.create(
                    user01,
                    cat.getValue("의류"),
                    "겨울 패딩 (L)",
                    "따뜻한 롱패딩입니다.",
                    BigDecimal.valueOf(90_000L),
                    requiredRegion("1144012400"),
                ),
            )
        p4.hide()
        addViews(p4, 12)

        val p5 =
            productRepository.save(
                Product.create(
                    user04,
                    cat.getValue("스포츠/레저"),
                    "캠핑 텐트 4인용",
                    "방수 우수, 몇 회 사용.",
                    BigDecimal.valueOf(120_000L),
                    requiredRegion("4113511400"),
                ),
            )
        p5.softDelete()
        addViews(p5, 5)

        val p6 =
            productRepository.save(
                Product.create(
                    user02,
                    cat.getValue("반려동물용품"),
                    "강아지 사료 5kg",
                    "미개봉 새 제품.",
                    BigDecimal.valueOf(35_000L),
                    requiredRegion("1171010100"),
                ),
            )
        addViews(p6, 27)

        // ── 4단계: 댓글 5건 (정상 + 삭제) ──────────────
        commentRepository.save(Comment.of(user02, p1, "관심있어요! 네고 가능한가요?"))
        commentRepository.save(Comment.of(user01, p1, "직거래 가능합니다"))
        commentRepository.save(Comment.of(user02, p3, "상태 좋네요, 잘 쓸게요"))
        val c4 = commentRepository.save(Comment.of(user03, p4, "부적절 내용 예시"))
        c4.softDelete()
        commentRepository.save(Comment.of(user01, p6, "우리 강아지가 잘 먹어요"))

        // ── 4단계: 신고 5건 (상태 4종 + 유형 2종) ───────
        reportRepository.save(
            Report.ofProduct(user02, p1, ReportReason.FRAUD_SUSPECTED, "사기 의심됩니다"), // RECEIVED
        )

        val r2 = reportRepository.save(Report.ofProduct(user01, p4, ReportReason.PROHIBITED_ITEM, "금지 품목 같아요"))
        r2.changeStatus(ReportStatus.REVIEWING)

        val r3 = reportRepository.save(Report.ofMember(user03, user04, ReportReason.INAPPROPRIATE_CONTENT, "부적절한 언행"))
        r3.changeStatus(ReportStatus.COMPLETED)

        val r4 = reportRepository.save(Report.ofProduct(user02, p3, ReportReason.FAKE_ITEM, "가품 의심"))
        r4.changeStatus(ReportStatus.REJECTED)

        reportRepository.save(
            Report.ofMember(user01, user02, ReportReason.ETC, "기타 신고"), // RECEIVED
        )

        log.info("[DemoData] seeded: members=6, products=6, comments=5, reports=5")
    }

    /** viewCount 세터가 없어 증가 메서드를 반복 호출(검증용, 소량). */
    private fun addViews(
        product: Product,
        count: Int,
    ) {
        repeat(count) { product.increaseViewCount() }
    }

    private fun requiredRegion(code: String): Region =
        regionRepository
            .findByCode(code)
            .orElseThrow { IllegalStateException("데모 데이터 지역을 찾을 수 없습니다: $code") }

    companion object {
        private val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)
        private const val SENTINEL_EMAIL = "user01@dongnemarket.com"
    }
}
