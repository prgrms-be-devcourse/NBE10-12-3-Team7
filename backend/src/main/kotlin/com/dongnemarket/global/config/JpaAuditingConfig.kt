package com.dongnemarket.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/**
 * JPA Auditing 활성화 ([com.dongnemarket.global.common.BaseTimeEntity] 의 시각 자동 주입).
 *
 * 본문이 비어 있어 `{}` 를 생략했다. `@Configuration` 은 CGLIB 프록시 대상이지만
 * `kotlin-spring`(allopen) 플러그인이 자동으로 `open` 으로 만들어준다.
 */
@Configuration
@EnableJpaAuditing
class JpaAuditingConfig
