package com.yachaq.api.requester;

import com.yachaq.api.requester.RequesterPortalService.RequestTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for request templates.
 * Provides pre-defined templates for common use cases.
 */
@Repository
public class RequestTemplateRepository {

    private final Map<String, RequestTemplate> templates = new ConcurrentHashMap<>();

    public RequestTemplateRepository() {
        initializeDefaultTemplates();
    }

    public List<RequestTemplate> findAll() {
        return new ArrayList<>(templates.values());
    }

    public List<RequestTemplate> findByCategory(String category) {
        return templates.values().stream()
                .filter(t -> t.category().equalsIgnoreCase(category))
                .toList();
    }

    public Optional<RequestTemplate> findById(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    public void save(RequestTemplate template) {
        templates.put(template.id(), template);
    }

    private void initializeDefaultTemplates() {
        // Health & Fitness Research Template
        templates.put("health-research", new RequestTemplate(
                "health-research",
                "Health & Fitness Research",
                "Aggregate health and fitness data for research purposes",
                "research",
                Set.of("health:steps", "health:sleep"),
                Set.of("health:heart_rate", "health:workouts"),
                "AGGREGATE_ONLY",
                new RequesterPortalService.TimeWindow(
                        Instant.now().minus(90, ChronoUnit.DAYS),
                        Instant.now()
                ),
                BigDecimal.valueOf(5.00),
                72
        ));

        // Media Consumption Analysis Template
        templates.put("media-analysis", new RequestTemplate(
                "media-analysis",
                "Media Consumption Analysis",
                "Analyze media consumption patterns for market research",
                "market-research",
                Set.of("media:music", "media:video"),
                Set.of("media:podcasts", "media:reading"),
                "CLEAN_ROOM",
                new RequesterPortalService.TimeWindow(
                        Instant.now().minus(30, ChronoUnit.DAYS),
                        Instant.now()
                ),
                BigDecimal.valueOf(3.00),
                48
        ));

        // Location Patterns Template
        templates.put("location-patterns", new RequestTemplate(
                "location-patterns",
                "Location Patterns Study",
                "Study anonymized location patterns for urban planning",
                "research",
                Set.of("location:home", "location:work"),
                Set.of("location:travel"),
                "AGGREGATE_ONLY",
                new RequesterPortalService.TimeWindow(
                        Instant.now().minus(60, ChronoUnit.DAYS),
                        Instant.now()
                ),
                BigDecimal.valueOf(8.00),
                96
        ));

        // Social Interaction Study Template
        templates.put("social-study", new RequestTemplate(
                "social-study",
                "Social Interaction Study",
                "Analyze social interaction patterns for academic research",
                "academic",
                Set.of("social:interactions"),
                Set.of("social:connections"),
                "AGGREGATE_ONLY",
                new RequesterPortalService.TimeWindow(
                        Instant.now().minus(30, ChronoUnit.DAYS),
                        Instant.now()
                ),
                BigDecimal.valueOf(4.00),
                48
        ));

        // Financial Behavior Template
        templates.put("financial-behavior", new RequestTemplate(
                "financial-behavior",
                "Financial Behavior Analysis",
                "Analyze spending patterns for financial services research",
                "market-research",
                Set.of("finance:spending_category"),
                Set.of("finance:transactions"),
                "AGGREGATE_ONLY",
                new RequesterPortalService.TimeWindow(
                        Instant.now().minus(90, ChronoUnit.DAYS),
                        Instant.now()
                ),
                BigDecimal.valueOf(10.00),
                72
        ));
    }
}
