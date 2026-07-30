package com.dongnemarket.global.exception

/**
 * 모든 비즈니스 예외의 단일 타입.
 *
 * 도메인 로직에서 RuntimeException 을 직접 던지지 않고 이 예외 + [ErrorCode] 를 사용한다.
 * (00-ai-common-rules.md 절대 규칙 8)
 *
 * Java 의 생성자 2개(오버로딩)가 **기본 인자 하나**로 합쳐졌다.
 * 단 Kotlin 기본 인자는 Java 에서 보이지 않으므로, 아직 Java 인 도메인들이
 * `new BusinessException(ErrorCode.X)` 를 그대로 쓸 수 있도록 `@JvmOverloads` 로
 * 1-인자 오버로드를 생성한다.
 */
class BusinessException
    @JvmOverloads
    constructor(
        val errorCode: ErrorCode,
        message: String = errorCode.message,
    ) : RuntimeException(message)
