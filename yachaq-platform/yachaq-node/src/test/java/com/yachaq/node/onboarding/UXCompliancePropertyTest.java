package com.yachaq.node.onboarding;

import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.onboarding.UXComplianceService.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for UXComplianceService.
 * 
 * **Feature: yachaq-platform, Property 67: Anti-Dark-Pattern Compliance**
 * **Validates: Requirements 361.1, 361.2**
 */
class UXCompliancePropertyTest {

    private final UXComplianceService service = new UXComplianceService();

    // ========================================================================
    // Property 1: Privacy-Preserving Defaults - Identity Reveal OFF
    // For any consent screen, identity reveal must default to OFF
    // **Property 67: Anti-Dark-Pattern Compliance**
    // **Validates: Requirements 361.1, 361.2**
    // ========================================================================
    
    @Property(tries = 100)
    @Label("Privacy-preserving defaults always have identity reveal OFF")
    void privacyPreservingDefaultsHaveIdentityRevealOff() {
        PrivacyPreservingDefaults defaults = service.getPrivacyPreservingDefaults();
        
        assertThat(defaults.identityRevealDefault())
            .as("Identity reveal must default to OFF")
            .isFalse();
        
        assertThat(defaults.identityLevelDefault())
            .as("Identity level must default to ANONYMOUS")
            .isEqualTo("ANONYMOUS");
    }

    // ========================================================================
    // Property 2: Applied Defaults Are Privacy-Preserving
    // For any data request, applied defaults must be privacy-preserving
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Applied defaults are privacy-preserving")
    void appliedDefaultsArePrivacyPreserving(
            @ForAll("dataRequests") DataRequest request) {
        
        UserChoices defaults = service.applyPrivacyPreservingDefaults(request);
        
        assertThat(defaults.revealIdentity())
            .as("Applied defaults must have identity reveal OFF")
            .isFalse();
        
        assertThat(defaults.identityRevealLevel())
            .as("Applied defaults must have ANONYMOUS identity level")
            .isEqualTo("ANONYMOUS");
        
        // Only required labels should be selected by default
        assertThat(defaults.selectedLabels())
            .as("Applied defaults should only include required labels")
            .containsExactlyInAnyOrderElementsOf(request.requiredLabels());
    }

    // ========================================================================
    // Property 3: Compliant Consent Screens Pass Validation
    // For any generated compliant screen, validation must pass
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Generated compliant screens pass validation")
    void generatedCompliantScreensPassValidation(
            @ForAll("dataRequests") DataRequest request) {
        
        UserChoices choices = service.applyPrivacyPreservingDefaults(request);
        ConsentScreenContent screen = service.generateCompliantScreen(request, choices);
        
        ConsentScreenValidation validation = service.validateConsentScreen(screen);
        
        assertThat(validation.isCompliant())
            .as("Generated compliant screen must pass validation")
            .isTrue();
        
        assertThat(validation.issues())
            .as("Compliant screen should have no ERROR issues")
            .filteredOn(i -> i.severity() == IssueSeverity.ERROR)
            .isEmpty();
    }

    // ========================================================================
    // Property 4: Manipulative Language Is Detected
    // For any content with manipulative language, detection must find it
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Manipulative language is detected")
    void manipulativeLanguageIsDetected(
            @ForAll("manipulativePhrases") String phrase) {
        
        String content = "Please " + phrase + " to share your data";
        ManipulationValidation validation = service.validateNonManipulative(content);
        
        assertThat(validation.isCompliant())
            .as("Content with manipulative language must fail validation")
            .isFalse();
        
        assertThat(validation.detectedPatterns())
            .as("Manipulative phrase must be detected")
            .isNotEmpty();
    }

    // ========================================================================
    // Property 5: Clean Content Passes Manipulation Check
    // For any clean content, manipulation check must pass
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Clean content passes manipulation check")
    void cleanContentPassesManipulationCheck(
            @ForAll("cleanPhrases") String phrase) {
        
        ManipulationValidation validation = service.validateNonManipulative(phrase);
        
        assertThat(validation.isCompliant())
            .as("Clean content must pass manipulation check")
            .isTrue();
        
        assertThat(validation.detectedPatterns())
            .as("Clean content should have no detected patterns")
            .isEmpty();
    }

    // ========================================================================
    // Property 6: Consent Screen Must Have Required Elements
    // For any consent screen, required elements must be present
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Consent screen validation requires essential elements")
    void consentScreenValidationRequiresEssentialElements() {
        // Screen missing title
        ConsentScreenContent missingTitle = new ConsentScreenContent(
                null, "Description", "Usage", "Revoke", "Duration",
                true, true, true, List.of(), "Low impact"
        );
        assertThat(service.validateConsentScreen(missingTitle).isCompliant())
            .as("Screen without title must fail")
            .isFalse();
        
        // Screen missing revocation option
        ConsentScreenContent missingRevocation = new ConsentScreenContent(
                "Title", "Description", "Usage", "Revoke", "Duration",
                false, true, true, List.of(), "Low impact"
        );
        assertThat(service.validateConsentScreen(missingRevocation).isCompliant())
            .as("Screen without revocation option must fail")
            .isFalse();
        
        // Screen with unequal buttons
        ConsentScreenContent unequalButtons = new ConsentScreenContent(
                "Title", "Description", "Usage", "Revoke", "Duration",
                true, true, false, List.of(), "Low impact"
        );
        assertThat(service.validateConsentScreen(unequalButtons).isCompliant())
            .as("Screen with unequal buttons must fail")
            .isFalse();
    }

    // ========================================================================
    // Property 7: Audit Checklist Covers All Requirements
    // For any audit, all required checks must be performed
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Audit checklist covers all requirements")
    void auditChecklistCoversAllRequirements(
            @ForAll("dataRequests") DataRequest request) {
        
        UserChoices choices = service.applyPrivacyPreservingDefaults(request);
        ConsentScreenContent screen = service.generateCompliantScreen(request, choices);
        
        AuditResult result = service.runAuditChecklist(screen, choices, screen.description());
        
        // Must have all required checks
        Set<String> checkIds = new HashSet<>();
        for (AuditCheckResult check : result.checks()) {
            checkIds.add(check.checkId());
        }
        
        assertThat(checkIds)
            .as("Audit must include all required checks")
            .contains(
                "CLEAR_CONSENT_SCREENS",
                "PRIVACY_PRESERVING_DEFAULTS",
                "NON_MANIPULATIVE_DESIGN",
                "EQUAL_PROMINENCE_BUTTONS",
                "REVOCATION_SHOWN"
            );
    }

    // ========================================================================
    // Property 8: Compliant Configuration Passes Full Audit
    // For any compliant configuration, full audit must pass
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Compliant configuration passes full audit")
    void compliantConfigurationPassesFullAudit(
            @ForAll("dataRequests") DataRequest request) {
        
        UserChoices choices = service.applyPrivacyPreservingDefaults(request);
        ConsentScreenContent screen = service.generateCompliantScreen(request, choices);
        
        AuditResult result = service.runAuditChecklist(screen, choices, screen.description());
        
        assertThat(result.allPassed())
            .as("Compliant configuration must pass full audit")
            .isTrue();
        
        assertThat(result.passedCount())
            .as("All checks must pass")
            .isEqualTo(result.totalCount());
    }

    // ========================================================================
    // Property 9: Rewritten Content Is Non-Manipulative
    // For any manipulative content, rewritten version must be clean
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Rewritten content is non-manipulative")
    void rewrittenContentIsNonManipulative(
            @ForAll("manipulativePhrases") String phrase) {
        
        String original = "Please " + phrase + " to share your data";
        String rewritten = service.rewriteNonManipulative(original);
        
        ManipulationValidation validation = service.validateNonManipulative(rewritten);
        
        assertThat(validation.isCompliant())
            .as("Rewritten content must pass manipulation check")
            .isTrue();
    }

    // ========================================================================
    // Property 10: Output Mode Defaults to Most Restrictive
    // For any defaults, output mode must be most restrictive
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Output mode defaults to most restrictive")
    void outputModeDefaultsToMostRestrictive() {
        PrivacyPreservingDefaults defaults = service.getPrivacyPreservingDefaults();
        
        assertThat(defaults.outputModeDefault())
            .as("Output mode must default to AGGREGATE_ONLY (most restrictive)")
            .isEqualTo(OutputMode.AGGREGATE_ONLY);
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<DataRequest> dataRequests() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(20),
                Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(50),
                Arbitraries.of(OutputMode.values()),
                Arbitraries.integers().between(1, 5)
        ).as((id, name, mode, labelCount) -> {
            Set<String> requiredLabels = new HashSet<>();
            for (int i = 0; i < labelCount; i++) {
                requiredLabels.add("domain:label_" + i);
            }
            
            return DataRequest.builder()
                    .id(id)
                    .requesterId("requester-" + id)
                    .requesterName(name)
                    .type(DataRequest.RequestType.BROADCAST)
                    .requiredLabels(requiredLabels)
                    .optionalLabels(Set.of("optional:label_1"))
                    .outputMode(mode)
                    .compensation(new DataRequest.CompensationOffer(10.0, "USD", "escrow-1"))
                    .timeWindow(new DataRequest.TimeWindow(
                            Instant.now().minusSeconds(86400), 
                            Instant.now()))
                    .createdAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(604800)) // 7 days
                    .policyStamp("policy-stamp")
                    .signature("signature")
                    .build();
        });
    }

    @Provide
    Arbitrary<String> manipulativePhrases() {
        return Arbitraries.of(
                "hurry",
                "limited time",
                "act now",
                "don't miss",
                "last chance",
                "everyone is doing it",
                "most people choose",
                "you'll regret",
                "you'll lose",
                "missing out"
        );
    }

    @Provide
    Arbitrary<String> cleanPhrases() {
        return Arbitraries.of(
                "Please review your data sharing options",
                "You can choose which data to share",
                "Your privacy is important to us",
                "Take your time to review",
                "You can change your mind later",
                "This is optional",
                "Consider your preferences",
                "Review the details below"
        );
    }
}
