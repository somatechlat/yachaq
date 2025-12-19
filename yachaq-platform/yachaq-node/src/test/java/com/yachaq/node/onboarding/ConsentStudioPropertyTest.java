package com.yachaq.node.onboarding;

import com.yachaq.node.contract.ContractBuilder;
import com.yachaq.node.contract.ContractBuilder.UserChoices;
import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.onboarding.ConsentStudio.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for ConsentStudio.
 * 
 * Validates: Requirements 344.1, 344.2, 344.3, 344.4, 344.5
 */
class ConsentStudioPropertyTest {

    private ConsentStudio createConsentStudio() {
        ContractBuilder contractBuilder = new ContractBuilder("test-node-id");
        PlanPreviewService planPreviewService = new DefaultPlanPreviewService();
        PayoutCalculator payoutCalculator = new DefaultPayoutCalculator();
        return new ConsentStudio(contractBuilder, planPreviewService, payoutCalculator);
    }

    private DataRequest createValidRequest(String id, Set<String> requiredLabels, Set<String> optionalLabels) {
        String signature = "valid-signature-" + UUID.randomUUID().toString() + UUID.randomUUID().toString();
        String policyStamp = "valid-policy-stamp-" + UUID.randomUUID().toString();
        
        return DataRequest.builder()
                .id(id)
                .requesterId("requester-1")
                .requesterName("Test Requester")
                .type(RequestType.BROADCAST)
                .requiredLabels(requiredLabels)
                .optionalLabels(optionalLabels)
                .outputMode(OutputMode.CLEAN_ROOM)
                .compensation(new CompensationOffer(10.0, "USD", "escrow-123"))
                .policyStamp(policyStamp)
                .signature(signature)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
    }

    private UserChoices createChoices(Set<String> selectedLabels, OutputMode outputMode, boolean revealIdentity) {
        return UserChoices.builder()
                .selectedLabels(selectedLabels)
                .outputMode(outputMode)
                .revealIdentity(revealIdentity)
                .build();
    }

    // ==================== Task 87.1: Plan Preview Tests ====================

    @Test
    void planPreview_displaysPrivacyImpactMeter() {
        // Requirement 344.1: Display plan preview with privacy impact meter
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of("media:podcasts"));
        UserChoices choices = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, false);
        
        PlanPreview preview = studio.getPlanPreview(request, choices);
        
        assertThat(preview.privacyImpact()).isNotNull();
        assertThat(preview.privacyImpact().score()).isBetween(0, 100);
        assertThat(preview.privacyImpact().level()).isNotNull();
        assertThat(preview.privacyImpact().factors()).isNotEmpty();
    }

    @Test
    void planPreview_showsDataCategories() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music", "entertainment:podcasts"), Set.of());
        UserChoices choices = createChoices(Set.of("media:music", "entertainment:podcasts"), 
                OutputMode.AGGREGATE_ONLY, false);
        
        PlanPreview preview = studio.getPlanPreview(request, choices);
        
        assertThat(preview.dataCategories()).isNotEmpty();
    }

    @Test
    void planPreview_showsEstimatedPayout() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of());
        UserChoices choices = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, false);
        
        PlanPreview preview = studio.getPlanPreview(request, choices);
        
        assertThat(preview.estimatedPayout()).isNotNull();
        assertThat(preview.currency()).isEqualTo("USD");
    }

    @Test
    void planPreview_higherImpactForSensitiveData() {
        ConsentStudio studio = createConsentStudio();
        
        // Non-sensitive request
        DataRequest nonSensitive = createValidRequest("req-1", 
                Set.of("media:music"), Set.of());
        UserChoices nonSensitiveChoices = createChoices(Set.of("media:music"), 
                OutputMode.AGGREGATE_ONLY, false);
        
        // Sensitive request (health data)
        DataRequest sensitive = createValidRequest("req-2", 
                Set.of("health:steps"), Set.of());
        UserChoices sensitiveChoices = createChoices(Set.of("health:steps"), 
                OutputMode.AGGREGATE_ONLY, false);
        
        PlanPreview nonSensitivePreview = studio.getPlanPreview(nonSensitive, nonSensitiveChoices);
        PlanPreview sensitivePreview = studio.getPlanPreview(sensitive, sensitiveChoices);
        
        assertThat(sensitivePreview.privacyImpact().score())
                .isGreaterThan(nonSensitivePreview.privacyImpact().score());
    }

    // ==================== Task 87.2: Scope Editor Tests ====================

    @Test
    void scopeEditor_showsLabelFamilies() {
        // Requirement 344.2: Allow editing label families
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music", "media:video"), Set.of("entertainment:podcasts"));
        
        ScopeEditorView editor = studio.getScopeEditor(request, null);
        
        assertThat(editor.labelFamilies()).isNotEmpty();
    }

    @Test
    void scopeEditor_showsOutputModeOptions() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of());
        
        ScopeEditorView editor = studio.getScopeEditor(request, null);
        
        assertThat(editor.outputModes()).isNotEmpty();
        // Should include aggregate only (most restrictive)
        assertThat(editor.outputModes())
                .extracting(OutputModeOption::mode)
                .contains(OutputMode.AGGREGATE_ONLY);
    }

    @Test
    void scopeEditor_showsTimeWindowOptions() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of());
        
        ScopeEditorView editor = studio.getScopeEditor(request, null);
        
        assertThat(editor.timeOptions()).isNotNull();
        assertThat(editor.timeOptions().presets()).isNotEmpty();
    }

    @Test
    void scopeEditor_showsGranularityOptions() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of());
        
        ScopeEditorView editor = studio.getScopeEditor(request, null);
        
        assertThat(editor.granularityOptions()).isNotNull();
        assertThat(editor.granularityOptions().timeGranularities()).isNotEmpty();
        assertThat(editor.granularityOptions().geoGranularities()).isNotEmpty();
    }

    // ==================== Task 87.3: Identity Reveal Switch Tests ====================

    @Test
    void identityReveal_defaultIsOff() {
        // Requirement 344.3: Show explicit switch with default OFF
        ConsentStudio studio = createConsentStudio();
        
        IdentityRevealState state = studio.getIdentityRevealState(null);
        
        assertThat(state.isRevealed()).isFalse();
        assertThat(state.currentLevel()).isEqualTo("ANONYMOUS");
    }

    @Test
    void identityReveal_showsOptions() {
        ConsentStudio studio = createConsentStudio();
        
        IdentityRevealState state = studio.getIdentityRevealState(null);
        
        assertThat(state.options()).isNotEmpty();
        assertThat(state.options())
                .extracting(IdentityRevealOption::level)
                .contains("ANONYMOUS", "BASIC", "FULL");
    }

    @Test
    void identityReveal_anonymousIsDefault() {
        ConsentStudio studio = createConsentStudio();
        
        IdentityRevealState state = studio.getIdentityRevealState(null);
        
        IdentityRevealOption defaultOption = state.options().stream()
                .filter(IdentityRevealOption::isDefault)
                .findFirst()
                .orElseThrow();
        
        assertThat(defaultOption.level()).isEqualTo("ANONYMOUS");
    }

    @Test
    void identityReveal_respectsUserChoice() {
        ConsentStudio studio = createConsentStudio();
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("media:music"))
                .revealIdentity(true)
                .identityRevealLevel("BASIC")
                .build();
        
        IdentityRevealState state = studio.getIdentityRevealState(choices);
        
        assertThat(state.isRevealed()).isTrue();
        assertThat(state.currentLevel()).isEqualTo("BASIC");
    }


    // ==================== Task 87.4: Payout Change Visualization Tests ====================

    @Test
    void payoutComparison_showsDifference() {
        // Requirement 344.4: Show how payout changes based on selections
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of("media:podcasts", "media:video"));
        
        UserChoices baseChoices = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, false);
        UserChoices modifiedChoices = createChoices(
                Set.of("media:music", "media:podcasts", "media:video"), 
                OutputMode.AGGREGATE_ONLY, false);
        
        PayoutComparison comparison = studio.comparePayouts(request, baseChoices, modifiedChoices);
        
        assertThat(comparison.basePayout()).isNotNull();
        assertThat(comparison.modifiedPayout()).isNotNull();
        assertThat(comparison.difference()).isNotNull();
        // More labels should increase payout
        assertThat(comparison.modifiedPayout()).isGreaterThan(comparison.basePayout());
    }

    @Test
    void payoutComparison_showsFactors() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of("media:podcasts"));
        
        UserChoices baseChoices = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, false);
        UserChoices modifiedChoices = createChoices(
                Set.of("media:music", "media:podcasts"), 
                OutputMode.AGGREGATE_ONLY, true); // Added label and identity reveal
        
        PayoutComparison comparison = studio.comparePayouts(request, baseChoices, modifiedChoices);
        
        assertThat(comparison.factors()).isNotEmpty();
    }

    @Test
    void payoutComparison_identityRevealIncreasePayout() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of());
        
        UserChoices withoutIdentity = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, false);
        UserChoices withIdentity = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, true);
        
        PayoutComparison comparison = studio.comparePayouts(request, withoutIdentity, withIdentity);
        
        assertThat(comparison.modifiedPayout()).isGreaterThan(comparison.basePayout());
    }

    @Property
    void payoutComparison_moreLabelsIncreasePayout(@ForAll("labelCounts") int additionalLabels) {
        // Property: Adding more optional labels should increase payout
        ConsentStudio studio = createConsentStudio();
        
        Set<String> optionalLabels = new HashSet<>();
        for (int i = 0; i < additionalLabels; i++) {
            optionalLabels.add("media:label" + i);
        }
        
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), optionalLabels);
        
        UserChoices baseChoices = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, false);
        
        Set<String> allLabels = new HashSet<>();
        allLabels.add("media:music");
        allLabels.addAll(optionalLabels);
        UserChoices modifiedChoices = createChoices(allLabels, OutputMode.AGGREGATE_ONLY, false);
        
        PayoutComparison comparison = studio.comparePayouts(request, baseChoices, modifiedChoices);
        
        if (additionalLabels > 0) {
            assertThat(comparison.modifiedPayout()).isGreaterThanOrEqualTo(comparison.basePayout());
        }
    }

    // ==================== Contract Building Tests ====================

    @Test
    void buildContract_createsValidDraft() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", 
                Set.of("media:music"), Set.of());
        UserChoices choices = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, false);
        
        ContractDraft draft = studio.buildContract(request, choices);
        
        assertThat(draft).isNotNull();
        assertThat(draft.requestId()).isEqualTo("req-1");
        assertThat(draft.selectedLabels()).contains("media:music");
    }

    // ==================== Edge Case Tests ====================

    @Test
    void constructor_rejectsNullContractBuilder() {
        assertThatThrownBy(() -> new ConsentStudio(null, 
                new DefaultPlanPreviewService(), new DefaultPayoutCalculator()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void constructor_rejectsNullPlanPreviewService() {
        assertThatThrownBy(() -> new ConsentStudio(
                new ContractBuilder("test-node"), null, new DefaultPayoutCalculator()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void constructor_rejectsNullPayoutCalculator() {
        assertThatThrownBy(() -> new ConsentStudio(
                new ContractBuilder("test-node"), new DefaultPlanPreviewService(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void planPreview_rejectsNullRequest() {
        ConsentStudio studio = createConsentStudio();
        UserChoices choices = createChoices(Set.of("media:music"), OutputMode.AGGREGATE_ONLY, false);
        
        assertThatThrownBy(() -> studio.getPlanPreview(null, choices))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void planPreview_rejectsNullChoices() {
        ConsentStudio studio = createConsentStudio();
        DataRequest request = createValidRequest("req-1", Set.of("media:music"), Set.of());
        
        assertThatThrownBy(() -> studio.getPlanPreview(request, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<Integer> labelCounts() {
        return Arbitraries.integers().between(0, 5);
    }
}
