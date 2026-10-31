package com.pricepilot.buying;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.scrape.PriceParser;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.pricepilot.buying.BuyingModels.*;

/** Pure analysis of recorded selling-price observations, without interpolating gaps or pooling variants. */
@Component
public class BuyingHistory {
    private final PlanningProperties properties;
    public BuyingHistory(PlanningProperties properties) { this.properties = properties; }

    public History analyze(List<Listing> listings, List<PricePoint> points, List<PricePoint> latest,
                           Long selectedListingId, Instant now) {
        Instant start = now.minus(Duration.ofDays(365));
        Map<String, List<PricePoint>> groups = new LinkedHashMap<>();
        Map<String, PricePoint> newest = new HashMap<>();
        Map<Long, Listing> identities = new HashMap<>();
        listings.forEach(listing -> identities.put(listing.getId(), listing));
        for (PricePoint point : points) {
            if (!valid(point, now) || point.getCapturedAt().isBefore(start) || !identities.containsKey(point.getListing().getId())) continue;
            groups.computeIfAbsent(key(point), ignored -> new ArrayList<>()).add(point);
        }
        for (PricePoint point : latest) {
            if (!valid(point, now) || !identities.containsKey(point.getListing().getId())) continue;
            groups.computeIfAbsent(key(point), ignored -> new ArrayList<>());
            newest.merge(key(point), point, (a, b) -> a.getCapturedAt().isAfter(b.getCapturedAt()) ? a : b);
        }
        for (Listing listing : listings) {
            if (groups.keySet().stream().noneMatch(key -> key.startsWith(listing.getId() + ":"))) {
                groups.put(listing.getId() + ":INR", new ArrayList<>());
            }
        }
        List<Series> series = new ArrayList<>();
        for (Map.Entry<String, List<PricePoint>> group : groups.entrySet()) {
            String[] identity = group.getKey().split(":", 2);
            Listing listing = identities.get(Long.valueOf(identity[0]));
            List<PricePoint> ordered = group.getValue().stream().sorted(Comparator.comparing(PricePoint::getCapturedAt)
                    .thenComparing(point -> point.getId() == null ? 0L : point.getId())).toList();
            PricePoint current = newest.get(group.getKey());
            if (!ordered.isEmpty() && (current == null || ordered.get(ordered.size() - 1).getCapturedAt().isAfter(current.getCapturedAt()))) {
                current = ordered.get(ordered.size() - 1);
            }
            List<Observation> observations = ordered.stream().map(point -> new Observation(point.getPrice(),
                    point.getListPrice(), point.isInStock(), point.getCapturedAt(), point.getSourceStrategy())).toList();
            List<Gap> gaps = gaps(observations, start, now);
            series.add(new Series(listing.getId(), listing.getRetailer(), listing.getTitle(), listing.getUrl(),
                    identity[1], listing.getStatus().name(), stats(observations, current, start, now, gaps), observations, gaps));
        }
        series.sort(Comparator.comparing(Series::listingId).thenComparing(Series::currency));
        return new History(start, now, 365, "STORED_OBSERVATIONS", selectedListingId, List.copyOf(series), List.of(
                "Only stored observations are shown. Missing periods have no invented or interpolated prices; no external historical series is connected.",
                "Each listing and currency is analyzed separately. Prices from another variant are not pooled into this history.",
                "Statistics use in-stock selling prices, not MRP, EMI, exchange offers or time-weighted estimates.",
                "Below-median spans describe adjacent observations, not proven sale durations. No confirmed future sale or reliable seasonal pattern is inferred."));
    }

    public Series selected(History history) {
        return history.series().stream().filter(series -> Objects.equals(series.listingId(), history.selectedListingId())
                && "INR".equals(series.currency())).findFirst().orElse(null);
    }

    private Stats stats(List<Observation> observations, PricePoint current, Instant start, Instant now, List<Gap> gaps) {
        List<BigDecimal> values = observations.stream().filter(Observation::inStock).map(Observation::price).sorted().toList();
        BigDecimal min = values.isEmpty() ? null : values.get(0);
        BigDecimal max = values.isEmpty() ? null : values.get(values.size() - 1);
        BigDecimal mean = values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
        BigDecimal median = percentile(values, .5);
        int changes = 0, drops = 0, below = 0;
        long lowSeconds = 0;
        Observation previous = null;
        for (Observation point : observations) {
            if (!point.inStock()) { previous = null; continue; }
            boolean isLow = median != null && point.price().compareTo(median) < 0;
            if (isLow) below++;
            if (previous != null) {
                int comparison = point.price().compareTo(previous.price());
                if (comparison != 0) changes++;
                if (comparison < 0) drops++;
                long elapsed = Duration.between(previous.capturedAt(), point.capturedAt()).getSeconds();
                if (isLow && previous.price().compareTo(median) < 0 && elapsed <= properties.getGapAfterHours() * 3600L) {
                    lowSeconds += Math.max(0, elapsed);
                }
            }
            previous = point;
        }
        int days = (int) Math.min(365, observations.stream().map(point -> point.capturedAt()
                .atZone(java.time.ZoneId.of(properties.getZoneId())).toLocalDate()).distinct().count());
        Instant first = observations.isEmpty() ? null : observations.get(0).capturedAt();
        Instant last = current == null ? null : current.getCapturedAt();
        boolean stale = last == null || !last.isAfter(now.minus(Duration.ofHours(properties.getStaleAfterHours())));
        BigDecimal currentPrice = current == null ? null : current.getPrice();
        boolean enoughPercentiles = values.size() >= 8;
        long largest = 0;
        Instant cursor = start;
        for (Observation point : observations) {
            largest = Math.max(largest, Duration.between(cursor, point.capturedAt()).toHours());
            cursor = point.capturedAt();
        }
        largest = Math.max(largest, Duration.between(cursor, now).toHours());
        return new Stats(observations.size(), values.size(), days, percent(BigDecimal.valueOf(days), BigDecimal.valueOf(365)),
                days == 365 && first != null && !first.isAfter(now.minus(Duration.ofDays(364))) && gaps.isEmpty() && !stale, first, last, currentPrice,
                current == null ? null : current.isInStock(), stale, min, max, mean, median,
                enoughPercentiles ? percentile(values, .1) : null, enoughPercentiles ? percentile(values, .25) : null,
                enoughPercentiles ? percentile(values, .75) : null, enoughPercentiles ? percentile(values, .9) : null,
                relative(currentPrice, min), relative(currentPrice, median), relative(currentPrice, mean), changes, drops,
                below, values.isEmpty() ? null : percent(BigDecimal.valueOf(below), BigDecimal.valueOf(values.size())),
                BigDecimal.valueOf(lowSeconds).divide(BigDecimal.valueOf(86400), 2, RoundingMode.HALF_UP), largest);
    }

    private List<Gap> gaps(List<Observation> observations, Instant start, Instant now) {
        List<Gap> gaps = new ArrayList<>();
        Instant previous = start;
        for (Observation observation : observations) {
            addGap(gaps, previous, observation.capturedAt(), previous.equals(start) ? "BEFORE_FIRST_OBSERVATION" : "BETWEEN_OBSERVATIONS");
            previous = observation.capturedAt();
        }
        addGap(gaps, previous, now, observations.isEmpty() ? "NO_OBSERVATIONS" : "AFTER_LAST_OBSERVATION");
        return List.copyOf(gaps);
    }

    private void addGap(List<Gap> gaps, Instant from, Instant to, String kind) {
        long hours = Duration.between(from, to).toHours();
        if (hours > properties.getGapAfterHours()) gaps.add(new Gap(from, to, hours, kind));
    }

    static BigDecimal percentile(List<BigDecimal> sorted, double fraction) {
        if (sorted.isEmpty()) return null;
        double position = (sorted.size() - 1) * fraction;
        int lower = (int) Math.floor(position), upper = (int) Math.ceil(position);
        return sorted.get(lower).add(sorted.get(upper).subtract(sorted.get(lower))
                .multiply(BigDecimal.valueOf(position - lower))).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal percent(BigDecimal value, BigDecimal reference) {
        return reference == null || reference.signum() == 0 ? null
                : value.multiply(BigDecimal.valueOf(100)).divide(reference, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal relative(BigDecimal value, BigDecimal reference) {
        return value == null || reference == null ? null : percent(value.subtract(reference), reference);
    }

    private static boolean valid(PricePoint point, Instant now) {
        return point != null && point.getListing() != null && point.getCapturedAt() != null
                && !point.getCapturedAt().isAfter(now) && PriceParser.isPlausible(point.getPrice())
                && point.getCurrency() != null && point.getCurrency().matches("[A-Z]{3}");
    }

    private static String key(PricePoint point) { return point.getListing().getId() + ":" + point.getCurrency(); }
}
