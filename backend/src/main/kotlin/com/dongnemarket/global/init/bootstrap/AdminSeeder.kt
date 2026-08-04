package com.dongnemarket.global.init.bootstrap

import com.dongnemarket.global.init.DataSeeder
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 부트스트랩: 관리자(ROLE_ADMIN) 계정 1개를 시드한다(멱등).
 * 같은 이메일이 이미 있으면 만들지 않는다. test 프로파일에서는 실행하지 않는다.
 *
 * ⚠️ `@Profile("!test")` 라 테스트가 이 클래스를 한 번도 실행하지 않는다 —
 * 전환의 방어선이 컴파일러뿐이므로 앱을 띄워 수동 확인해야 한다.
 * `member` 도메인이 아직 Java 라 `Member.createAdmin(...)` 은 플랫폼 타입으로 다룬다.
 */
@Component
@Profile("!test")
class AdminSeeder(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
) : DataSeeder {
    override fun order(): Int = 20

    @Transactional
    override fun seed() {
        if (memberRepository.existsByEmail(ADMIN_EMAIL)) {
            return
        }
        memberRepository.save(
            Member.createAdmin(
                ADMIN_EMAIL,
                passwordEncoder.encode(ADMIN_RAW_PASSWORD),
                ADMIN_NICKNAME,
            ),
        )
    }

    companion object {
        private const val ADMIN_EMAIL = "admin@dongnemarket.com"
        private const val ADMIN_RAW_PASSWORD = "admin1234!"
        private const val ADMIN_NICKNAME = "관리자"
    }
}
