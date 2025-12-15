package com.yachaq.core.repository;

import com.yachaq.core.domain.Request;
import com.yachaq.core.domain.Request.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RequestRepository extends JpaRepository<Request, UUID> {

    Page<Request> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId, Pageable pageable);

    Page<Request> findByStatusOrderByCreatedAtDesc(RequestStatus status, Pageable pageable);

    @Query("SELECT r FROM Request r WHERE r.status = 'ACTIVE' AND r.durationStart <= :now AND r.durationEnd >= :now")
    List<Request> findActiveRequestsInRange(@Param("now") Instant now);
}
