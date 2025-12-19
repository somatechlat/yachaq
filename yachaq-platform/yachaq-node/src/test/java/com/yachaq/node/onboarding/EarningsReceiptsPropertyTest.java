package com.yachaq.node.onboarding;

import com.yachaq.node.onboarding.EarningsReceipts.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for EarningsReceipts.
 * 
 * Validates: Requirements 346.1, 346.2, 346.3, 346.4, 346.5
 */
class EarningsReceiptsPropertyTest {

    // ==================== Test Fixtures ====================

    private EarningsReceipts createEarningsReceipts() {
        return new EarningsReceipts(new InMemoryEarningsStore());
    }

    private EarningsReceipts createEarningsReceiptsWithData(String dsNodeId, List<EarningsTransaction> transactions) {
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        for (EarningsTransaction t : transactions) {
            store.addTransaction(dsNodeId, t);
        }
        return new EarningsReceipts(store);
    }

    private EarningsTransaction createTransaction(String id, TransactionType type, BigDecimal amount, Instant timestamp) {
        return new EarningsTransaction(
                id,
                type,
                amount,
                "USD",
                "Test transaction",
                "requester-1",
                "Test Requester",
                "contract-" + id,
                timestamp,
                TransactionStatus.COMPLETED,
                Map.of()
        );
    }

    private EscrowHold createEscrowHold(String id, BigDecimal amount, EscrowStatus status) {
        return new EscrowHold(
                id,
                amount,
                "USD",
                "requester-1",
                "Test Requester",
                "contract-" + id,
                status,
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(7, ChronoUnit.DAYS)
        );
    }

    // ==================== Task 90.1: Earnings Dashboard Tests ====================

    @Test
    void dashboard_displaysEscrowState() {
        // Requirement 346.1: Display escrow state
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        store.addEscrowHold("ds-1", createEscrowHold("escrow-1", BigDecimal.valueOf(50), EscrowStatus.HELD));
        store.addEscrowHold("ds-1", createEscrowHold("escrow-2", BigDecimal.valueOf(30), EscrowStatus.PENDING_RELEASE));
        EarningsReceipts earnings = new EarningsReceipts(store);

        EarningsDashboard dashboard = earnings.getDashboard("ds-1");

        assertThat(dashboard.totalPending()).isEqualByComparingTo(BigDecimal.valueOf(80));
        assertThat(dashboard.activeEscrows()).hasSize(2);
    }

    @Test
    void dashboard_displaysPayouts() {
        // Requirement 346.1: Display payouts
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, BigDecimal.valueOf(100), Instant.now()));
        store.addTransaction("ds-1", createTransaction("t2", TransactionType.PAYOUT, BigDecimal.valueOf(50), Instant.now()));
        EarningsReceipts earnings = new EarningsReceipts(store);

        EarningsDashboard dashboard = earnings.getDashboard("ds-1");

        assertThat(dashboard.totalEarned()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(dashboard.totalPaidOut()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(dashboard.availableBalance()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void dashboard_displaysRecentTransactions() {
        // Requirement 346.1: Display receipts
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        for (int i = 0; i < 15; i++) {
            store.addTransaction("ds-1", createTransaction("t" + i, TransactionType.EARNING, 
                    BigDecimal.valueOf(10), Instant.now().minus(i, ChronoUnit.HOURS)));
        }
        EarningsReceipts earnings = new EarningsReceipts(store);

        EarningsDashboard dashboard = earnings.getDashboard("ds-1");

        assertThat(dashboard.recentTransactions()).hasSize(10); // Limited to 10
    }

    @Test
    void dashboard_calculatesPeriodEarnings() {
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        // This month
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(100), Instant.now()));
        // Last month
        store.addTransaction("ds-1", createTransaction("t2", TransactionType.EARNING, 
                BigDecimal.valueOf(200), Instant.now().minus(35, ChronoUnit.DAYS)));
        EarningsReceipts earnings = new EarningsReceipts(store);

        EarningsDashboard dashboard = earnings.getDashboard("ds-1");

        assertThat(dashboard.thisMonth().totalEarned()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(dashboard.thisMonth().transactionCount()).isEqualTo(1);
    }

    @Property
    void dashboard_totalEarnedEqualsEarningsPlusBonus(@ForAll("transactionLists") List<EarningsTransaction> transactions) {
        // Property: Total earned should equal sum of EARNING + BONUS transactions
        EarningsReceipts earnings = createEarningsReceiptsWithData("ds-prop", transactions);

        EarningsDashboard dashboard = earnings.getDashboard("ds-prop");

        BigDecimal expectedTotal = transactions.stream()
                .filter(t -> t.type() == TransactionType.EARNING || t.type() == TransactionType.BONUS)
                .map(EarningsTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(dashboard.totalEarned()).isEqualByComparingTo(expectedTotal);
    }

    // ==================== Task 90.2: Receipt Export Tests ====================

    @Test
    void receiptExport_providesTransactionProof() {
        // Requirement 346.2: Provide transaction proofs without exposing data
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(100), Instant.now()));
        EarningsReceipts earnings = new EarningsReceipts(store);

        TransactionReceipt receipt = earnings.exportReceipt("t1");

        assertThat(receipt.receiptId()).isNotNull();
        assertThat(receipt.transactionId()).isEqualTo("t1");
        assertThat(receipt.proofHash()).isNotNull();
        assertThat(receipt.signature()).isNotNull();
        assertThat(receipt.amount()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void receiptExport_doesNotExposeRawData() {
        // Requirement 346.2: Without exposing data
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        EarningsTransaction transaction = new EarningsTransaction(
                "t1",
                TransactionType.EARNING,
                BigDecimal.valueOf(100),
                "USD",
                "Sensitive description with PII",
                "requester-secret-id",
                "Secret Requester Name",
                "contract-secret",
                Instant.now(),
                TransactionStatus.COMPLETED,
                Map.of("secret", "value")
        );
        store.addTransaction("ds-1", transaction);
        EarningsReceipts earnings = new EarningsReceipts(store);

        TransactionReceipt receipt = earnings.exportReceipt("t1");

        // Receipt should not contain sensitive data directly
        assertThat(receipt.contractHash()).isNotEqualTo("contract-secret"); // Should be hashed
    }

    @Test
    void receiptVerification_validatesCorrectReceipt() {
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(100), Instant.now()));
        EarningsReceipts earnings = new EarningsReceipts(store);

        TransactionReceipt receipt = earnings.exportReceipt("t1");
        ReceiptVerificationResult result = earnings.verifyReceipt(receipt);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void receiptExport_throwsForUnknownTransaction() {
        EarningsReceipts earnings = createEarningsReceipts();

        assertThatThrownBy(() -> earnings.exportReceipt("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Property
    void receiptExport_allReceiptsHaveUniqueIds(@ForAll("transactionLists") List<EarningsTransaction> transactions) {
        // Property: All exported receipts should have unique IDs
        if (transactions.isEmpty()) return;

        EarningsReceipts earnings = createEarningsReceiptsWithData("ds-prop", transactions);
        TransactionFilter filter = TransactionFilter.builder().build();

        List<TransactionReceipt> receipts = earnings.exportReceipts("ds-prop", filter);

        Set<String> receiptIds = new HashSet<>();
        for (TransactionReceipt receipt : receipts) {
            assertThat(receiptIds.add(receipt.receiptId())).isTrue();
        }
    }

    // ==================== Task 90.3: Tax Export Tests ====================

    @Test
    void taxExport_providesExportableSummary() {
        // Requirement 346.4: Provide exportable summaries in standard formats
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        int currentYear = java.time.Year.now().getValue();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(1000), Instant.now()));
        store.addTransaction("ds-1", createTransaction("t2", TransactionType.FEE, 
                BigDecimal.valueOf(50), Instant.now()));
        EarningsReceipts earnings = new EarningsReceipts(store);

        TaxExport export = earnings.exportTaxSummary("ds-1", currentYear, TaxExportFormat.CSV);

        assertThat(export.totalIncome()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(export.totalFees()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(export.netIncome()).isEqualByComparingTo(BigDecimal.valueOf(950));
        assertThat(export.exportData()).isNotEmpty();
        assertThat(export.filename()).contains("csv");
    }

    @Test
    void taxExport_supportsMultipleFormats() {
        // Requirement 346.4: Standard formats
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        int currentYear = java.time.Year.now().getValue();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(100), Instant.now()));
        EarningsReceipts earnings = new EarningsReceipts(store);

        TaxExport csvExport = earnings.exportTaxSummary("ds-1", currentYear, TaxExportFormat.CSV);
        TaxExport jsonExport = earnings.exportTaxSummary("ds-1", currentYear, TaxExportFormat.JSON);
        TaxExport pdfExport = earnings.exportTaxSummary("ds-1", currentYear, TaxExportFormat.PDF);

        assertThat(csvExport.format()).isEqualTo(TaxExportFormat.CSV);
        assertThat(jsonExport.format()).isEqualTo(TaxExportFormat.JSON);
        assertThat(pdfExport.format()).isEqualTo(TaxExportFormat.PDF);

        assertThat(csvExport.filename()).endsWith(".csv");
        assertThat(jsonExport.filename()).endsWith(".json");
        assertThat(pdfExport.filename()).endsWith(".pdf");
    }

    @Test
    void taxExport_filtersToSpecifiedYear() {
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        int currentYear = java.time.Year.now().getValue();
        // Current year
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(100), Instant.now()));
        // Last year
        store.addTransaction("ds-1", createTransaction("t2", TransactionType.EARNING, 
                BigDecimal.valueOf(200), Instant.now().minus(400, ChronoUnit.DAYS)));
        EarningsReceipts earnings = new EarningsReceipts(store);

        TaxExport export = earnings.exportTaxSummary("ds-1", currentYear, TaxExportFormat.JSON);

        assertThat(export.transactionCount()).isEqualTo(1);
        assertThat(export.totalIncome()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void availableTaxYears_returnsYearsWithTransactions() {
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        int currentYear = java.time.Year.now().getValue();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(100), Instant.now()));
        store.addTransaction("ds-1", createTransaction("t2", TransactionType.EARNING, 
                BigDecimal.valueOf(200), Instant.now().minus(400, ChronoUnit.DAYS)));
        EarningsReceipts earnings = new EarningsReceipts(store);

        List<Integer> years = earnings.getAvailableTaxYears("ds-1");

        assertThat(years).contains(currentYear);
        assertThat(years).isSortedAccordingTo(Comparator.reverseOrder());
    }

    // ==================== Filtering Tests ====================

    @Test
    void filtering_byTransactionType() {
        // Requirement 346.3: Support filtering
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(100), Instant.now()));
        store.addTransaction("ds-1", createTransaction("t2", TransactionType.PAYOUT, 
                BigDecimal.valueOf(50), Instant.now()));
        store.addTransaction("ds-1", createTransaction("t3", TransactionType.BONUS, 
                BigDecimal.valueOf(25), Instant.now()));
        EarningsReceipts earnings = new EarningsReceipts(store);

        TransactionFilter filter = TransactionFilter.builder()
                .types(Set.of(TransactionType.EARNING))
                .build();

        List<TransactionSummary> results = earnings.getTransactions("ds-1", filter);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).type()).isEqualTo(TransactionType.EARNING);
    }

    @Test
    void filtering_byDateRange() {
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        Instant now = Instant.now();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(100), now));
        store.addTransaction("ds-1", createTransaction("t2", TransactionType.EARNING, 
                BigDecimal.valueOf(200), now.minus(10, ChronoUnit.DAYS)));
        store.addTransaction("ds-1", createTransaction("t3", TransactionType.EARNING, 
                BigDecimal.valueOf(300), now.minus(30, ChronoUnit.DAYS)));
        EarningsReceipts earnings = new EarningsReceipts(store);

        TransactionFilter filter = TransactionFilter.builder()
                .startDate(now.minus(15, ChronoUnit.DAYS))
                .endDate(now.plus(1, ChronoUnit.DAYS))
                .build();

        List<TransactionSummary> results = earnings.getTransactions("ds-1", filter);

        assertThat(results).hasSize(2);
    }

    @Test
    void filtering_byAmountRange() {
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        store.addTransaction("ds-1", createTransaction("t1", TransactionType.EARNING, 
                BigDecimal.valueOf(10), Instant.now()));
        store.addTransaction("ds-1", createTransaction("t2", TransactionType.EARNING, 
                BigDecimal.valueOf(50), Instant.now()));
        store.addTransaction("ds-1", createTransaction("t3", TransactionType.EARNING, 
                BigDecimal.valueOf(100), Instant.now()));
        EarningsReceipts earnings = new EarningsReceipts(store);

        TransactionFilter filter = TransactionFilter.builder()
                .minAmount(BigDecimal.valueOf(20))
                .maxAmount(BigDecimal.valueOf(80))
                .build();

        List<TransactionSummary> results = earnings.getTransactions("ds-1", filter);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).amount()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void filtering_withPagination() {
        InMemoryEarningsStore store = new InMemoryEarningsStore();
        for (int i = 0; i < 20; i++) {
            store.addTransaction("ds-1", createTransaction("t" + i, TransactionType.EARNING, 
                    BigDecimal.valueOf(10 + i), Instant.now().minus(i, ChronoUnit.HOURS)));
        }
        EarningsReceipts earnings = new EarningsReceipts(store);

        TransactionFilter filter = TransactionFilter.builder()
                .offset(5)
                .limit(10)
                .build();

        List<TransactionSummary> results = earnings.getTransactions("ds-1", filter);

        assertThat(results).hasSize(10);
    }

    // ==================== Edge Case Tests ====================

    @Test
    void constructor_rejectsNullStore() {
        assertThatThrownBy(() -> new EarningsReceipts(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void getDashboard_rejectsNullDsNodeId() {
        EarningsReceipts earnings = createEarningsReceipts();

        assertThatThrownBy(() -> earnings.getDashboard(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getDashboard_returnsEmptyForUnknownDs() {
        EarningsReceipts earnings = createEarningsReceipts();

        EarningsDashboard dashboard = earnings.getDashboard("unknown-ds");

        assertThat(dashboard.totalEarned()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dashboard.recentTransactions()).isEmpty();
    }

    @Test
    void verifyReceipt_rejectsNullReceipt() {
        EarningsReceipts earnings = createEarningsReceipts();

        assertThatThrownBy(() -> earnings.verifyReceipt(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<List<EarningsTransaction>> transactionLists() {
        return Arbitraries.integers().between(0, 10)
                .flatMap(count -> {
                    if (count == 0) return Arbitraries.just(List.of());
                    
                    return Arbitraries.integers().between(1, 1000)
                            .list().ofSize(count)
                            .map(amounts -> {
                                List<EarningsTransaction> transactions = new ArrayList<>();
                                TransactionType[] types = TransactionType.values();
                                for (int i = 0; i < amounts.size(); i++) {
                                    transactions.add(new EarningsTransaction(
                                            "t-" + UUID.randomUUID().toString().substring(0, 8),
                                            types[i % types.length],
                                            BigDecimal.valueOf(amounts.get(i)),
                                            "USD",
                                            "Test transaction " + i,
                                            "requester-" + (i % 3),
                                            "Requester " + (i % 3),
                                            "contract-" + i,
                                            Instant.now().minus(i, java.time.temporal.ChronoUnit.DAYS),
                                            TransactionStatus.COMPLETED,
                                            Map.of()
                                    ));
                                }
                                return transactions;
                            });
                });
    }
}
