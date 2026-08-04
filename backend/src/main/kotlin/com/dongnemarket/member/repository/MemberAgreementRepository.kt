package com.dongnemarket.member.repository

import com.dongnemarket.member.entity.MemberAgreement
import org.springframework.data.jpa.repository.JpaRepository

interface MemberAgreementRepository : JpaRepository<MemberAgreement, Long>
