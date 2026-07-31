package com.dongnemarket.comment.service;

import com.dongnemarket.comment.dto.CommentCreateRequest;
import com.dongnemarket.comment.dto.CommentResponse;
import com.dongnemarket.comment.dto.CommentUpdateRequest;
import com.dongnemarket.comment.entity.Comment;
import com.dongnemarket.comment.repository.CommentRepository;
import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import com.dongnemarket.member.entity.Member;
import com.dongnemarket.product.entity.Product;
import com.dongnemarket.product.service.ProductService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CommentService 단위 테스트.
 * <p>Repository·ProductService·EntityManager를 mock으로 대체하고 <b>서비스의 비자명 분기 로직만</b> 검증한다
 * (접근 게이트, 작성자 검증, 소프트삭제 의미). 단순 매핑/조회는 컨트롤러 통합 테스트(CommentControllerTest)가
 * 실제 값으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommentService 단위 테스트")
class CommentServiceTest {

    @Mock
    CommentRepository commentRepository;

    @Mock
    ProductService productService;

    @Mock
    EntityManager entityManager;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    CommentService commentService;

    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 100L;
    private static final Long COMMENT_ID = 10L;

    /** 작성자 식별자만 스텁한 댓글 엔티티(소유자 검증·응답 변환용). */
    private Comment commentByMember(Long memberId, String content) {
        Member member = mock(Member.class);
        given(member.getId()).willReturn(memberId);
        return Comment.of(member, mock(Product.class), content);
    }

    @Nested
    @DisplayName("댓글 작성")
    class Create {

        @Test
        @DisplayName("접근 불가(존재하지 않거나 삭제·숨김) 상품이면 PRODUCT_NOT_FOUND, 저장하지 않는다")
        void productNotAccessible_throwsAndDoesNotSave() {
            CommentCreateRequest request = new CommentCreateRequest("좋은 상품이네요");
            willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND))
                    .given(productService).validateAccessibleProduct(PRODUCT_ID);

            assertThatThrownBy(() -> commentService.create(MEMBER_ID, PRODUCT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_FOUND);

            verify(commentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("댓글 목록 조회")
    class GetComments {

        @Test
        @DisplayName("접근 불가(존재하지 않거나 삭제·숨김) 상품이면 PRODUCT_NOT_FOUND, 조회하지 않는다")
        void productNotAccessible_throwsAndDoesNotQuery() {
            willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND))
                    .given(productService).validateAccessibleProduct(PRODUCT_ID);

            assertThatThrownBy(() -> commentService.getComments(PRODUCT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_FOUND);

            verify(commentRepository, never()).findAllWithMemberByProduct_Id(anyLong());
        }
    }

    @Nested
    @DisplayName("댓글 수정")
    class Update {

        @Test
        @DisplayName("작성자 본인이면 내용이 수정된다")
        void owner_updatesContent() {
            Comment comment = commentByMember(MEMBER_ID, "원본 내용");
            given(commentRepository.findByIdAndDeletedAtIsNull(COMMENT_ID)).willReturn(Optional.of(comment));

            CommentResponse response = commentService.update(MEMBER_ID, COMMENT_ID, new CommentUpdateRequest("수정된 내용"));

            assertThat(response.getContent()).isEqualTo("수정된 내용");
        }

        @Test
        @DisplayName("존재하지 않거나 삭제된 댓글이면 COMMENT_NOT_FOUND 예외가 발생한다")
        void notFound_throwsException() {
            given(commentRepository.findByIdAndDeletedAtIsNull(COMMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> commentService.update(MEMBER_ID, COMMENT_ID, new CommentUpdateRequest("수정")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("작성자가 아니면 COMMENT_OWNER_ONLY 예외가 발생한다")
        void notOwner_throwsException() {
            Comment othersComment = commentByMember(999L, "남의 댓글");
            given(commentRepository.findByIdAndDeletedAtIsNull(COMMENT_ID)).willReturn(Optional.of(othersComment));

            assertThatThrownBy(() -> commentService.update(MEMBER_ID, COMMENT_ID, new CommentUpdateRequest("수정")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMMENT_OWNER_ONLY);
        }
    }

    @Nested
    @DisplayName("댓글 삭제")
    class Delete {

        @Test
        @DisplayName("작성자 본인이면 소프트 삭제된다")
        void owner_softDeletes() {
            Comment comment = commentByMember(MEMBER_ID, "삭제될 댓글");
            given(commentRepository.findByIdAndDeletedAtIsNull(COMMENT_ID)).willReturn(Optional.of(comment));

            commentService.delete(MEMBER_ID, COMMENT_ID);

            assertThat(comment.isDeleted()).isTrue();
        }

        @Test
        @DisplayName("존재하지 않거나 이미 삭제된 댓글이면 COMMENT_NOT_FOUND 예외가 발생한다")
        void notFound_throwsException() {
            given(commentRepository.findByIdAndDeletedAtIsNull(COMMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> commentService.delete(MEMBER_ID, COMMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("작성자가 아니면 COMMENT_OWNER_ONLY 예외가 발생한다")
        void notOwner_throwsException() {
            Comment othersComment = commentByMember(999L, "남의 댓글");
            given(commentRepository.findByIdAndDeletedAtIsNull(COMMENT_ID)).willReturn(Optional.of(othersComment));

            assertThatThrownBy(() -> commentService.delete(MEMBER_ID, COMMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMMENT_OWNER_ONLY);
        }
    }
}
