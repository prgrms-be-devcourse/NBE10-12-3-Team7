package com.dongnemarket.comment.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** 댓글 작성 요청 본문. */
data class CommentCreateRequest(
    @field:NotBlank(message = "댓글 내용을 입력해주세요.")
    @field:Size(max = 500, message = "댓글은 500자 이내로 입력해주세요.")
    val content: String,
)
