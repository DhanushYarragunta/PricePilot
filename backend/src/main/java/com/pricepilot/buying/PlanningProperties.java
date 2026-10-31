package com.pricepilot.buying;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Separate knobs for conservative planning; these never change scraping limits or budgets. */
@Component
@ConfigurationProperties(prefix = "pricepilot.planning")
public class PlanningProperties {
    private int minimumForecastObservations = 12;
    private int minimumForecastDays = 7;
    private int maximumForecastDays = 30;
    private int staleAfterHours = 24;
    private int gapAfterHours = 48;
    private int reassessAfterHours = 24;
    private int deadlineRiskDays = 3;
    private double minimumExpectedSavingsPercent = 3.0;
    private double maximumBacktestErrorPercent = 15.0;
    private double minimumTrainingCoveragePercent = 50.0;
    private int schedulerBatchSize = 20;
    private int aiTimeoutSeconds = 15;
    private String zoneId = "Asia/Kolkata";

    public int getMinimumForecastObservations() { return minimumForecastObservations; }
    public void setMinimumForecastObservations(int value) { minimumForecastObservations = Math.max(6, value); }
    public int getMinimumForecastDays() { return minimumForecastDays; }
    public void setMinimumForecastDays(int value) { minimumForecastDays = Math.max(6, value); }
    public int getMaximumForecastDays() { return maximumForecastDays; }
    public void setMaximumForecastDays(int value) { maximumForecastDays = Math.max(1, Math.min(90, value)); }
    public int getStaleAfterHours() { return staleAfterHours; }
    public void setStaleAfterHours(int value) { staleAfterHours = Math.max(1, value); }
    public int getGapAfterHours() { return gapAfterHours; }
    public void setGapAfterHours(int value) { gapAfterHours = Math.max(1, value); }
    public int getReassessAfterHours() { return reassessAfterHours; }
    public void setReassessAfterHours(int value) { reassessAfterHours = Math.max(1, value); }
    public int getDeadlineRiskDays() { return deadlineRiskDays; }
    public void setDeadlineRiskDays(int value) { deadlineRiskDays = Math.max(0, Math.min(30, value)); }
    public double getMinimumExpectedSavingsPercent() { return minimumExpectedSavingsPercent; }
    public void setMinimumExpectedSavingsPercent(double value) { minimumExpectedSavingsPercent = Math.max(0, Math.min(100, value)); }
    public double getMaximumBacktestErrorPercent() { return maximumBacktestErrorPercent; }
    public void setMaximumBacktestErrorPercent(double value) { maximumBacktestErrorPercent = Math.max(0.1, Math.min(100, value)); }
    public double getMinimumTrainingCoveragePercent() { return minimumTrainingCoveragePercent; }
    public void setMinimumTrainingCoveragePercent(double value) { minimumTrainingCoveragePercent = Math.max(1, Math.min(100, value)); }
    public int getSchedulerBatchSize() { return schedulerBatchSize; }
    public void setSchedulerBatchSize(int value) { schedulerBatchSize = Math.max(1, Math.min(100, value)); }
    public int getAiTimeoutSeconds() { return aiTimeoutSeconds; }
    public void setAiTimeoutSeconds(int value) { aiTimeoutSeconds = Math.max(1, Math.min(30, value)); }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String value) { java.time.ZoneId.of(value); zoneId = value; }
}
