package com.dongnemarket.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger / OpenAPI 설정.
 * JWT Bearer 인증 스킴을 등록해 Swagger UI 의 Authorize 로 토큰을 넣어 테스트할 수 있다.
 */
@Configuration
class SwaggerConfig {
    @Bean
    fun openAPI(): OpenAPI {
        val bearerScheme =
            SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                // `in` 은 Kotlin 예약어(in 연산자)라서 백틱으로 감싸야 메서드로 호출된다.
                .`in`(SecurityScheme.In.HEADER)
                .name("Authorization")

        return OpenAPI()
            .info(
                Info()
                    .title("마켓온 API")
                    .description("마켓온 REST API")
                    .version("v1.0.0"),
            ).addSecurityItem(SecurityRequirement().addList(SECURITY_SCHEME_NAME))
            .components(Components().addSecuritySchemes(SECURITY_SCHEME_NAME, bearerScheme))
    }

    companion object {
        private const val SECURITY_SCHEME_NAME = "bearerAuth"
    }
}
