package com.yachaq.api.escrow;

import com.yachaq.core.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    
    List<JournalEntry> findByDebitAccountOrderByTimestampDesc(String debitAccount);
    
    List<JournalEntry> findByCreditAccountOrderByTimestampDesc(String creditAccount);
    
    @Query("SELECT COALESCE(SUM(j.amount), 0) FROM JournalEntry j WHERE j.debitAccount = :account")
    BigDecimal sumDebitsByAccount(@Param("account") String account);
    
    @Query("SELECT COALESCE(SUM(j.amount), 0) FROM JournalEntry j WHERE j.creditAccount = :account")
    BigDecimal sumCreditsByAccount(@Param("account") String account);
    
    boolean existsByIdempotencyKey(String idempotencyKey);
}
