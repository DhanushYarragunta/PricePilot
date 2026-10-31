package com.pricepilot.research;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.Product;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.ProductRepository;
import com.pricepilot.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchIdentityTest {
    private final ProductRepository products=mock(ProductRepository.class);
    private final ListingRepository listings=mock(ListingRepository.class);
    private final ResearchIdentity resolver=new ResearchIdentity(products,listings);
    @BeforeEach void setup(){
        Product product=new Product();product.setId(1L);product.setTitle("Acme AX-100 128GB Black");product.setBrand("Acme");
        Listing listing=new Listing();listing.setId(2L);listing.setProduct(product);listing.setPrimary(true);
        listing.setTitle(product.getTitle());listing.setUrl("https://www.amazon.in/dp/B07CR28HLL");
        when(products.findById(1L)).thenReturn(Optional.of(product));when(listings.findByProductId(1L)).thenReturn(List.of(listing));
    }
    @Test void declaredModelAndVariantMustMatchTitle(){
        var identity=resolver.resolve(1L,new ResearchReport.Request(2L,"AX-100","128GB Black",false));
        assertThat(identity.ambiguous()).isFalse();assertThat(identity.identifiers()).containsEntry("asin","B07CR28HLL");
        assertThat(identity.referenceUrl()).isEqualTo("https://www.amazon.in/dp/B07CR28HLL");
        assertThat(identity.identifiers().get("identityEvidence")).isEqualTo("retailer_title_and_stored_url");
    }
    @Test void unknownModelUsesFullTitleAndMarksUncertainty(){
        var identity=resolver.resolve(1L,new ResearchReport.Request(null,null,null,false));
        assertThat(identity.ambiguous()).isTrue();assertThat(identity.model()).isEqualTo("AX-100");
        assertThat(identity.identifiers()).containsEntry("modelEvidence","exact_code_in_retailer_title");
        assertThat(identity.uncertainties()).anyMatch(s->s.contains("not independently confirmed"));
    }
    @Test void incorrectVariantAndCrossProductListingAreRejected(){
        assertThatThrownBy(()->resolver.resolve(1L,new ResearchReport.Request(2L,"AX-101",null,false))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->resolver.resolve(1L,new ResearchReport.Request(2L,"AX-100","256GB",false))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->resolver.resolve(1L,new ResearchReport.Request(99L,null,null,false))).isInstanceOf(ApiException.class);
    }
    @Test void cacheIdentityIncludesExplicitModelAndVariant(){
        var full=resolver.resolve(1L,new ResearchReport.Request(2L,"AX-100","128GB Black",false));
        var unknown=resolver.resolve(1L,new ResearchReport.Request(2L,null,null,false));
        assertThat(ResearchIdentity.key(full)).isNotEqualTo(ResearchIdentity.key(unknown));
    }
    @Test void cacheSeparatesInferredTitleCodeFromExplicitlyMatchedModel(){
        var inferred=resolver.resolve(1L,new ResearchReport.Request(2L,null,"128GB Black",false));
        var explicit=resolver.resolve(1L,new ResearchReport.Request(2L,"AX-100","128GB Black",false));
        assertThat(inferred.ambiguous()).isTrue();assertThat(explicit.ambiguous()).isFalse();
        assertThat(ResearchIdentity.key(inferred)).isNotEqualTo(ResearchIdentity.key(explicit));
    }
    @Test void noModelCodeUsesEntireTitleWithUncertainty(){
        Listing listing=listings.findByProductId(1L).get(0);listing.setTitle("Acme Comfortable Everyday Headphones Black");
        var identity=resolver.resolve(1L,new ResearchReport.Request(2L,null,null,false));
        assertThat(identity.model()).isNull();assertThat(ResearchIdentity.anchor(identity)).isEqualTo(listing.getTitle());
        assertThat(identity.identifiers()).containsEntry("coloursInTitle","black");
    }

}
