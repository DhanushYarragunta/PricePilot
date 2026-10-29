package com.pricepilot.scrape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class ProductSnapshotTest {
    @Test void mrpContributedBeforePriceStillCannotContradictIt() {
        SnapshotBuilder b = new SnapshotBuilder("INR");
        b.offerMrp(new BigDecimal("999"), "css");
        b.offerPrice(new BigDecimal("1609"), "css");
        b.offerTitle("USB C Hub");
        assertThat(b.build().isVerified()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"0", "-1", "100000001", "1609.999"})
    void invalidAmountsCannotBeStored(String value) {
        assertThat(new ProductSnapshot(new BigDecimal(value), null,"INR","USB C Hub",null,true,"css").isVerified()).isFalse();
    }
    @Test void nonInrOrUntitledSnapshotCannotBeStored() {
        assertThat(new ProductSnapshot(new BigDecimal("1609"),null,"USD","USB C Hub",null,true,"css").isVerified()).isFalse();
        assertThat(new ProductSnapshot(new BigDecimal("1609"),null,"INR",null,null,true,"css").isVerified()).isFalse();
    }
}
