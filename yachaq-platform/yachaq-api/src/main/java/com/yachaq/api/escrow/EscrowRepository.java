package com.yachaq.api.escrow;

import com.yachaq.core.domain.EscrowAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EscrowRepository extends JpaRepository<EscrowAccount, UUID> {
    Optional<EscrowAccount> findByRequestId(UUID requestId);
    boolean existsByRequestId(UUID requestId);
}
