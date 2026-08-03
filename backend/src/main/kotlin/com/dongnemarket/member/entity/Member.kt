package com.dongnemarket.member.entity

import com.dongnemarket.global.common.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 회원. 여러 도메인이 `@ManyToOne(LAZY)` 로 참조하는 핵심 엔티티다.
 *
 * `data class` 가 아니라 `class` — equals/hashCode 가 지연 로딩·JPA 동일성과 어긋난다.
 * 상태를 바꾸는 프로퍼티는 `var` + `protected set` 이라 외부에서는 아래 메서드로만 변경한다
 * (원본 Java 도 setter 없이 의도가 드러나는 메서드만 노출했다).
 *
 * [isLocalLoginEnabled] 의 `@Column(name = "local_login_enabled")` 가 필수인 이유: Kotlin 은
 * 프로퍼티 이름 하나로 필드명과 게터명을 함께 결정한다. Java 호출부가 쓰는 `isLocalLoginEnabled()`
 * 게터를 유지하려면 프로퍼티명이 `isLocalLoginEnabled` 여야 하는데, 그러면 컬럼이
 * `is_local_login_enabled` 가 된다. 실제 컬럼은 `local_login_enabled`(V6__add_social_login_support.sql)
 * 이므로 이름을 명시해 고정한다. 지연 로딩 프록시 대상이라 `@get:JvmName` 은 쓰지 않는다
 * (붙이면 getter 가 final 이 되어 프록시가 깨진다 — MemberSocialAccount 의 규칙과 동일).
 */
@Entity
@Table(name = "members")
class Member private constructor(
    @field:Column(nullable = false, unique = true, length = 100)
    val email: String,
    password: String,
    nickname: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    val role: Role,
    status: MemberStatus,
    /**
     * 비밀번호 기반 로그인이 가능한 계정인지 여부. 소셜 전용 가입 회원은 false로 생성되며,
     * `password` 컬럼에는 로그인에 쓰이지 않는 더미 해시가 들어있다.
     * 로그인/비밀번호 재설정/비밀번호 변경 등 비밀번호 관련 진입점은 반드시 이 값을 먼저 확인한다.
     */
    @field:Column(name = "local_login_enabled", nullable = false)
    val isLocalLoginEnabled: Boolean,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(nullable = false, length = 100)
    var password: String = password
        protected set

    @field:Column(nullable = false, unique = true, length = 20)
    var nickname: String = nickname
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    var status: MemberStatus = status
        protected set

    @field:Column
    var deletedAt: LocalDateTime? = null
        protected set

    fun update(nickname: String) {
        this.nickname = nickname
    }

    /** 비밀번호 변경. 이미 인코딩된 값을 받는다(인코딩 책임은 Service). */
    fun changePassword(encodedPassword: String) {
        this.password = encodedPassword
    }

    fun softDelete() {
        status = MemberStatus.DELETED
        deletedAt = LocalDateTime.now()
    }

    /** 관리자에 의한 회원 상태 변경 */
    fun changeStatus(status: MemberStatus) {
        this.status = status
        deletedAt = if (status == MemberStatus.DELETED) LocalDateTime.now() else null
    }

    /**
     * 탈퇴(DELETED)한 회원인지 여부. "탈퇴" 판정의 단일 기준점으로, 닉네임 마스킹·채팅 전송 차단 등
     * 탈퇴 여부에 반응하는 모든 지점이 이 프로퍼티를 사용한다.
     * (SUSPENDED는 관리자 정지일 뿐 탈퇴가 아니므로 여기에 포함하지 않는다.)
     */
    val isWithdrawn: Boolean
        get() = status == MemberStatus.DELETED

    /**
     * 화면 표시용 닉네임. 탈퇴한 회원은 실명 닉네임 대신 마스킹 문구를 반환한다.
     * 닉네임이 노출되는 모든 지점(채팅 상대·판매자 등)에서 이 프로퍼티를 사용해 마스킹을 일관 적용한다.
     */
    val displayNickname: String
        get() = if (isWithdrawn) WITHDRAWN_NICKNAME else nickname

    companion object {
        /** 탈퇴 회원의 표시용 닉네임 마스킹 문구. */
        private const val WITHDRAWN_NICKNAME = "탈퇴한 사용자"

        /** 회원가입 시 일반 사용자(ROLE_USER, ACTIVE) 생성 */
        @JvmStatic
        fun createUser(
            email: String,
            password: String,
            nickname: String,
        ): Member = Member(email, password, nickname, Role.ROLE_USER, MemberStatus.ACTIVE, true)

        /** 관리자 계정 시드용 (ROLE_ADMIN, ACTIVE) */
        @JvmStatic
        fun createAdmin(
            email: String,
            password: String,
            nickname: String,
        ): Member = Member(email, password, nickname, Role.ROLE_ADMIN, MemberStatus.ACTIVE, true)

        /**
         * 소셜 로그인 최초 가입 시 생성(ROLE_USER, ACTIVE, 로컬 로그인 불가).
         * @param dummyPasswordHash 로그인에 쓰이지 않는 더미 해시. 원문은 어디에도 저장하지 않는다(호출부 책임).
         */
        @JvmStatic
        fun createSocialUser(
            email: String,
            dummyPasswordHash: String,
            nickname: String,
        ): Member = Member(email, dummyPasswordHash, nickname, Role.ROLE_USER, MemberStatus.ACTIVE, false)
    }
}
