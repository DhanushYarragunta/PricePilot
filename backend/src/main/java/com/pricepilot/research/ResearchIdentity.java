package com.pricepilot.research;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.Product;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.ProductRepository;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.web.ApiException;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class ResearchIdentity {
    private final ProductRepository products;
    private final ListingRepository listings;
    public ResearchIdentity(ProductRepository products,ListingRepository listings){this.products=products;this.listings=listings;}
    public ResearchReport.Identity resolve(Long productId,ResearchReport.Request request){
        Product product=products.findById(productId).orElseThrow(()->ApiException.notFound("Product"));
        List<Listing> candidates=listings.findByProductId(productId);
        Listing listing=request.listingId()==null?candidates.stream().min(Comparator.comparing((Listing l)->!l.isPrimary())
                .thenComparing(Listing::getId)).orElseThrow(()->ApiException.notFound("Listing")):
                candidates.stream().filter(l->request.listingId().equals(l.getId())).findFirst().orElseThrow(()->ApiException.notFound("Listing"));
        String title=listing.getTitle()==null||listing.getTitle().isBlank()?product.getTitle():listing.getTitle();
        if(title==null||title.isBlank())throw ApiException.badRequest("This listing needs a product title before research can begin.");
        String model=clean(request.model()), variant=clean(request.variant());
        List<String> unknown=new ArrayList<>();
        boolean extractedModel=false;
        if(model!=null&&!ResearchEvidence.containsPhrase(title,model))
            throw ApiException.badRequest("The model does not match the stored product title. Use the model printed in that title, or leave it blank.");
        if(variant!=null&&!ResearchEvidence.containsPhrase(title,variant))
            throw ApiException.badRequest("The variant does not match the stored product title. Use the variant printed in that title, or leave it blank.");
        LinkedHashMap<String,String> identifiers=new LinkedHashMap<>();
        if(model==null){
            var code=Pattern.compile("\\b[A-Z]{2,8}-[A-Z0-9]*[0-9][A-Z0-9-]*\\b").matcher(title);
            if(code.find()){
                model=code.group();extractedModel=true;
                identifiers.put("modelEvidence","exact_code_in_retailer_title");
                unknown.add("A model code was read from the retailer title; it is not independently confirmed by manufacturer evidence.");
            }
        }

        try{
            URI url=URI.create(listing.getUrl());
            var asin=Pattern.compile("/(?:dp|gp/product)/([A-Z0-9]{10})(?:/|$)").matcher(url.getPath());
            if(url.getHost()!=null&&(url.getHost().equals("amazon.in")||url.getHost().endsWith(".amazon.in"))&&asin.find())identifiers.put("asin",asin.group(1));
            var pid=Pattern.compile("(?:^|&)pid=([^&]+)").matcher(url.getRawQuery()==null?"":url.getRawQuery());
            if(url.getHost()!=null&&(url.getHost().equals("flipkart.com")||url.getHost().endsWith(".flipkart.com"))&&pid.find())identifiers.put("pid",pid.group(1));
        }catch(IllegalArgumentException ignored){unknown.add("The stored retailer URL could not be interpreted.");}
        var storage=Pattern.compile("(?i)\\b(\\d{1,4})\\s*(GB|TB)\\b").matcher(title);
        List<String> capacities=new ArrayList<>();while(storage.find())capacities.add(storage.group(1)+storage.group(2).toUpperCase(Locale.ROOT));
        if(!capacities.isEmpty())identifiers.put("capacitiesInTitle",String.join(", ",capacities));
        identifiers.put("identityEvidence","retailer_title_and_stored_url");
        if(product.getBrand()!=null)identifiers.put("brandEvidence","existing_product_metadata");
        var colours=Pattern.compile("(?i)\\b(black|white|blue|green|red|grey|gray|pink|purple|yellow|orange|gold|silver)\\b").matcher(title);
        List<String> colourNames=new ArrayList<>();while(colours.find())colourNames.add(colours.group().toLowerCase(Locale.ROOT));
        if(!colourNames.isEmpty())identifiers.put("coloursInTitle",String.join(", ",colourNames));
        var generation=Pattern.compile("(?i)\\b(?:[0-9]+(?:st|nd|rd|th)?\\s+(?:gen|generation)|(?:gen|generation)\\s+[0-9]+)\\b").matcher(title);
        if(generation.find())identifiers.put("generationInTitle",generation.group());
        if(model==null)unknown.add("The exact model has not been separately confirmed. Research uses the complete retailer title, not only the brand.");
        if(variant==null)unknown.add("Storage, colour, generation and selected variant are not independently confirmed; cross-variant claims may be omitted.");
        unknown.add("The stored URL is canonical. Variant choices absent from its title or URL cannot be recovered from earlier page interactions.");
        return new ResearchReport.Identity(productId,listing.getId(),title,product.getBrand(),model,variant,listing.getUrl(),
                java.util.Map.copyOf(identifiers),model==null||extractedModel||variant==null,List.copyOf(unknown));
    }
    static String clean(String text){return text==null||text.isBlank()?null:text.replaceAll("\\s+"," ").trim();}
    static String anchor(ResearchReport.Identity identity){return identity.model()==null?identity.title():identity.model()+
            (identity.variant()==null?"":" "+identity.variant());}
    static String key(ResearchReport.Identity identity){return UrlCanonicalizer.sha256Hex("research-v1|"+identity.productId()+"|"+
            identity.listingId()+"|"+identity.referenceUrl()+"|"+identity.title()+"|"+identity.model()+"|"+identity.variant()+"|"+identity.ambiguous());}
}
