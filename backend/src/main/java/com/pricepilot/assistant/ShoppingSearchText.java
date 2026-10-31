package com.pricepilot.assistant;

import com.pricepilot.domain.Product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small lexical hints for discovery; a title match never certifies product compatibility. */
final class ShoppingSearchText {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-.+/][\\p{L}\\p{N}]+)*");
    private static final Set<String> FILLER = Set.of(
            "a", "an", "the", "i", "we", "me", "my", "our", "you", "your", "it", "its", "this", "that",
            "is", "are", "be", "to", "of", "for", "and", "or", "with", "please", "need", "needs", "want",
            "wants", "looking", "find", "show", "recommend", "compare", "comparison", "must", "have", "has",
            "required", "requirement", "requirements", "should", "would", "like", "different", "other",
            "alternative", "alternatives", "brand", "brands", "company", "companies", "product", "products",
            "buy", "india");
    static final String CONTRADICTION = "Does not meet a stated requirement: ";

    private ShoppingSearchText() { }

    static String alternativeQuery(Product product, ShoppingAdvice.Request request, String plannedQuery) {
        String requirements = compact(request.requirements(), 140, 16, "");
        String topic = plannedQuery;
        if (topic == null || topic.isBlank()) {
            topic = product.getCategory();
            // Detailed requirements can stand alone. A sparse request needs a little reference
            // context, but copying a branded title into every alternative search narrows it again.
            if (topic == null || topic.isBlank()) topic = terms(requirements).size() < 4 ? product.getTitle() : "";
        }
        String context = compact(topic, 72, 8, product.getBrand());
        String combined = compact(requirements + " " + context, 220, 24, product.getBrand());
        return combined + " alternatives India";
    }

    static String originalQuery(Product product) {
        return compact(product.getTitle(), 200, 24, "") + " buy India";
    }

    static int relevance(String title, ShoppingAdvice.Request request, String referenceBrand) {
        String normalizedTitle = normalize(title);
        int score = 0;
        for (String term : terms(request.requirements())) {
            if (containsTerm(normalizedTitle, normalize(term))) score += term.matches(".*\\d.*") ? 3 : 2;
        }
        if (!contradictions(title, request.requirements()).isEmpty()) score -= 100;
        if (referenceBrand != null && !referenceBrand.isBlank()
                && containsTerm(normalizedTitle, normalize(referenceBrand))) score -= 2;
        return score;
    }

    static List<String> contradictions(String title, String requirements) {
        if (title == null || requirements == null) {
            return List.of();
        }
        List<String> cautions = new ArrayList<>();
        for (String term : terms(requirements)) {
            if (term.length() < 3) continue;
            String quoted = Pattern.quote(term);
            // Negation applies to the feature, not an unrelated phrase such as
            // "not necessarily the same company" elsewhere in the request.
            if (Pattern.compile("(?i)\\b(?:no|not|without|avoid|exclude)\\s+" + quoted + "\\b")
                    .matcher(requirements).find()) continue;
            // Require an explicit, bounded absence phrase. "Without Ethernet cable" does not
            // establish that an Ethernet port is absent, and missing title text proves nothing.
            Pattern absence = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(?:(?:no|without)\\s+" + quoted
                    + "(?=$|[,.();]|\\s+(?:and|or|but)\\b)|" + quoted
                    + "\\s+(?:not included|not supported|unavailable)(?=$|[,.();]|\\s+(?:and|or|but)\\b))");
            Matcher match = absence.matcher(title);
            if (match.find()) cautions.add(CONTRADICTION + "the title says \"" + match.group().trim() + "\".");
        }
        Double requiredEthernet = ethernetMbps(requirements);
        Double offeredEthernet = ethernetMbps(title);
        if (requiredEthernet != null && offeredEthernet != null && offeredEthernet < requiredEthernet) {
            cautions.add(CONTRADICTION + "the title labels Ethernet at " + offeredEthernet.intValue()
                    + " Mbps, below the requested " + requiredEthernet.intValue() + " Mbps.");
        }
        return List.copyOf(cautions);
    }

    /** Compare only a speed directly labelled as Ethernet/RJ45, never a USB/HDMI speed. */
    private static Double ethernetMbps(String text) {
        String feature = "(?:ethernet|rj[- ]?45)";
        if (Pattern.compile("(?i)\\b(?:no|without|avoid|exclude)\\s+" + feature + "\\b").matcher(text).find()) return null;
        if (Pattern.compile("(?i)\\b(?:gigabit\\s+" + feature + "|" + feature + "\\s+gigabit)\\b")
                .matcher(text).find()) return 1000.0;
        String speed = "(?:(?:10|100)/)*(\\d+(?:\\.\\d+)?)\\s*(g|m)bps";
        for (String pattern : List.of("\\b" + feature + "(?:\\s+(?:port|at|up to|with|supports?))?\\s*[:(-]?\\s*" + speed + "\\b",
                "\\b" + speed + "\\s+(?:gigabit\\s+)?" + feature + "\\b")) {
            Matcher match = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
            if (match.find()) {
                double value = Double.parseDouble(match.group(1)) * (match.group(2).equalsIgnoreCase("g") ? 1000 : 1);
                if (Double.isFinite(value)) return value;
            }
        }
        return null;
    }

    static boolean contradicted(ShoppingAdvice.Candidate candidate) {
        return candidate.cautions().stream().anyMatch(caution -> caution.startsWith(CONTRADICTION));
    }

    private static List<String> terms(String text) {
        Map<String, String> terms = new LinkedHashMap<>();
        Matcher matcher = WORD.matcher(text == null ? "" : text.replaceAll("(?i)https?://\\S+|www\\.\\S+|\\S+@\\S+", " "));
        while (matcher.find()) {
            String term = matcher.group();
            String lower = term.toLowerCase(Locale.ROOT);
            if (!FILLER.contains(lower)) terms.putIfAbsent(lower, term);
        }
        return List.copyOf(terms.values());
    }

    private static String compact(String text, int maxCharacters, int maxTerms, String excludedBrand) {
        String cleaned = text == null ? "" : text;
        if (excludedBrand != null && !excludedBrand.isBlank()) {
            cleaned = cleaned.replaceAll("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(excludedBrand.trim())
                    + "(?![\\p{L}\\p{N}])", " ");
        }
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String term : terms(cleaned)) {
            if (count == maxTerms || result.length() + term.length() + (result.isEmpty() ? 0 : 1) > maxCharacters) break;
            if (!result.isEmpty()) result.append(' ');
            result.append(term);
            count++;
        }
        return result.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean containsTerm(String text, String term) {
        return !term.isBlank() && (" " + text + " ").contains(" " + term + " ");
    }
}
