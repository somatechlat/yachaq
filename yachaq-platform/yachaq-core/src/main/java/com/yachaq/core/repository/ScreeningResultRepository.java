package com.yachaq.core.repository;

import com.yachaq.core.domain.ScreeningResult;
import com.yachaq.core.domain.ScreeningResult.AppealStatus;
import com.yachaq.core.domain.ScreeningResult.ScreeningDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScreeningResultRepository extends JpaRepository<ScreeningResult, UUID> {
    Optional<ScreeningResult> findByRequestId(UUID requestId);
    boolean existsByRequestId(UUID requestId);
    Page<ScreeningResult> findByDecisionOrderByScreenedAtDesc(ScreeningDecision decision, Pageable pageable);
    List<ScreeningResult> findByAppealStatusOrderByAppealSubmittedAtAsc(AppealStatus status);
}
