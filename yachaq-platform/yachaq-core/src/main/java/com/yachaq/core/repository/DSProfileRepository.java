package com.yachaq.core.repository;

import com.yachaq.core.domain.DSProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Data Sovereign profiles.
 */
@Repository
public interface DSProfileRepository extends JpaRepository<DSProfile, UUID> {
    
    Optional<DSProfile> findByPseudonym(String pseudonym);
    
    boolean existsByPseudonym(String pseudonym);
}
