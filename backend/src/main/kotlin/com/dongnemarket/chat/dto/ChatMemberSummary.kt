package com.dongnemarket.chat.dto

import com.dongnemarket.member.entity.Member

/** 채팅 상대·판매자 표시용 회원 요약. 이메일 등 민감정보 없이 닉네임만 노출한다. */
@ConsistentCopyVisibility
data class ChatMemberSummary private constructor(
    val memberId: Long?,
    val nickname: String?,
    /** 상대가 탈퇴(DELETED)했는지 여부. 프론트가 입력창 비활성화·안내 배너를 프로액티브하게 띄우는 신뢰 신호. */
    val withdrawn: Boolean,
) {
    companion object {
        @JvmStatic
        fun of(member: Member): ChatMemberSummary =
            ChatMemberSummary(member.id, member.displayNickname, member.isWithdrawn)
    }
}
