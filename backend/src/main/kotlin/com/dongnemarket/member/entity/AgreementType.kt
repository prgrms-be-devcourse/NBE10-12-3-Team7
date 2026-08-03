package com.dongnemarket.member.entity

/**
 * 회원가입 시 필수로 동의받는 항목.
 *
 * [PERSONAL_INFO_COLLECTION]은 개인정보 수집·이용 동의를 뜻하며, 회원 식별·서비스 제공 목적 전반
 * (채팅, 알림 등 서비스 부가 기능 포함)을 아우른다. 채팅/알림은 별도 동의 항목이 아니라 이 목적 범위 안에서 처리한다.
 *
 * 상시 공개 문서인 개인정보처리방침(privacy policy notice)은 필수 동의 체크박스 대상이 아니라
 * 별도 열람 링크로 제공하므로 이 enum에 포함하지 않는다.
 */
enum class AgreementType {
    TERMS_OF_SERVICE,
    PERSONAL_INFO_COLLECTION,
}
