package com.pricepilot;

import com.pricepilot.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PricePilot — an AI price-tracking agent for Indian e-commerce.
 *
 * <p>The system has five moving parts:
 * <ol>
 *   <li><b>Scraping engine</b> ({@code com.pricepilot.scrape}) — fetches live prices from real
 *       retailer product pages using a chain of strategies, cheapest and most reliable first.</li>
 *   <li><b>Adaptive scheduler</b> ({@code com.pricepilot.schedule}) — decides which listings are
 *       worth re-checking and when, so we spend requests where prices actually move.</li>
 *   <li><b>Claude analysis</b> ({@code com.pricepilot.ai}) — turns a price history into a
 *       human-readable buy / wait verdict and detects inflated "MRP" discounts.</li>
 *   <li><b>Tavily discovery</b> ({@code com.pricepilot.discovery}) — finds which other retailers
 *       sell the same product so prices can be compared across sites. Discovery only: it never
 *       supplies a price.</li>
 *   <li><b>Alerts</b> ({@code com.pricepilot.alerts}) — tells watchers when a price crosses their
 *       target, hits a record low, or comes back in stock. Recorded in the app first, emailed
 *       second, so alerts survive a mail server being down or absent.</li>
 * </ol>
 *
 * <p>{@code com.pricepilot.metrics} sits across all five and recomputes the project's own
 * reliability and efficiency figures from the rows those parts write, so every number the dashboard
 * shows can be traced back to real work rather than asserted.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
@EnableCaching
@EnableAsync
public class PricePilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(PricePilotApplication.class, args);
    }
}
