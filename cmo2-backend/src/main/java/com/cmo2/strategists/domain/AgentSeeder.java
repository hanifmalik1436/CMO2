package com.cmo2.strategists.domain;

import com.cmo2.strategists.repository.StrategistAgentRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Idempotently seeds the strategist council. Single source of truth so the same
 * agents populate both Flyway-migrated MySQL (prod) and the H2 test schema (Flyway off).
 * Only inserts agents whose code is missing, so it is safe to run on every boot.
 *
 * <p>Per Doc 05's catalog this is 19 agents (5 + 2 + 8 + 4); the overview headlines "17".
 */
@Component
@Order(50)
public class AgentSeeder implements ApplicationRunner {

    private record Seed(String code, String name, int phase, int order, String deliverables, String prompt) {}

    private static final List<Seed> AGENTS = List.of(
            // Phase 1 — Insights & Brand
            new Seed("MARKET_ANALYSIS", "Market Analysis", 1, 1,
                    "[\"TAM/SAM/SOM snapshot\",\"Segment heatmap\",\"Jobs-to-be-done\",\"Audience hypotheses\",\"Top-10 keywords\"]",
                    "You are the Market Analysis strategist. Size the market (TAM/SAM/SOM), map segments, articulate jobs-to-be-done, and surface the top keywords."),
            new Seed("COMPETITOR", "Competitor", 1, 2,
                    "[\"Competitor matrix\",\"Positioning gaps\",\"SWOT\",\"Differentiation angles\"]",
                    "You are the Competitor strategist. Map competitors, find positioning gaps, and recommend differentiation."),
            new Seed("BRAND_ANALYSIS", "Brand Analysis", 1, 3,
                    "[\"Brand audit\",\"Voice & tone assessment\",\"Perception gaps\"]",
                    "You are the Brand Analysis strategist. Audit the brand, assess voice/tone, and identify perception gaps."),
            new Seed("BRAND", "Brand", 1, 4,
                    "[\"Brand positioning statement\",\"Messaging pillars\",\"Tagline options\"]",
                    "You are the Brand strategist. Define positioning, messaging pillars, and tagline options."),
            new Seed("PRICING", "Pricing", 1, 5,
                    "[\"Pricing model\",\"Tier recommendations\",\"Willingness-to-pay notes\"]",
                    "You are the Pricing strategist. Recommend a pricing model, tiers, and packaging."),
            // Phase 2 — Distribution
            new Seed("DISTRIBUTION", "Distribution", 2, 1,
                    "[\"Channel mix\",\"Channel-fit scoring\",\"Rollout sequence\"]",
                    "You are the Distribution strategist. Recommend the channel mix and rollout sequence."),
            new Seed("PARTNERSHIP_ECOSYSTEM", "Partnership & Ecosystem", 2, 2,
                    "[\"Partner shortlist\",\"Integration opportunities\",\"Co-marketing plays\"]",
                    "You are the Partnership & Ecosystem strategist. Identify partners, integrations, and co-marketing plays."),
            // Phase 3 — Big Media & Content
            new Seed("SEO", "SEO", 3, 1,
                    "[\"Keyword clusters\",\"Content gap analysis\",\"Technical SEO checklist\"]",
                    "You are the SEO strategist. Build keyword clusters, find content gaps, and list technical fixes."),
            new Seed("PAID_SEARCH", "Paid Search", 3, 2,
                    "[\"Campaign structure\",\"Keyword/match plan\",\"Budget allocation\"]",
                    "You are the Paid Search strategist. Design campaign structure, keywords, and budget split."),
            new Seed("GOOGLE_META_ADS", "Google & Meta Ads", 3, 3,
                    "[\"Audience targeting plan\",\"Creative angles\",\"Funnel mapping\"]",
                    "You are the Google & Meta Ads strategist. Plan audiences, creative angles, and funnel stages."),
            new Seed("INFLUENCER", "Influencer", 3, 4,
                    "[\"Creator shortlist\",\"Collaboration formats\",\"Brief template\"]",
                    "You are the Influencer strategist. Shortlist creators and define collaboration formats."),
            new Seed("SOCIAL_MEDIA", "Social Media", 3, 5,
                    "[\"Channel plan\",\"Content pillars\",\"Posting cadence\"]",
                    "You are the Social Media strategist. Recommend channels, pillars, and cadence."),
            new Seed("EMAIL_MARKETING", "Email Marketing", 3, 6,
                    "[\"Lifecycle map\",\"Sequence outlines\",\"Segmentation plan\"]",
                    "You are the Email Marketing strategist. Map lifecycle stages and outline sequences."),
            new Seed("CONTENT", "Content", 3, 7,
                    "[\"Content pillars\",\"Editorial calendar\",\"Repurposing plan\"]",
                    "You are the Content strategist. Define pillars, a calendar, and repurposing flow."),
            new Seed("VIDEO", "Video", 3, 8,
                    "[\"Video formats\",\"Hook library\",\"Distribution plan\"]",
                    "You are the Video strategist. Recommend formats, hooks, and distribution."),
            // Phase 4 — Sales & Operations
            new Seed("SALES", "Sales", 4, 1,
                    "[\"Sales playbook\",\"Objection handling\",\"Pipeline stages\"]",
                    "You are the Sales strategist. Build the playbook, objection handling, and pipeline stages."),
            new Seed("CRM", "CRM", 4, 2,
                    "[\"CRM workflow\",\"Lead scoring model\",\"Automation map\"]",
                    "You are the CRM strategist. Design workflows, lead scoring, and automations."),
            new Seed("CRO", "Conversion Rate Optimization", 4, 3,
                    "[\"Funnel audit\",\"Test backlog\",\"Quick wins\"]",
                    "You are the Conversion Rate Optimization strategist. Audit the funnel and prioritize tests."),
            new Seed("ANALYTICS_DATA", "Analytics & Data", 4, 4,
                    "[\"KPI tree\",\"Dashboard spec\",\"Tracking plan\"]",
                    "You are the Analytics & Data strategist. Define the KPI tree, dashboards, and tracking plan.")
    );

    private final StrategistAgentRepository repository;

    public AgentSeeder(StrategistAgentRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Seed s : AGENTS) {
            if (repository.findByCode(s.code()).isEmpty()) {
                StrategistAgent agent = new StrategistAgent();
                agent.setCode(s.code());
                agent.setName(s.name());
                agent.setPhase(s.phase());
                agent.setSortOrder(s.order());
                agent.setDeliverablesJson(s.deliverables());
                agent.setSystemPrompt(s.prompt());
                repository.save(agent);
            }
        }
    }
}
