package com.yachaq.api.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for DS Balance.
 */
@Repository
public interface DSBalanceRepository extends JpaRepository<DSBalance, UUID> {
    
    Optional<DSBalance> findByDsId(UUID dsId);
    
    boolean existsByDsId(UUID dsId);
}
