package com.pricepilot.assistant;

import com.pricepilot.domain.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShoppingSearchTextTest {
    @Test void longReferenceCannotCrowdRequirementsOutOfAlternativeQuery() {
        Product product = new Product();
        product.setBrand("Anker");
        product.setTitle("Anker USB-C Hub " + "HDMI charging laptop docking adapter ".repeat(20));
        var request = request("USB-C hub RJ45 1Gbps Ethernet different brands");

        String query = ShoppingSearchText.alternativeQuery(product, request, null);

        assertThat(query).startsWith("USB-C hub RJ45 1Gbps Ethernet");
        assertThat(query).doesNotContain("Anker");
        assertThat(query).endsWith("alternatives India");
        assertThat(query.length()).isLessThanOrEqualTo(240);
        assertThat(ShoppingSearchText.originalQuery(product)).startsWith("Anker USB-C Hub").endsWith("buy India");
    }

    @Test void sparseRequirementsKeepCompactCategoryContext() {
        Product product = new Product();
        product.setCategory("USB-C hubs");
        product.setTitle("Anker very long reference title");

        assertThat(ShoppingSearchText.alternativeQuery(product, request("RJ45 1Gbps"), null))
                .isEqualTo("RJ45 1Gbps USB-C hubs alternatives India");
    }

    @Test void planningCannotReplaceMustHaveTermsAndQueryDoesNotCopyContactDetails() {
        Product product = new Product();
        product.setCategory("Laptop docks");
        String query = ShoppingSearchText.alternativeQuery(product,
                request("RJ45 1Gbps https://example.test/private person@example.test"), "USB-C hub brands");

        assertThat(query).startsWith("RJ45 1Gbps USB-C hub");
        assertThat(query).doesNotContain("example", "private", "person", "https");
    }

    @Test void titlesMentioningRequiredFeaturesRankBeforeGenericReferenceAccessories() {
        var request = request("USB-C hub RJ45 1Gbps Ethernet");
        assertThat(ShoppingSearchText.relevance("Different brand USB-C hub RJ45 1Gbps Ethernet", request, "Anker"))
                .isGreaterThan(ShoppingSearchText.relevance("Anker USB-C hub HDMI charging", request, "Anker"));
    }

    @Test void onlyExplicitBoundedAbsenceOfAPositiveRequirementIsAContradiction() {
        assertThat(ShoppingSearchText.contradictions("USB-C hub without Ethernet, HDMI included", "Ethernet required"))
                .singleElement().asString().contains("without Ethernet");
        assertThat(ShoppingSearchText.contradictions("USB-C hub Ethernet not supported", "Ethernet required"))
                .hasSize(1);
        assertThat(ShoppingSearchText.contradictions("USB-C hub with HDMI", "Ethernet required")).isEmpty();
        assertThat(ShoppingSearchText.contradictions("USB-C hub without Ethernet cable", "Ethernet required")).isEmpty();
        assertThat(ShoppingSearchText.contradictions("USB-C hub without Ethernet", "without Ethernet")).isEmpty();
    }

    @Test void unlabeledSpeedsAreNotComparedAsIfTheyDescribeTheSameFeature() {
        assertThat(ShoppingSearchText.contradictions("USB 5Gbps hub with Ethernet", "RJ45 1Gbps Ethernet")).isEmpty();
    }

    @Test void anUnrelatedNegativeBrandPreferenceDoesNotDisableRequiredFeatureChecks() {
        assertThat(ShoppingSearchText.contradictions("USB-C hub without Ethernet, HDMI included",
                "Ethernet is required, not necessarily the same company")).hasSize(1);
    }

    @Test void slowerExplicitEthernetRatingIsNotSuitableForGigabitRequirement() {
        assertThat(ShoppingSearchText.contradictions("USB-C 5Gbps hub with RJ45 100Mbps",
                "USB-C hub with 1Gbps Ethernet")).singleElement().asString().contains("100 Mbps", "1000 Mbps");
        assertThat(ShoppingSearchText.contradictions("USB-C hub 10/100Mbps Ethernet",
                "Gigabit Ethernet")).hasSize(1);
        assertThat(ShoppingSearchText.contradictions("USB-C hub RJ45 10/100/1000Mbps",
                "Gigabit Ethernet")).isEmpty();
        assertThat(ShoppingSearchText.contradictions("USB-C hub RJ45 2.5Gbps",
                "1Gbps Ethernet")).isEmpty();
    }

    private static ShoppingAdvice.Request request(String requirements) {
        return new ShoppingAdvice.Request("Compare alternatives", new BigDecimal("3000"), requirements, List.of());
    }
}
