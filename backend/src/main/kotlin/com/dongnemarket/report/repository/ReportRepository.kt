package com.dongnemarket.report.repository

import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import com.dongnemarket.report.entity.Report
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface ReportRepository : JpaRepository<Report, Long> {
    fun existsByReporterAndTargetProduct(
        reporter: Member,
        targetProduct: Product,
    ): Boolean

    fun existsByReporterAndTargetMember(
        reporter: Member,
        targetMember: Member,
    ): Boolean

    /**
     * targetProduct/targetMember(둘 다 지연 로딩 @ManyToOne)를 한 번에 fetch join한다.
     * MyReportResponse.from이 신고마다 둘 중 하나를 읽는데, 이 EntityGraph 없이는
     * 신고 건수만큼 추가 SELECT가 발생하는 N+1이 생긴다.
     */
    @EntityGraph(attributePaths = ["targetProduct", "targetMember"])
    fun findAllByReporter(reporter: Member): List<Report>
}
