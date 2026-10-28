package com.pricepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Every tunable in one place, bound from {@code application.yml} (and therefore from
 * environment variables). Nothing in the codebase reads {@code @Value} directly, so there is a
 * single answer to "where does this setting come from?".
 */
@ConfigurationProperties(prefix = "pricepilot")
public class AppProperties {

    private Auth auth = new Auth();
    private Cors cors = new Cors();
    private String publicUrl = "http://localhost:5173";
    private Ai ai = new Ai();
    private Discovery discovery = new Discovery();
    private Scrape scrape = new Scrape();
    private Scheduler scheduler = new Scheduler();
    private Alerts alerts = new Alerts();
    private Limits limits = new Limits();

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    public Ai getAi() { return ai; }
    public void setAi(Ai ai) { this.ai = ai; }
    public Discovery getDiscovery() { return discovery; }
    public void setDiscovery(Discovery discovery) { this.discovery = discovery; }
    public Scrape getScrape() { return scrape; }
    public void setScrape(Scrape scrape) { this.scrape = scrape; }
    public Scheduler getScheduler() { return scheduler; }
    public void setScheduler(Scheduler scheduler) { this.scheduler = scheduler; }
    public Alerts getAlerts() { return alerts; }
    public void setAlerts(Alerts alerts) { this.alerts = alerts; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }

    // ------------------------------------------------------------------ auth

    public static class Auth {
        private String jwtSecret = "";
        private int jwtTtlHours = 72;
        /** When non-blank, registration requires this code. Protects your Claude budget. */
        private String signupInviteCode = "";

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public int getJwtTtlHours() { return jwtTtlHours; }
        public void setJwtTtlHours(int jwtTtlHours) { this.jwtTtlHours = jwtTtlHours; }
        public String getSignupInviteCode() { return signupInviteCode; }
        public void setSignupInviteCode(String signupInviteCode) { this.signupInviteCode = signupInviteCode; }
    }

    // ------------------------------------------------------------------ cors

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    // -------------------------------------------------------------------- ai

    public static class Ai {
        private String apiKey = "";
        private String model = "claude-haiku-4-5";
        private String baseUrl = "https://api.anthropic.com";
        private String apiVersion = "2023-06-01";
        private int maxTokens = 1200;
        private int timeoutMs = 45000;
        private BigDecimal monthlyBudgetUsd = new BigDecimal("5.00");
        /** Skip re-analysis unless the price moved at least this fraction. */
        private double reanalysisPriceDelta = 0.02;
        private int reanalysisMinAgeHours = 12;

        /** Analysis is simply disabled, not broken, when no key is present. */
        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public BigDecimal getMonthlyBudgetUsd() { return monthlyBudgetUsd; }
        public void setMonthlyBudgetUsd(BigDecimal monthlyBudgetUsd) { this.monthlyBudgetUsd = monthlyBudgetUsd; }
        public double getReanalysisPriceDelta() { return reanalysisPriceDelta; }
        public void setReanalysisPriceDelta(double reanalysisPriceDelta) { this.reanalysisPriceDelta = reanalysisPriceDelta; }
        public int getReanalysisMinAgeHours() { return reanalysisMinAgeHours; }
        public void setReanalysisMinAgeHours(int reanalysisMinAgeHours) { this.reanalysisMinAgeHours = reanalysisMinAgeHours; }
    }

    // ------------------------------------------------------------- discovery

    public static class Discovery {
        private String apiKey = "";
        private String baseUrl = "https://api.tavily.com";
        private int timeoutMs = 20000;
        private int monthlyCallBudget = 500;
        private int maxResults = 8;

        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMonthlyCallBudget() { return monthlyCallBudget; }
        public void setMonthlyCallBudget(int monthlyCallBudget) { this.monthlyCallBudget = monthlyCallBudget; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    }

    // ---------------------------------------------------------------- scrape

    public static class Scrape {
        private int timeoutMs = 20000;
        private int maxConcurrency = 2;
        private long minDelayMs = 4000;
        private int maxRetries = 2;
        private int maxResponseBytes = 4 * 1024 * 1024;
        private int freshnessWindowSeconds = 900;
        /** none | scraperapi | scrapingbee */
        private String provider = "none";
        private String providerApiKey = "";
        private int providerMonthlyCallBudget = 800;
        private String providerCountry = "in";
        private String rulesPath = "classpath:scraping/domain-rules.yml";

        public boolean isProviderConfigured() {
            return provider != null
                    && !provider.isBlank()
                    && !"none".equalsIgnoreCase(provider)
                    && providerApiKey != null
                    && !providerApiKey.isBlank();
        }

        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
        public long getMinDelayMs() { return minDelayMs; }
        public void setMinDelayMs(long minDelayMs) { this.minDelayMs = minDelayMs; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
        public int getFreshnessWindowSeconds() { return freshnessWindowSeconds; }
        public void setFreshnessWindowSeconds(int freshnessWindowSeconds) { this.freshnessWindowSeconds = freshnessWindowSeconds; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getProviderApiKey() { return providerApiKey; }
        public void setProviderApiKey(String providerApiKey) { this.providerApiKey = providerApiKey; }
        public int getProviderMonthlyCallBudget() { return providerMonthlyCallBudget; }
        public void setProviderMonthlyCallBudget(int providerMonthlyCallBudget) { this.providerMonthlyCallBudget = providerMonthlyCallBudget; }
        public String getProviderCountry() { return providerCountry; }
        public void setProviderCountry(String providerCountry) { this.providerCountry = providerCountry; }
        public String getRulesPath() { return rulesPath; }
        public void setRulesPath(String rulesPath) { this.rulesPath = rulesPath; }
    }

    // ------------------------------------------------------------- scheduler

    public static class Scheduler {
        private boolean enabled = true;
        private int tickSeconds = 120;
        private int minIntervalMinutes = 60;
        private int maxIntervalMinutes = 1440;
        private int initialIntervalMinutes = 180;
        private int alertedIntervalMinutes = 60;
        private double backoffMultiplier = 1.6;
        private double speedupDivisor = 2.5;
        private int batchSize = 12;
        private int maxConsecutiveFailures = 8;
        /**
         * How long scrape telemetry is kept. The table grows by one row per attempt, so on a small
         * EC2 disk it needs a ceiling; 45 days is comfortably more than the 30-day windows the
         * metrics page reports over.
         */
        private int attemptRetentionDays = 45;
        /**
         * Delay before the first scrape tick, giving Flyway, the domain rules and the connection
         * pool time to come up. Also read directly by the {@code @Scheduled} annotation, which is
         * why the default appears in both places.
         */
        private int startupDelaySeconds = 45;
        /** When the nightly telemetry prune runs. Local time of the JVM, which deploys set to UTC. */
        private String cleanupCron = "0 40 3 * * *";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTickSeconds() { return tickSeconds; }
        public void setTickSeconds(int tickSeconds) { this.tickSeconds = tickSeconds; }
        public int getMinIntervalMinutes() { return minIntervalMinutes; }
        public void setMinIntervalMinutes(int minIntervalMinutes) { this.minIntervalMinutes = minIntervalMinutes; }
        public int getMaxIntervalMinutes() { return maxIntervalMinutes; }
        public void setMaxIntervalMinutes(int maxIntervalMinutes) { this.maxIntervalMinutes = maxIntervalMinutes; }
        public int getInitialIntervalMinutes() { return initialIntervalMinutes; }
        public void setInitialIntervalMinutes(int initialIntervalMinutes) { this.initialIntervalMinutes = initialIntervalMinutes; }
        public int getAlertedIntervalMinutes() { return alertedIntervalMinutes; }
        public void setAlertedIntervalMinutes(int alertedIntervalMinutes) { this.alertedIntervalMinutes = alertedIntervalMinutes; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
        public double getSpeedupDivisor() { return speedupDivisor; }
        public void setSpeedupDivisor(double speedupDivisor) { this.speedupDivisor = speedupDivisor; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
        public void setMaxConsecutiveFailures(int maxConsecutiveFailures) { this.maxConsecutiveFailures = maxConsecutiveFailures; }
        public int getAttemptRetentionDays() { return attemptRetentionDays; }
        public void setAttemptRetentionDays(int attemptRetentionDays) { this.attemptRetentionDays = attemptRetentionDays; }
        public int getStartupDelaySeconds() { return startupDelaySeconds; }
        public void setStartupDelaySeconds(int startupDelaySeconds) { this.startupDelaySeconds = startupDelaySeconds; }
        public String getCleanupCron() { return cleanupCron; }
        public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
    }

    // ---------------------------------------------------------------- alerts

    public static class Alerts {
        private boolean enabled = false;
        private String from = "PricePilot <noreply@example.com>";
        private int cooldownHours = 12;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public int getCooldownHours() { return cooldownHours; }
        public void setCooldownHours(int cooldownHours) { this.cooldownHours = cooldownHours; }
    }

    // ---------------------------------------------------------------- limits

    public static class Limits {
        private int maxTrackedProductsPerUser = 40;
        private int maxListingsPerProduct = 6;
        private int manualRefreshPerHour = 30;

        /**
         * How far back the charts and the "changed this much" figures look.
         *
         * <p>Thirty days is the window a shopper reasons in — it covers a monthly sale cycle without
         * letting a six-month-old launch price dominate the average. It is deliberately shorter than
         * {@code scheduler.attemptRetentionDays}, so the data behind the window is never the limiting
         * factor; raising this number widens the view over history that is already being kept.
         */
        private int historyWindowDays = 30;

        public int getMaxTrackedProductsPerUser() { return maxTrackedProductsPerUser; }
        public void setMaxTrackedProductsPerUser(int maxTrackedProductsPerUser) { this.maxTrackedProductsPerUser = maxTrackedProductsPerUser; }
        public int getMaxListingsPerProduct() { return maxListingsPerProduct; }
        public void setMaxListingsPerProduct(int maxListingsPerProduct) { this.maxListingsPerProduct = maxListingsPerProduct; }
        public int getManualRefreshPerHour() { return manualRefreshPerHour; }
        public void setManualRefreshPerHour(int manualRefreshPerHour) { this.manualRefreshPerHour = manualRefreshPerHour; }
        public int getHistoryWindowDays() { return historyWindowDays; }
        public void setHistoryWindowDays(int historyWindowDays) { this.historyWindowDays = historyWindowDays; }
    }
}
