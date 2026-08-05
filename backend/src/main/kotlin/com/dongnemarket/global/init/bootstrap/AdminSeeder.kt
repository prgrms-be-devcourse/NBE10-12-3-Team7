package com.dongnemarket.global.init.bootstrap

import com.dongnemarket.global.init.DataSeeder
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 부트스트랩: 관리자(ROLE_ADMIN) 계정 1개를 시드한다(멱등).
 * 같은 이메일이 이미 있으면 만들지 않는다.
 *
 * ⚠️ **개발 편의 전용이다.** 자격증명을 만드는 시더라 두 겹으로 막는다.
 * - [Profile]`("dev")` — 허용 목록. 예전 `@Profile("!test")` 는 "test 만 아니면 전부"라
 *   **prod 에서도 관리자가 생겼다.** 비밀번호까지 소스에 있었고 리포가 public 이라
 *   배포된 서버에 누구나 관리자로 들어올 수 있었다. 허용 목록은 그 실수를 구조적으로 막는다.
 * - [ConditionalOnProperty] — dev 안에서도 켜야 돈다. 빈 등록 자체가 막히므로
 *   `SeedOrchestrator` 는 이 시더의 존재를 모른다(오케스트레이터에 조건 분기가 없는 이유).
 *
 * 비밀번호는 소스에 두지 않는다(`APP_SEED_ADMIN_PASSWORD`). **기본값이 없어서** 스위치만 켜고
 * 값을 안 주면 기동이 실패한다 — 빈 비밀번호 관리자가 조용히 생기는 것보다 낫다.
 *
 * **운영에는 이 시더를 쓰지 않는다.** 배포 후 관리자 1명을 수동 생성한다(`infra/infra.md` 참고).
 *
 * ⚠️ 프로파일·조건으로 걸러지므로 테스트가 이 클래스를 한 번도 실행하지 않는다 —
 * 방어선이 컴파일러뿐이라 앱을 띄워 수동 확인해야 한다.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = ["app.seed.admin"], havingValue = "true")
class AdminSeeder(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    @param:Value("\${app.seed.admin-password}") private val adminRawPassword: String,
) : DataSeeder {
    /**
     * 플레이스홀더가 "없음"은 스프링이 막아주지만 "빈 문자열"은 통과한다
     * (docker compose 가 미설정 변수를 빈 값으로 넘기는 경우). 전달 경로와 무관하게 여기서 막는다.
     */
    init {
        require(adminRawPassword.isNotBlank()) {
            "app.seed.admin-password 가 비어 있다. APP_SEED_ADMIN_PASSWORD 를 설정하거나 APP_SEED_ADMIN 을 꺼라."
        }
    }

    override fun order(): Int = 20

    @Transactional
    override fun seed() {
        if (memberRepository.existsByEmail(ADMIN_EMAIL)) {
            return
        }
        memberRepository.save(
            Member.createAdmin(
                ADMIN_EMAIL,
                passwordEncoder.encode(adminRawPassword),
                ADMIN_NICKNAME,
            ),
        )
    }

    companion object {
        /** 비밀이 아니다 — e2e·부하테스트가 식별자로 참조한다. 외부화하는 것은 비밀번호뿐이다. */
        private const val ADMIN_EMAIL = "admin@dongnemarket.com"
        private const val ADMIN_NICKNAME = "관리자"
    }
}
