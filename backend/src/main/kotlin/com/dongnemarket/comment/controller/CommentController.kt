package com.dongnemarket.comment.controller

import com.dongnemarket.comment.dto.CommentCreateRequest
import com.dongnemarket.comment.dto.CommentResponse
import com.dongnemarket.comment.dto.CommentUpdateRequest
import com.dongnemarket.comment.service.CommentService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Comment", description = "댓글 API")
@RestController
class CommentController(
    private val commentService: CommentService,
) {
    @Operation(summary = "댓글 작성", description = "로그인 사용자가 특정 상품에 댓글을 작성한다.")
    @PostMapping("/api/products/{productId}/comments")
    fun createComment(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable productId: Long,
        @Valid @RequestBody request: CommentCreateRequest,
    ): ResponseEntity<ApiResponse<CommentResponse>> {
        val response = commentService.create(memberId, productId, request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "댓글이 작성되었습니다.", response))
    }

    @Operation(
        summary = "댓글 목록 조회",
        description = "특정 상품의 댓글 목록을 조회한다. 비로그인 사용자도 조회할 수 있으며, 삭제된 댓글은 제외된다.",
    )
    @GetMapping("/api/products/{productId}/comments")
    fun getComments(
        @PathVariable productId: Long,
    ): ResponseEntity<ApiResponse<List<CommentResponse>>> {
        val response = commentService.getComments(productId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "댓글 수정", description = "작성자 본인이 자신의 댓글 내용을 수정한다.")
    @PatchMapping("/api/comments/{commentId}")
    fun updateComment(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable commentId: Long,
        @Valid @RequestBody request: CommentUpdateRequest,
    ): ResponseEntity<ApiResponse<CommentResponse>> {
        val response = commentService.update(memberId, commentId, request)
        return ResponseEntity.ok(ApiResponse.success("댓글이 수정되었습니다.", response))
    }

    @Operation(summary = "댓글 삭제", description = "작성자 본인이 자신의 댓글을 삭제한다. (소프트 삭제)")
    @DeleteMapping("/api/comments/{commentId}")
    fun deleteComment(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable commentId: Long,
    ): ResponseEntity<ApiResponse<Void?>> {
        commentService.delete(memberId, commentId)
        return ResponseEntity.ok(ApiResponse.success<Void?>("댓글이 삭제되었습니다.", null))
    }
}
