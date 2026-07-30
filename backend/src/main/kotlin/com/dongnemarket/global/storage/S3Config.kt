package com.dongnemarket.global.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

/**
 * S3 연결 설정. `file.storage.type=s3`일 때만 빈을 만든다 — 그 외(local, 기본값)에서는
 * `file.storage.s3.*` 프로퍼티가 없어도 컨텍스트가 뜬다(test 프로파일도 이 경로로 자연스럽게 커버된다).
 *
 * 자격증명은 [DefaultCredentialsProvider]를 사용한다 — 로컬/dev는 개발자의 `~/.aws/credentials`
 * 또는 실제 OS 환경변수(AWS_ACCESS_KEY_ID/SECRET), 배포 환경(EC2)은 IAM 인스턴스 역할을 자동으로 사용한다.
 * 자격증명을 코드나 .env에 직접 넣지 않는다.
 *
 * `name` 은 어노테이션에서 `String[]` 이다. Java 는 값이 하나면 배열 표기를 생략할 수 있지만
 * Kotlin 은 항상 `["..."]` 로 써야 한다.
 */
@Configuration
@ConditionalOnProperty(name = ["file.storage.type"], havingValue = "s3")
class S3Config {
    @Bean
    fun s3Client(
        @Value("\${file.storage.s3.region}") region: String,
    ): S3Client =
        S3Client
            .builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build()
}
