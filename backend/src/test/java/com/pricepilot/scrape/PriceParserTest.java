package com.pricepilot.scrape;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class PriceParserTest {
    @ParameterizedTest
    @CsvSource(delimiter='|', value={"₹1,609.50|1609.50", "INR 1,29,999|129999.00", "Rs. 99|99.00", "99 INR|99.00", "1,609.50|1609.50"})
    void acceptsExplicitAmounts(String raw, String expected) {
        assertThat(PriceParser.parseAmount(raw)).get().isEqualTo(new java.math.BigDecimal(expected));
    }
    @ParameterizedTest
    @ValueSource(strings={"0", "-1609", "NaN", "100000001", "₹1609 EMI", "₹99/month", "20% off", "4.3 stars", "₹1,609 ₹3,999", "$1609", "1,60", "1609.999", "1e3", ""})
    void rejectsAmbiguousOrInvalidAmounts(String raw) {
        assertThat(PriceParser.parseAmount(raw)).isEmpty();
    }
}
