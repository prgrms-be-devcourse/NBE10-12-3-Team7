package com.dongnemarket.admin.service;

import com.dongnemarket.admin.dto.OrphanDeleteRequest;
import com.dongnemarket.admin.dto.OrphanDeleteRequest.OrphanTarget;
import com.dongnemarket.admin.dto.OrphanDeleteResponse;
import com.dongnemarket.admin.dto.OrphanFileResponse;
import com.dongnemarket.admin.repository.AdminProductImageRepository;
import com.dongnemarket.admin.repository.AdminReportRepository;
import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import com.dongnemarket.global.storage.FileStorageService;
import com.dongnemarket.global.storage.StorageException;
import com.dongnemarket.global.storage.StoredObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * [단위] AdminStorageService — 고아 대조(저장소목록 − DB참조) + grace 필터 + 삭제 재확인 로직만 검증.
 * FileStorageService·참조 레포는 목으로 격리한다. (실제 저장소/DB는 통합에서)
 */
@ExtendWith(MockitoExtension.class)
class AdminStorageServiceTest {

    private static final String PRODUCT_IMAGES = "product-images";
    private static final String REPORT_EVIDENCE = "report-evidence";

    @Mock
    FileStorageService fileStorageService;
    @Mock
    AdminProductImageRepository adminProductImageRepository;
    @Mock
    AdminReportRepository adminReportRepository;
    @InjectMocks
    AdminStorageService adminStorageService;

    private StoredObject old(String name) {
        return new StoredObject(name, 100L, Instant.now().minus(Duration.ofDays(2)));
    }

    private StoredObject recent(String name) {
        return new StoredObject(name, 100L, Instant.now());
    }

    /** report-evidence 쪽은 이 기능 대부분의 케이스에서 비어 있어, 공통으로 빈 스텁을 깐다. */
    private void emptyReportSide() {
        given(fileStorageService.list(REPORT_EVIDENCE)).willReturn(List.of());
        given(adminReportRepository.findAllEvidenceImageUrls()).willReturn(List.of());
    }

    @Nested
    @DisplayName("scanOrphans")
    class Scan {

        @Test
        @DisplayName("DB가 참조하지 않는 오래된 파일만 고아로 잡고, 참조되는 파일은 제외한다")
        void detectsUnreferencedOldFile() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(List.of(old("orphan.png"), old("used.png")));
            given(adminProductImageRepository.findAllImageUrls())
                    .willReturn(List.of("/api/products/images/used.png"));
            emptyReportSide();

            List<OrphanFileResponse> orphans = adminStorageService.scanOrphans(24);

            assertThat(orphans).extracting(OrphanFileResponse::getFilename).containsExactly("orphan.png");
        }

        @Test
        @DisplayName("grace period 이내에 수정된 최근 파일은 제외한다")
        void excludesRecentFileWithinGrace() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(List.of(recent("fresh.png"), old("stale.png")));
            given(adminProductImageRepository.findAllImageUrls()).willReturn(List.of());
            emptyReportSide();

            List<OrphanFileResponse> orphans = adminStorageService.scanOrphans(24);

            assertThat(orphans).extracting(OrphanFileResponse::getFilename).containsExactly("stale.png");
        }
    }

    @Nested
    @DisplayName("deleteOrphans")
    class Delete {

        @Test
        @DisplayName("지금도 고아인 대상은 삭제되고 deleted로 집계된다")
        void deletesActualOrphan() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(List.of(old("orphan.png")));
            given(adminProductImageRepository.findAllImageUrls()).willReturn(List.of());
            emptyReportSide();
            given(fileStorageService.delete("orphan.png", PRODUCT_IMAGES)).willReturn(true);

            OrphanDeleteResponse res = adminStorageService.deleteOrphans(
                    new OrphanDeleteRequest(List.of(new OrphanTarget(PRODUCT_IMAGES, "orphan.png"))), 24);

            assertThat(res.getRequested()).isEqualTo(1);
            assertThat(res.getDeleted()).isEqualTo(1);
            assertThat(res.getSkipped()).isZero();
        }

        @Test
        @DisplayName("지금은 고아가 아닌(참조되는) 대상은 삭제하지 않고 skip한다")
        void skipsNonOrphanTarget() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(List.of(old("used.png")));
            given(adminProductImageRepository.findAllImageUrls())
                    .willReturn(List.of("/api/products/images/used.png"));
            emptyReportSide();

            OrphanDeleteResponse res = adminStorageService.deleteOrphans(
                    new OrphanDeleteRequest(List.of(new OrphanTarget(PRODUCT_IMAGES, "used.png"))), 24);

            assertThat(res.getRequested()).isEqualTo(1);
            assertThat(res.getDeleted()).isZero();
            assertThat(res.getSkipped()).isEqualTo(1);
            then(fileStorageService).should(never()).delete(anyString(), anyString());
        }

        @Test
        @DisplayName("허용되지 않은 디렉터리는 삭제 전에 INVALID_STORAGE_DIRECTORY로 거부한다")
        void rejectsInvalidDirectory() {
            assertThatThrownBy(() -> adminStorageService.deleteOrphans(
                    new OrphanDeleteRequest(List.of(new OrphanTarget("bad-dir", "x.png"))), 24))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STORAGE_DIRECTORY);
        }

        @Test
        @DisplayName("저장소 삭제 중 오류는 STORAGE_ORPHAN_DELETE_FAILED로 변환한다")
        void wrapsStorageError() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(List.of(old("boom.png")));
            given(adminProductImageRepository.findAllImageUrls()).willReturn(List.of());
            emptyReportSide();
            given(fileStorageService.delete("boom.png", PRODUCT_IMAGES))
                    .willThrow(new StorageException("fail", new RuntimeException()));

            assertThatThrownBy(() -> adminStorageService.deleteOrphans(
                    new OrphanDeleteRequest(List.of(new OrphanTarget(PRODUCT_IMAGES, "boom.png"))), 24))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORAGE_ORPHAN_DELETE_FAILED);
        }
    }
}
