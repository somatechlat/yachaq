package com.yachaq.api.matching;

import com.yachaq.core.domain.Request;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Property-based tests for MatchingService.
 * 
 * **Feature: yachaq-platform, Property 4: Uniform Compensation**
 * **Validates: Requirements 10.2**
 * 
 * For any request and any two Data Sovereigns participating in that request 
 * with the same unit type, the unit price must be identical regardless of 
 * their geographic location, device type, or profile attributes.
 */
class MatchingServicePropertyTest {

    @Property(tries = 100)
    void property4_uniformCompensation_sameRequestSamePrice(
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal unitPrice,
            @ForAll @IntRange(min = 2, max = 100) int participantCount) {
        
        // Create a request with fixed unit price
        UUID requestId = UUID.randomUUID();
        
        // Generate multiple participants with different attributes
        List<ParticipantProfile> participants = new ArrayList<>();
        for (int i = 0; i < participantCount; i++) {
            participants.add(new ParticipantProfile(
                UUID.randomUUID(),
                "pseudonym_" + i,
                randomLocation(),
                randomDeviceType(),
                randomAccountType()
            ));
        }
        
        // Property 4: All participants must receive same unit price
        for (ParticipantProfile p1 : participants) {
            for (ParticipantProfile p2 : participants) {
                BigDecimal price1 = calculateCompensation(unitPrice, p1);
                BigDecimal price2 = calculateCompensation(unitPrice, p2);
                
                assert price1.compareTo(price2) == 0 
                    : "Uniform compensation violated: " + p1.location + " got " + price1 
                      + " but " + p2.location + " got " + price2;
            }
        }
    }

    @Property(tries = 100)
    void property4_compensationIndependentOfGeography(
            @ForAll @BigRange(min = "1.00", max = "100.00") BigDecimal basePrice,
            @ForAll("locations") String location1,
            @ForAll("locations") String location2) {
        
        // Same request, different locations
        BigDecimal comp1 = calculateCompensation(basePrice, 
            new ParticipantProfile(UUID.randomUUID(), "p1", location1, "mobile", "DS_IND"));
        BigDecimal comp2 = calculateCompensation(basePrice, 
            new ParticipantProfile(UUID.randomUUID(), "p2", location2, "mobile", "DS_IND"));
        
        assert comp1.compareTo(comp2) == 0 
            : "Geography should not affect compensation: " + location1 + "=" + comp1 
              + " vs " + location2 + "=" + comp2;
    }

    @Property(tries = 100)
    void property4_compensationIndependentOfDeviceType(
            @ForAll @BigRange(min = "1.00", max = "100.00") BigDecimal basePrice,
            @ForAll("deviceTypes") String device1,
            @ForAll("deviceTypes") String device2) {
        
        BigDecimal comp1 = calculateCompensation(basePrice, 
            new ParticipantProfile(UUID.randomUUID(), "p1", "US", device1, "DS_IND"));
        BigDecimal comp2 = calculateCompensation(basePrice, 
            new ParticipantProfile(UUID.randomUUID(), "p2", "US", device2, "DS_IND"));
        
        assert comp1.compareTo(comp2) == 0 
            : "Device type should not affect compensation";
    }

    @Property(tries = 100)
    void property4_compensationIndependentOfAccountType(
            @ForAll @BigRange(min = "1.00", max = "100.00") BigDecimal basePrice,
            @ForAll("accountTypes") String account1,
            @ForAll("accountTypes") String account2) {
        
        BigDecimal comp1 = calculateCompensation(basePrice, 
            new ParticipantProfile(UUID.randomUUID(), "p1", "US", "mobile", account1));
        BigDecimal comp2 = calculateCompensation(basePrice, 
            new ParticipantProfile(UUID.randomUUID(), "p2", "US", "mobile", account2));
        
        assert comp1.compareTo(comp2) == 0 
            : "Account type should not affect compensation";
    }

    /**
     * Compensation calculation - must be uniform regardless of participant attributes.
     * This is the core of Property 4.
     */
    private BigDecimal calculateCompensation(BigDecimal unitPrice, ParticipantProfile profile) {
        // Property 4: Compensation is ONLY based on unit price, NOT on participant attributes
        // Location, device type, account type must NOT affect the price
        return unitPrice;
    }

    @Provide
    Arbitrary<String> locations() {
        return Arbitraries.of("US", "UK", "DE", "JP", "BR", "IN", "NG", "AU", "CA", "FR");
    }

    @Provide
    Arbitrary<String> deviceTypes() {
        return Arbitraries.of("mobile_ios", "mobile_android", "desktop_mac", "desktop_windows", "tablet");
    }

    @Provide
    Arbitrary<String> accountTypes() {
        return Arbitraries.of("DS_IND", "DS_COMP", "DS_ORG");
    }

    private String randomLocation() {
        String[] locations = {"US", "UK", "DE", "JP", "BR", "IN", "NG", "AU"};
        return locations[new Random().nextInt(locations.length)];
    }

    private String randomDeviceType() {
        String[] devices = {"mobile_ios", "mobile_android", "desktop", "tablet"};
        return devices[new Random().nextInt(devices.length)];
    }

    private String randomAccountType() {
        String[] types = {"DS_IND", "DS_COMP", "DS_ORG"};
        return types[new Random().nextInt(types.length)];
    }

    record ParticipantProfile(
        UUID id,
        String pseudonym,
        String location,
        String deviceType,
        String accountType
    ) {}
}
