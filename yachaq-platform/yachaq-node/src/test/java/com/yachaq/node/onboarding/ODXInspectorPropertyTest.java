package com.yachaq.node.onboarding;

import com.yachaq.node.odx.ODXBuilder;
import com.yachaq.node.odx.ODXEntry;
import com.yachaq.node.odx.ODXEntry.*;
import com.yachaq.node.onboarding.ODXInspector.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for ODXInspector.
 * 
 * Validates: Requirements 342.1, 342.2, 342.3, 342.5
 * 
 * CRITICAL: These tests verify that NO raw payload content is ever displayed.
 */
class ODXInspectorPropertyTest {

    private ODXInspector createInspector() {
        return new ODXInspector(new ODXBuilder(ZoneId.of("UTC"), "1.0.0"));
    }

    private ODXEntry createSafeEntry(String facetKey, int count) {
        return ODXEntry.builder()
                .generateId()
                .facetKey(facetKey)
                .timeBucket("2024-01-15")
                .count(count)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.CITY)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build();
    }

    // ==================== Task 85.1: ODX Label Browser Tests ====================

    @Test
    void labelBrowser_showsCountsOnly() {
        // Requirement 342.1: Show labels with counts/buckets only (never raw data)
        ODXInspector inspector = createInspector();
        
        List<ODXEntry> entries = List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("health:heart_rate", 50),
                createSafeEntry("media:music", 200)
        );
        inspector.loadEntries(entries);
        
        LabelBrowserView view = inspector.getLabelBrowser();
        
        assertThat(view.categories()).isNotEmpty();
        assertThat(view.totalEntries()).isEqualTo(3);
        
        // Verify only counts are shown, no raw values
        for (LabelCategory category : view.categories()) {
            for (LabelItem item : category.items()) {
                assertThat(item.count()).isGreaterThan(0);
                assertThat(item.facetKey()).matches("^[a-z]+:[a-z_]+$");
                // Verify no raw data patterns in display name
                assertThat(item.displayName()).doesNotContainIgnoringCase("raw");
                assertThat(item.displayName()).doesNotContainIgnoringCase("payload");
            }
        }
    }

    @Test
    void labelBrowser_groupsByNamespace() {
        ODXInspector inspector = createInspector();
        
        List<ODXEntry> entries = List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("health:sleep", 50),
                createSafeEntry("media:music", 200),
                createSafeEntry("media:video", 75)
        );
        inspector.loadEntries(entries);
        
        LabelBrowserView view = inspector.getLabelBrowser();
        
        // Should have health and media categories
        assertThat(view.categories())
                .extracting(LabelCategory::namespace)
                .contains("health", "media");
    }

    @Test
    void labelBrowser_aggregatesCounts() {
        ODXInspector inspector = createInspector();
        
        List<ODXEntry> entries = List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("health:steps", 50) // Same label, different entry
        );
        inspector.loadEntries(entries);
        
        LabelBrowserView view = inspector.getLabelBrowser();
        
        LabelCategory healthCategory = view.categories().stream()
                .filter(c -> c.namespace().equals("health"))
                .findFirst()
                .orElseThrow();
        
        // Total count should be aggregated
        assertThat(healthCategory.totalCount()).isEqualTo(150);
    }

    @Property
    void labelBrowser_neverShowsRawData(@ForAll("safeEntries") List<ODXEntry> entries) {
        // Property: Label browser NEVER shows raw data patterns
        ODXInspector inspector = createInspector();
        inspector.loadEntries(entries);
        
        LabelBrowserView view = inspector.getLabelBrowser();
        
        for (LabelCategory category : view.categories()) {
            assertThat(category.displayName()).doesNotContainIgnoringCase("raw");
            assertThat(category.displayName()).doesNotContainIgnoringCase("payload");
            assertThat(category.displayName()).doesNotContainIgnoringCase("content");
            
            for (LabelItem item : category.items()) {
                assertThat(item.displayName()).doesNotContainIgnoringCase("raw");
                assertThat(item.displayName()).doesNotContainIgnoringCase("payload");
                assertThat(item.displayName()).doesNotContainIgnoringCase("content");
            }
        }
    }

    // ==================== Task 85.2: Match Explanation Tests ====================

    @Test
    void matchExplanation_providesReasons() {
        // Requirement 342.2: Provide match explanations per request
        ODXInspector inspector = createInspector();
        
        List<ODXEntry> entries = List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("health:heart_rate", 50)
        );
        inspector.loadEntries(entries);
        
        RequestCriteria criteria = new RequestCriteria(
                "req-123",
                List.of("health:.*"),
                "2024-01-01",
                "2024-12-31",
                null
        );
        
        MatchExplanation explanation = inspector.explainMatch(criteria);
        
        assertThat(explanation.requestId()).isEqualTo("req-123");
        assertThat(explanation.isMatch()).isTrue();
        assertThat(explanation.reasons()).isNotEmpty();
        assertThat(explanation.privacyNote()).contains("ODX labels only");
    }

    @Test
    void matchExplanation_showsMatchedLabels() {
        ODXInspector inspector = createInspector();
        
        List<ODXEntry> entries = List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("media:music", 200)
        );
        inspector.loadEntries(entries);
        
        RequestCriteria criteria = new RequestCriteria(
                "req-456",
                List.of("health:.*"),
                null,
                null,
                null
        );
        
        MatchExplanation explanation = inspector.explainMatch(criteria);
        
        assertThat(explanation.matchedLabels()).contains("health:.*");
    }

    @Test
    void matchExplanation_noMatchWhenNoLabels() {
        ODXInspector inspector = createInspector();
        
        List<ODXEntry> entries = List.of(
                createSafeEntry("media:music", 200)
        );
        inspector.loadEntries(entries);
        
        RequestCriteria criteria = new RequestCriteria(
                "req-789",
                List.of("health:.*"),
                null,
                null,
                null
        );
        
        MatchExplanation explanation = inspector.explainMatch(criteria);
        
        assertThat(explanation.isMatch()).isFalse();
        assertThat(explanation.matchedLabels()).isEmpty();
    }

    @Test
    void matchExplanation_rejectsNullCriteria() {
        ODXInspector inspector = createInspector();
        
        assertThatThrownBy(() -> inspector.explainMatch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    // ==================== Task 85.3: Hidden Data Explanation Tests ====================

    @Test
    void hiddenDataExplanation_containsGuarantees() {
        // Requirement 342.3: Explain raw vault is never shown to coordinator
        ODXInspector inspector = createInspector();
        inspector.loadEntries(List.of(createSafeEntry("health:steps", 100)));
        
        HiddenDataExplanation explanation = inspector.getHiddenDataExplanation();
        
        assertThat(explanation.guarantees()).isNotEmpty();
        assertThat(explanation.guarantees())
                .extracting(PrivacyGuarantee::id)
                .contains("RAW_DATA_LOCAL", "ODX_ONLY_DISCOVERY", "P2P_DELIVERY");
    }

    @Test
    void hiddenDataExplanation_explainsRawDataStaysLocal() {
        ODXInspector inspector = createInspector();
        inspector.loadEntries(List.of(createSafeEntry("health:steps", 100)));
        
        HiddenDataExplanation explanation = inspector.getHiddenDataExplanation();
        
        PrivacyGuarantee rawDataGuarantee = explanation.guarantees().stream()
                .filter(g -> g.id().equals("RAW_DATA_LOCAL"))
                .findFirst()
                .orElseThrow();
        
        assertThat(rawDataGuarantee.description()).containsIgnoringCase("local");
        assertThat(rawDataGuarantee.description()).containsIgnoringCase("never leave");
    }

    @Test
    void hiddenDataExplanation_listsHiddenDataTypes() {
        ODXInspector inspector = createInspector();
        
        List<ODXEntry> entries = List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("media:music", 200)
        );
        inspector.loadEntries(entries);
        
        HiddenDataExplanation explanation = inspector.getHiddenDataExplanation();
        
        assertThat(explanation.hiddenDataTypes()).isNotEmpty();
    }

    @Test
    void hiddenDataExplanation_hasSummary() {
        ODXInspector inspector = createInspector();
        inspector.loadEntries(List.of(createSafeEntry("health:steps", 100)));
        
        HiddenDataExplanation explanation = inspector.getHiddenDataExplanation();
        
        assertThat(explanation.summary()).isNotBlank();
        assertThat(explanation.summary()).containsIgnoringCase("encrypted");
    }


    // ==================== Task 85.4: Display Safety Validation Tests ====================

    @Test
    void displayValidation_passesForSafeEntries() {
        // Requirement 342.5: Verify no raw payload content displayed
        ODXInspector inspector = createInspector();
        
        List<ODXEntry> entries = List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("media:music", 200)
        );
        inspector.loadEntries(entries);
        
        DisplayValidationResult result = inspector.validateDisplaySafety();
        
        assertThat(result.isSafe()).isTrue();
        assertThat(result.safeEntries()).isEqualTo(2);
        assertThat(result.unsafeEntries()).isEqualTo(0);
        assertThat(result.violations()).isEmpty();
    }

    @Property
    void displayValidation_rejectsForbiddenPatterns(
            @ForAll("forbiddenPatterns") String forbiddenPattern) {
        // Property: Entries with forbidden patterns are rejected at construction time
        // ODXEntry validates facet keys during construction and throws ODXSafetyException
        // for forbidden patterns - this is the correct security behavior
        
        assertThatThrownBy(() -> ODXEntry.builder()
                .generateId()
                .facetKey("domain:" + forbiddenPattern)
                .timeBucket("2024-01-15")
                .count(100)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.CITY)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build())
                .isInstanceOf(ODXEntry.ODXSafetyException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void displayValidation_neverShowsRawPayload() {
        ODXInspector inspector = createInspector();
        
        // Load safe entries
        List<ODXEntry> entries = List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("fitness:activities", 50)
        );
        inspector.loadEntries(entries);
        
        // Get all displayable content
        LabelBrowserView view = inspector.getLabelBrowser();
        
        // Verify no raw payload patterns anywhere
        for (LabelCategory category : view.categories()) {
            assertThat(category.displayName()).doesNotContainIgnoringCase("raw");
            assertThat(category.displayName()).doesNotContainIgnoringCase("payload");
            assertThat(category.displayName()).doesNotContainIgnoringCase("content");
            assertThat(category.displayName()).doesNotContainIgnoringCase("message");
            assertThat(category.displayName()).doesNotContainIgnoringCase("email");
            assertThat(category.displayName()).doesNotContainIgnoringCase("phone");
        }
    }

    // ==================== Edge Case Tests ====================

    @Test
    void constructor_rejectsNullBuilder() {
        assertThatThrownBy(() -> new ODXInspector(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void loadEntries_rejectsNull() {
        ODXInspector inspector = createInspector();
        
        assertThatThrownBy(() -> inspector.loadEntries(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void labelBrowser_emptyWhenNoEntries() {
        ODXInspector inspector = createInspector();
        inspector.loadEntries(List.of());
        
        LabelBrowserView view = inspector.getLabelBrowser();
        
        assertThat(view.categories()).isEmpty();
        assertThat(view.totalEntries()).isEqualTo(0);
    }

    @Test
    void entryCount_tracksLoadedEntries() {
        ODXInspector inspector = createInspector();
        
        assertThat(inspector.getEntryCount()).isEqualTo(0);
        
        inspector.loadEntries(List.of(
                createSafeEntry("health:steps", 100),
                createSafeEntry("media:music", 200)
        ));
        
        assertThat(inspector.getEntryCount()).isEqualTo(2);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<List<ODXEntry>> safeEntries() {
        return Arbitraries.of(
                "health:steps", "health:sleep", "health:heart_rate",
                "media:music", "media:video", "media:podcasts",
                "fitness:activities", "fitness:routes"
        ).list().ofMinSize(1).ofMaxSize(10)
                .map(facets -> facets.stream()
                        .map(f -> createSafeEntry(f, 100))
                        .toList());
    }

    @Provide
    Arbitrary<String> forbiddenPatterns() {
        return Arbitraries.of(
                "raw_data", "payload_content", "message_text",
                "email_body", "phone_number", "address_line"
        );
    }
}
