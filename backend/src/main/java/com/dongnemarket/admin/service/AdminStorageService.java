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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 저장소 고아파일(파일은 실재하나 DB가 참조하지 않는 것) 스캔·삭제.
 * directory별로 FileStorageService.list()와 DB 참조 집합을 대조한다. 조회는 읽기 전용.
 */
@Service
@Transactional(readOnly = true)
public class AdminStorageService {

    private static final String PRODUCT_IMAGES = "product-images";
    private static final String REPORT_EVIDENCE = "report-evidence";

    private final FileStorageService fileStorageService;
    private final AdminProductImageRepository adminProductImageRepository;
    private final AdminReportRepository adminReportRepository;

    public AdminStorageService(FileStorageService fileStorageService,
                               AdminProductImageRepository adminProductImageRepository,
                               AdminReportRepository adminReportRepository) {
        this.fileStorageService = fileStorageService;
        this.adminProductImageRepository = adminProductImageRepository;
        this.adminReportRepository = adminReportRepository;
    }

    /** graceHours 이내 수정 파일(업로드 진행 중일 수 있음)은 제외하고 각 디렉터리의 고아를 모은다. */
    public List<OrphanFileResponse> scanOrphans(long graceHours) {
        Instant now = Instant.now();
        Instant threshold = now.minus(Duration.ofHours(graceHours));
        List<OrphanFileResponse> orphans = new ArrayList<>();
        orphans.addAll(scan(PRODUCT_IMAGES, referencedFilenames(adminProductImageRepository.findAllImageUrls()), now, threshold));
        orphans.addAll(scan(REPORT_EVIDENCE, referencedFilenames(adminReportRepository.findAllEvidenceImageUrls()), now, threshold));
        return orphans;
    }

    /** 선택 파일을 삭제한다. 삭제 직전 "지금도 고아·grace 통과"인지 재확인해 참조/최근 파일을 보호한다. */
    public OrphanDeleteResponse deleteOrphans(OrphanDeleteRequest request, long graceHours) {
        List<OrphanTarget> targets =
                (request == null || request.targets() == null) ? List.of() : request.targets();
        targets.forEach(t -> validateDirectory(t.directory()));   // 잘못된 디렉터리는 삭제 전 일괄 거부

        Set<String> currentOrphans = scanOrphans(graceHours).stream()
                .map(o -> key(o.directory(), o.filename()))
                .collect(Collectors.toSet());

        int deleted = 0;
        int skipped = 0;
        for (OrphanTarget t : targets) {
            if (!currentOrphans.contains(key(t.directory(), t.filename()))) {
                skipped++;   // 지금은 고아 아님(참조됨/최근/이미 없음) → 건너뜀
                continue;
            }
            try {
                if (fileStorageService.delete(t.filename(), t.directory())) {
                    deleted++;
                } else {
                    skipped++;   // 그 사이 이미 사라짐(멱등)
                }
            } catch (StorageException e) {
                throw new BusinessException(ErrorCode.STORAGE_ORPHAN_DELETE_FAILED);
            }
        }
        return new OrphanDeleteResponse(targets.size(), deleted, skipped);
    }

    private List<OrphanFileResponse> scan(String directory, Set<String> referenced, Instant now, Instant threshold) {
        return fileStorageService.list(directory).stream()
                .filter(obj -> !referenced.contains(obj.getFilename()))     // DB 미참조 = 고아 후보
                .filter(obj -> obj.getLastModified().isBefore(threshold))   // grace 통과(충분히 오래된 것만)
                .map(obj -> new OrphanFileResponse(
                        directory,
                        obj.getFilename(),
                        obj.getSizeBytes(),
                        obj.getLastModified(),
                        Duration.between(obj.getLastModified(), now).toHours()))
                .toList();
    }

    /** URL(/api/.../<filename>)에서 파일명만 뽑아 집합으로 — 마지막 '/' 뒤가 저장 파일명. */
    private Set<String> referencedFilenames(List<String> urls) {
        return urls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(url -> url.substring(url.lastIndexOf('/') + 1))
                .collect(Collectors.toSet());
    }

    private void validateDirectory(String directory) {
        if (!PRODUCT_IMAGES.equals(directory) && !REPORT_EVIDENCE.equals(directory)) {
            throw new BusinessException(ErrorCode.INVALID_STORAGE_DIRECTORY);
        }
    }

    private String key(String directory, String filename) {
        return directory + "#" + filename;
    }
}
