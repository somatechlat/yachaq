package com.yachaq.node.onboarding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Earnings & Receipts Dashboard for Provider App UI.
 * Displays escrow state, payouts, receipts, and provides export functionality.
 * 
 * Security: Transaction proofs do not expose underlying data.
 * Performance: Efficient filtering and aggregation.
 * UX: Clear earnings visualization with exportable summaries.
 * 
 * Validates: Requirements 346.1, 346.2, 346.3, 346.4, 346.5
 */
public class EarningsReceipts {

    private final EarningsStore earningsStore;
    private final ReceiptGenerator receiptGenerator;
    private final TaxExporter taxExporter;

    public EarningsReceipts(EarningsStore earningsStore) {
        this(earningsStore, new DefaultReceiptGenerator(), new DefaultTaxExporter());
    }

    public EarningsReceipts(EarningsStore earningsStore, 
                           ReceiptGenerator receiptGenerator,
                           TaxExporter taxExporter) {
        this.earningsStore = Objects.requireNonNull(earningsStore, "EarningsStore cannot be null");
        this.receiptGenerator = Objects.requireNonNull(receiptGenerator, "ReceiptGenerator cannot be null");
        this.taxExporter = Objects.requireNonNull(taxExporter, "TaxExporter cannot be null");
    }

    // ==================== Task 90.1: Earnings Dashboard ====================

    /**
     * Gets the earnings dashboard view.
     * Requirement 346.1: Display escrow state, payouts, receipts.
     * 
     * @param dsNodeId Data supplier node ID
     * @return EarningsDashboard for UI rendering
     */
    public EarningsDashboard getDashboard(String dsNodeId) {
        Objects.requireNonNull(dsNodeId, "DS Node ID cannot be null");

        List<EarningsTransaction> transactions = earningsStore.getTransactions(dsNodeId);
        List<EscrowHold> escrowHolds = earningsStore.getEscrowHolds(dsNodeId);

        // Calculate totals
        BigDecimal totalEarned = calculateTotalEarned(transactions);
        BigDecimal totalPending = calculateTotalPending(escrowHolds);
        BigDecimal totalPaidOut = calculateTotalPaidOut(transactions);
        BigDecimal availableBalance = totalEarned.subtract(totalPaidOut);

        // Get recent transactions
        List<TransactionSummary> recentTransactions = transactions.stream()
                .sorted(Comparator.comparing(EarningsTransaction::timestamp).reversed())
                .limit(10)
                .map(this::toTransactionSummary)
                .toList();

        // Get active escrows
        List<EscrowSummary> activeEscrows = escrowHolds.stream()
                .filter(e -> e.status() == EscrowStatus.HELD || e.status() == EscrowStatus.PENDING_RELEASE)
                .map(this::toEscrowSummary)
                .toList();

        // Calculate period earnings
        PeriodEarnings thisMonth = calculatePeriodEarnings(transactions, Period.THIS_MONTH);
        PeriodEarnings lastMonth = calculatePeriodEarnings(transactions, Period.LAST_MONTH);
        PeriodEarnings thisYear = calculatePeriodEarnings(transactions, Period.THIS_YEAR);

        return new EarningsDashboard(
                dsNodeId,
                totalEarned,
                totalPending,
                totalPaidOut,
                availableBalance,
                "USD", // Default currency
                recentTransactions,
                activeEscrows,
                thisMonth,
                lastMonth,
                thisYear,
                Instant.now()
        );
    }

    /**
     * Gets filtered transactions.
     * Requirement 346.3: Support filtering.
     * 
     * @param dsNodeId Data supplier node ID
     * @param filter Transaction filter
     * @return List of filtered transactions
     */
    public List<TransactionSummary> getTransactions(String dsNodeId, TransactionFilter filter) {
        Objects.requireNonNull(dsNodeId, "DS Node ID cannot be null");
        Objects.requireNonNull(filter, "Filter cannot be null");

        List<EarningsTransaction> transactions = earningsStore.getTransactions(dsNodeId);

        return transactions.stream()
                .filter(t -> matchesFilter(t, filter))
                .sorted(getComparator(filter.sortBy(), filter.sortAscending()))
                .skip(filter.offset())
                .limit(filter.limit())
                .map(this::toTransactionSummary)
                .toList();
    }

    // ==================== Task 90.2: Receipt Export ====================

    /**
     * Exports a transaction receipt.
     * Requirement 346.2: Provide transaction proofs without exposing data.
     * 
     * @param transactionId Transaction ID
     * @return TransactionReceipt with proof
     */
    public TransactionReceipt exportReceipt(String transactionId) {
        Objects.requireNonNull(transactionId, "Transaction ID cannot be null");

        EarningsTransaction transaction = earningsStore.getTransaction(transactionId);
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction not found: " + transactionId);
        }

        return receiptGenerator.generateReceipt(transaction);
    }

    /**
     * Exports multiple receipts.
     * Requirement 346.2: Provide transaction proofs without exposing data.
     * 
     * @param dsNodeId Data supplier node ID
     * @param filter Filter for receipts
     * @return List of receipts
     */
    public List<TransactionReceipt> exportReceipts(String dsNodeId, TransactionFilter filter) {
        Objects.requireNonNull(dsNodeId, "DS Node ID cannot be null");

        List<EarningsTransaction> transactions = earningsStore.getTransactions(dsNodeId);

        return transactions.stream()
                .filter(t -> matchesFilter(t, filter))
                .map(receiptGenerator::generateReceipt)
                .toList();
    }

    /**
     * Verifies a receipt.
     * 
     * @param receipt Receipt to verify
     * @return VerificationResult
     */
    public ReceiptVerificationResult verifyReceipt(TransactionReceipt receipt) {
        Objects.requireNonNull(receipt, "Receipt cannot be null");
        return receiptGenerator.verifyReceipt(receipt);
    }

    // ==================== Task 90.3: Tax Export ====================

    /**
     * Exports tax summary for a period.
     * Requirement 346.4: Provide exportable summaries in standard formats.
     * 
     * @param dsNodeId Data supplier node ID
     * @param year Tax year
     * @param format Export format
     * @return TaxExport with summary data
     */
    public TaxExport exportTaxSummary(String dsNodeId, int year, TaxExportFormat format) {
        Objects.requireNonNull(dsNodeId, "DS Node ID cannot be null");
        Objects.requireNonNull(format, "Format cannot be null");

        List<EarningsTransaction> transactions = earningsStore.getTransactions(dsNodeId);

        // Filter to the specified year
        List<EarningsTransaction> yearTransactions = transactions.stream()
                .filter(t -> t.timestamp().atZone(ZoneId.systemDefault()).getYear() == year)
                .toList();

        return taxExporter.export(dsNodeId, year, yearTransactions, format);
    }

    /**
     * Gets available tax years.
     * 
     * @param dsNodeId Data supplier node ID
     * @return List of years with transactions
     */
    public List<Integer> getAvailableTaxYears(String dsNodeId) {
        Objects.requireNonNull(dsNodeId, "DS Node ID cannot be null");

        List<EarningsTransaction> transactions = earningsStore.getTransactions(dsNodeId);

        return transactions.stream()
                .map(t -> t.timestamp().atZone(ZoneId.systemDefault()).getYear())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    // ==================== Private Helper Methods ====================

    private BigDecimal calculateTotalEarned(List<EarningsTransaction> transactions) {
        return transactions.stream()
                .filter(t -> t.type() == TransactionType.EARNING || t.type() == TransactionType.BONUS)
                .map(EarningsTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateTotalPending(List<EscrowHold> escrows) {
        return escrows.stream()
                .filter(e -> e.status() == EscrowStatus.HELD || e.status() == EscrowStatus.PENDING_RELEASE)
                .map(EscrowHold::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateTotalPaidOut(List<EarningsTransaction> transactions) {
        return transactions.stream()
                .filter(t -> t.type() == TransactionType.PAYOUT)
                .map(EarningsTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PeriodEarnings calculatePeriodEarnings(List<EarningsTransaction> transactions, Period period) {
        Instant start = period.getStart();
        Instant end = period.getEnd();

        List<EarningsTransaction> periodTransactions = transactions.stream()
                .filter(t -> !t.timestamp().isBefore(start) && t.timestamp().isBefore(end))
                .toList();

        BigDecimal earned = periodTransactions.stream()
                .filter(t -> t.type() == TransactionType.EARNING || t.type() == TransactionType.BONUS)
                .map(EarningsTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int transactionCount = periodTransactions.size();

        return new PeriodEarnings(period.getDisplayName(), earned, transactionCount, start, end);
    }

    private TransactionSummary toTransactionSummary(EarningsTransaction t) {
        return new TransactionSummary(
                t.id(),
                t.type(),
                t.amount(),
                t.currency(),
                t.description(),
                t.requesterId(),
                t.requesterName(),
                t.contractId(),
                t.timestamp(),
                t.status()
        );
    }

    private EscrowSummary toEscrowSummary(EscrowHold e) {
        return new EscrowSummary(
                e.id(),
                e.amount(),
                e.currency(),
                e.requesterId(),
                e.requesterName(),
                e.contractId(),
                e.status(),
                e.createdAt(),
                e.expectedReleaseAt()
        );
    }

    private boolean matchesFilter(EarningsTransaction t, TransactionFilter filter) {
        if (filter.types() != null && !filter.types().isEmpty() && !filter.types().contains(t.type())) {
            return false;
        }
        if (filter.statuses() != null && !filter.statuses().isEmpty() && !filter.statuses().contains(t.status())) {
            return false;
        }
        if (filter.startDate() != null && t.timestamp().isBefore(filter.startDate())) {
            return false;
        }
        if (filter.endDate() != null && t.timestamp().isAfter(filter.endDate())) {
            return false;
        }
        if (filter.minAmount() != null && t.amount().compareTo(filter.minAmount()) < 0) {
            return false;
        }
        if (filter.maxAmount() != null && t.amount().compareTo(filter.maxAmount()) > 0) {
            return false;
        }
        if (filter.requesterId() != null && !filter.requesterId().equals(t.requesterId())) {
            return false;
        }
        return true;
    }

    private Comparator<EarningsTransaction> getComparator(SortField sortBy, boolean ascending) {
        Comparator<EarningsTransaction> comparator = switch (sortBy != null ? sortBy : SortField.DATE) {
            case DATE -> Comparator.comparing(EarningsTransaction::timestamp);
            case AMOUNT -> Comparator.comparing(EarningsTransaction::amount);
            case TYPE -> Comparator.comparing(t -> t.type().name());
            case STATUS -> Comparator.comparing(t -> t.status().name());
        };
        return ascending ? comparator : comparator.reversed();
    }


    // ==================== Inner Types ====================

    /**
     * Transaction type enumeration.
     */
    public enum TransactionType {
        EARNING("Earning", "Payment for data contribution"),
        BONUS("Bonus", "Bonus payment"),
        PAYOUT("Payout", "Withdrawal to external account"),
        REFUND("Refund", "Refund from cancelled contract"),
        FEE("Fee", "Platform fee");

        private final String displayName;
        private final String description;

        TransactionType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Transaction status enumeration.
     */
    public enum TransactionStatus {
        PENDING("Pending"),
        COMPLETED("Completed"),
        FAILED("Failed"),
        CANCELLED("Cancelled");

        private final String displayName;

        TransactionStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    /**
     * Escrow status enumeration.
     */
    public enum EscrowStatus {
        HELD("Held in escrow"),
        PENDING_RELEASE("Pending release"),
        RELEASED("Released"),
        REFUNDED("Refunded"),
        DISPUTED("Under dispute");

        private final String displayName;

        EscrowStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    /**
     * Sort field enumeration.
     */
    public enum SortField {
        DATE, AMOUNT, TYPE, STATUS
    }

    /**
     * Tax export format enumeration.
     */
    public enum TaxExportFormat {
        CSV("CSV", "Comma-separated values"),
        PDF("PDF", "Portable Document Format"),
        JSON("JSON", "JavaScript Object Notation");

        private final String displayName;
        private final String description;

        TaxExportFormat(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Period enumeration for earnings calculation.
     */
    public enum Period {
        THIS_MONTH("This Month"),
        LAST_MONTH("Last Month"),
        THIS_YEAR("This Year"),
        LAST_YEAR("Last Year"),
        ALL_TIME("All Time");

        private final String displayName;

        Period(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }

        public Instant getStart() {
            LocalDate now = LocalDate.now();
            return switch (this) {
                case THIS_MONTH -> now.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case LAST_MONTH -> now.minusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case THIS_YEAR -> now.withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case LAST_YEAR -> now.minusYears(1).withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case ALL_TIME -> Instant.EPOCH;
            };
        }

        public Instant getEnd() {
            LocalDate now = LocalDate.now();
            return switch (this) {
                case THIS_MONTH -> now.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case LAST_MONTH -> now.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case THIS_YEAR -> now.plusYears(1).withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case LAST_YEAR -> now.withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                case ALL_TIME -> Instant.now().plusSeconds(86400);
            };
        }
    }

    /**
     * Earnings transaction record.
     */
    public record EarningsTransaction(
            String id,
            TransactionType type,
            BigDecimal amount,
            String currency,
            String description,
            String requesterId,
            String requesterName,
            String contractId,
            Instant timestamp,
            TransactionStatus status,
            Map<String, String> metadata
    ) {
        public EarningsTransaction {
            Objects.requireNonNull(id, "ID cannot be null");
            Objects.requireNonNull(type, "Type cannot be null");
            Objects.requireNonNull(amount, "Amount cannot be null");
            Objects.requireNonNull(currency, "Currency cannot be null");
            Objects.requireNonNull(timestamp, "Timestamp cannot be null");
            Objects.requireNonNull(status, "Status cannot be null");
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
    }

    /**
     * Escrow hold record.
     */
    public record EscrowHold(
            String id,
            BigDecimal amount,
            String currency,
            String requesterId,
            String requesterName,
            String contractId,
            EscrowStatus status,
            Instant createdAt,
            Instant expectedReleaseAt
    ) {
        public EscrowHold {
            Objects.requireNonNull(id, "ID cannot be null");
            Objects.requireNonNull(amount, "Amount cannot be null");
            Objects.requireNonNull(currency, "Currency cannot be null");
            Objects.requireNonNull(status, "Status cannot be null");
            Objects.requireNonNull(createdAt, "Created at cannot be null");
        }
    }

    /**
     * Earnings dashboard view.
     * Requirement 346.1: Display escrow state, payouts, receipts.
     */
    public record EarningsDashboard(
            String dsNodeId,
            BigDecimal totalEarned,
            BigDecimal totalPending,
            BigDecimal totalPaidOut,
            BigDecimal availableBalance,
            String currency,
            List<TransactionSummary> recentTransactions,
            List<EscrowSummary> activeEscrows,
            PeriodEarnings thisMonth,
            PeriodEarnings lastMonth,
            PeriodEarnings thisYear,
            Instant generatedAt
    ) {}

    /**
     * Transaction summary for display.
     */
    public record TransactionSummary(
            String id,
            TransactionType type,
            BigDecimal amount,
            String currency,
            String description,
            String requesterId,
            String requesterName,
            String contractId,
            Instant timestamp,
            TransactionStatus status
    ) {}

    /**
     * Escrow summary for display.
     */
    public record EscrowSummary(
            String id,
            BigDecimal amount,
            String currency,
            String requesterId,
            String requesterName,
            String contractId,
            EscrowStatus status,
            Instant createdAt,
            Instant expectedReleaseAt
    ) {}

    /**
     * Period earnings summary.
     */
    public record PeriodEarnings(
            String periodName,
            BigDecimal totalEarned,
            int transactionCount,
            Instant startDate,
            Instant endDate
    ) {}

    /**
     * Transaction filter.
     */
    public record TransactionFilter(
            Set<TransactionType> types,
            Set<TransactionStatus> statuses,
            Instant startDate,
            Instant endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String requesterId,
            SortField sortBy,
            boolean sortAscending,
            int offset,
            int limit
    ) {
        public TransactionFilter {
            if (offset < 0) offset = 0;
            if (limit <= 0) limit = 50;
            if (limit > 1000) limit = 1000;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Set<TransactionType> types;
            private Set<TransactionStatus> statuses;
            private Instant startDate;
            private Instant endDate;
            private BigDecimal minAmount;
            private BigDecimal maxAmount;
            private String requesterId;
            private SortField sortBy = SortField.DATE;
            private boolean sortAscending = false;
            private int offset = 0;
            private int limit = 50;

            public Builder types(Set<TransactionType> types) { this.types = types; return this; }
            public Builder statuses(Set<TransactionStatus> statuses) { this.statuses = statuses; return this; }
            public Builder startDate(Instant startDate) { this.startDate = startDate; return this; }
            public Builder endDate(Instant endDate) { this.endDate = endDate; return this; }
            public Builder minAmount(BigDecimal minAmount) { this.minAmount = minAmount; return this; }
            public Builder maxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; return this; }
            public Builder requesterId(String requesterId) { this.requesterId = requesterId; return this; }
            public Builder sortBy(SortField sortBy) { this.sortBy = sortBy; return this; }
            public Builder sortAscending(boolean sortAscending) { this.sortAscending = sortAscending; return this; }
            public Builder offset(int offset) { this.offset = offset; return this; }
            public Builder limit(int limit) { this.limit = limit; return this; }

            public TransactionFilter build() {
                return new TransactionFilter(types, statuses, startDate, endDate, minAmount, maxAmount,
                        requesterId, sortBy, sortAscending, offset, limit);
            }
        }
    }

    /**
     * Transaction receipt.
     * Requirement 346.2: Provide transaction proofs without exposing data.
     */
    public record TransactionReceipt(
            String receiptId,
            String transactionId,
            TransactionType type,
            BigDecimal amount,
            String currency,
            Instant timestamp,
            String contractHash,
            String proofHash,
            String signature,
            Instant generatedAt,
            Map<String, String> verificationData
    ) {
        public TransactionReceipt {
            Objects.requireNonNull(receiptId, "Receipt ID cannot be null");
            Objects.requireNonNull(transactionId, "Transaction ID cannot be null");
            Objects.requireNonNull(proofHash, "Proof hash cannot be null");
            Objects.requireNonNull(signature, "Signature cannot be null");
            verificationData = verificationData != null ? Map.copyOf(verificationData) : Map.of();
        }
    }

    /**
     * Receipt verification result.
     */
    public record ReceiptVerificationResult(
            boolean valid,
            String receiptId,
            String transactionId,
            List<String> errors
    ) {
        public static ReceiptVerificationResult valid(String receiptId, String transactionId) {
            return new ReceiptVerificationResult(true, receiptId, transactionId, List.of());
        }

        public static ReceiptVerificationResult invalid(String receiptId, String transactionId, List<String> errors) {
            return new ReceiptVerificationResult(false, receiptId, transactionId, errors);
        }
    }

    /**
     * Tax export result.
     * Requirement 346.4: Provide exportable summaries in standard formats.
     */
    public record TaxExport(
            String dsNodeId,
            int year,
            TaxExportFormat format,
            BigDecimal totalIncome,
            BigDecimal totalFees,
            BigDecimal netIncome,
            int transactionCount,
            String currency,
            byte[] exportData,
            String filename,
            Instant generatedAt
    ) {}

    // ==================== Interfaces ====================

    /**
     * Interface for earnings storage.
     */
    public interface EarningsStore {
        List<EarningsTransaction> getTransactions(String dsNodeId);
        EarningsTransaction getTransaction(String transactionId);
        List<EscrowHold> getEscrowHolds(String dsNodeId);
    }

    /**
     * Interface for receipt generation.
     */
    public interface ReceiptGenerator {
        TransactionReceipt generateReceipt(EarningsTransaction transaction);
        ReceiptVerificationResult verifyReceipt(TransactionReceipt receipt);
    }

    /**
     * Interface for tax export.
     */
    public interface TaxExporter {
        TaxExport export(String dsNodeId, int year, List<EarningsTransaction> transactions, TaxExportFormat format);
    }

    // ==================== Default Implementations ====================

    /**
     * Default receipt generator.
     */
    public static class DefaultReceiptGenerator implements ReceiptGenerator {
        @Override
        public TransactionReceipt generateReceipt(EarningsTransaction transaction) {
            String receiptId = "RCP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String proofHash = generateProofHash(transaction);
            String signature = generateSignature(transaction, proofHash);

            Map<String, String> verificationData = new HashMap<>();
            verificationData.put("algorithm", "SHA-256");
            verificationData.put("version", "1.0");

            return new TransactionReceipt(
                    receiptId,
                    transaction.id(),
                    transaction.type(),
                    transaction.amount(),
                    transaction.currency(),
                    transaction.timestamp(),
                    transaction.contractId() != null ? hashString(transaction.contractId()) : null,
                    proofHash,
                    signature,
                    Instant.now(),
                    verificationData
            );
        }

        @Override
        public ReceiptVerificationResult verifyReceipt(TransactionReceipt receipt) {
            List<String> errors = new ArrayList<>();

            if (receipt.proofHash() == null || receipt.proofHash().isEmpty()) {
                errors.add("Missing proof hash");
            }
            if (receipt.signature() == null || receipt.signature().isEmpty()) {
                errors.add("Missing signature");
            }

            // In production, would verify signature cryptographically
            if (errors.isEmpty()) {
                return ReceiptVerificationResult.valid(receipt.receiptId(), receipt.transactionId());
            }
            return ReceiptVerificationResult.invalid(receipt.receiptId(), receipt.transactionId(), errors);
        }

        private String generateProofHash(EarningsTransaction transaction) {
            String data = transaction.id() + "|" + transaction.amount() + "|" + transaction.timestamp();
            return hashString(data);
        }

        private String generateSignature(EarningsTransaction transaction, String proofHash) {
            // In production, would use actual cryptographic signing
            String data = proofHash + "|" + transaction.id();
            return "SIG:" + hashString(data);
        }

        private String hashString(String input) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            } catch (Exception e) {
                return "HASH_ERROR";
            }
        }
    }

    /**
     * Default tax exporter.
     */
    public static class DefaultTaxExporter implements TaxExporter {
        private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        @Override
        public TaxExport export(String dsNodeId, int year, List<EarningsTransaction> transactions, TaxExportFormat format) {
            BigDecimal totalIncome = transactions.stream()
                    .filter(t -> t.type() == TransactionType.EARNING || t.type() == TransactionType.BONUS)
                    .map(EarningsTransaction::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalFees = transactions.stream()
                    .filter(t -> t.type() == TransactionType.FEE)
                    .map(EarningsTransaction::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal netIncome = totalIncome.subtract(totalFees);

            byte[] exportData = switch (format) {
                case CSV -> generateCsv(transactions, year);
                case JSON -> generateJson(transactions, year, totalIncome, totalFees, netIncome);
                case PDF -> generatePdfPlaceholder(transactions, year, totalIncome, totalFees, netIncome);
            };

            String filename = String.format("yachaq_tax_summary_%d.%s", year, format.name().toLowerCase());

            return new TaxExport(
                    dsNodeId,
                    year,
                    format,
                    totalIncome,
                    totalFees,
                    netIncome,
                    transactions.size(),
                    "USD",
                    exportData,
                    filename,
                    Instant.now()
            );
        }

        private byte[] generateCsv(List<EarningsTransaction> transactions, int year) {
            StringBuilder sb = new StringBuilder();
            sb.append("Date,Type,Amount,Currency,Description,Contract ID,Status\n");

            for (EarningsTransaction t : transactions) {
                sb.append(formatDate(t.timestamp())).append(",");
                sb.append(t.type().getDisplayName()).append(",");
                sb.append(t.amount().setScale(2, RoundingMode.HALF_UP)).append(",");
                sb.append(t.currency()).append(",");
                sb.append(escapeCsv(t.description())).append(",");
                sb.append(t.contractId() != null ? t.contractId() : "").append(",");
                sb.append(t.status().getDisplayName()).append("\n");
            }

            return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        private byte[] generateJson(List<EarningsTransaction> transactions, int year,
                                    BigDecimal totalIncome, BigDecimal totalFees, BigDecimal netIncome) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"year\": ").append(year).append(",\n");
            sb.append("  \"totalIncome\": ").append(totalIncome.setScale(2, RoundingMode.HALF_UP)).append(",\n");
            sb.append("  \"totalFees\": ").append(totalFees.setScale(2, RoundingMode.HALF_UP)).append(",\n");
            sb.append("  \"netIncome\": ").append(netIncome.setScale(2, RoundingMode.HALF_UP)).append(",\n");
            sb.append("  \"transactionCount\": ").append(transactions.size()).append(",\n");
            sb.append("  \"currency\": \"USD\",\n");
            sb.append("  \"transactions\": [\n");

            for (int i = 0; i < transactions.size(); i++) {
                EarningsTransaction t = transactions.get(i);
                sb.append("    {\n");
                sb.append("      \"date\": \"").append(formatDate(t.timestamp())).append("\",\n");
                sb.append("      \"type\": \"").append(t.type().name()).append("\",\n");
                sb.append("      \"amount\": ").append(t.amount().setScale(2, RoundingMode.HALF_UP)).append(",\n");
                sb.append("      \"currency\": \"").append(t.currency()).append("\"\n");
                sb.append("    }").append(i < transactions.size() - 1 ? "," : "").append("\n");
            }

            sb.append("  ]\n");
            sb.append("}\n");

            return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        private byte[] generatePdfPlaceholder(List<EarningsTransaction> transactions, int year,
                                              BigDecimal totalIncome, BigDecimal totalFees, BigDecimal netIncome) {
            // In production, would use a PDF library
            String content = String.format(
                    "YACHAQ Tax Summary %d\n\nTotal Income: %s USD\nTotal Fees: %s USD\nNet Income: %s USD\nTransactions: %d",
                    year,
                    totalIncome.setScale(2, RoundingMode.HALF_UP),
                    totalFees.setScale(2, RoundingMode.HALF_UP),
                    netIncome.setScale(2, RoundingMode.HALF_UP),
                    transactions.size()
            );
            return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        private String formatDate(Instant instant) {
            return DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()).toLocalDate());
        }

        private String escapeCsv(String value) {
            if (value == null) return "";
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }

    /**
     * In-memory earnings store for testing.
     */
    public static class InMemoryEarningsStore implements EarningsStore {
        private final Map<String, List<EarningsTransaction>> transactionsByDs = new HashMap<>();
        private final Map<String, EarningsTransaction> transactionsById = new HashMap<>();
        private final Map<String, List<EscrowHold>> escrowsByDs = new HashMap<>();

        public void addTransaction(String dsNodeId, EarningsTransaction transaction) {
            transactionsByDs.computeIfAbsent(dsNodeId, k -> new ArrayList<>()).add(transaction);
            transactionsById.put(transaction.id(), transaction);
        }

        public void addEscrowHold(String dsNodeId, EscrowHold escrow) {
            escrowsByDs.computeIfAbsent(dsNodeId, k -> new ArrayList<>()).add(escrow);
        }

        @Override
        public List<EarningsTransaction> getTransactions(String dsNodeId) {
            return transactionsByDs.getOrDefault(dsNodeId, List.of());
        }

        @Override
        public EarningsTransaction getTransaction(String transactionId) {
            return transactionsById.get(transactionId);
        }

        @Override
        public List<EscrowHold> getEscrowHolds(String dsNodeId) {
            return escrowsByDs.getOrDefault(dsNodeId, List.of());
        }
    }
}
