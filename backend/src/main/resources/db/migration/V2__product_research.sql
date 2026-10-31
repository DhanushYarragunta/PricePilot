-- Research evidence is additive; original products, listings and price points are untouched.
CREATE TABLE product_research (
    cache_key varchar(64) PRIMARY KEY,
    product_id bigint NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    listing_id bigint NOT NULL REFERENCES listing(id) ON DELETE CASCADE,
    report_json jsonb NOT NULL,
    researched_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX product_research_product_idx ON product_research(product_id, researched_at DESC);
CREATE INDEX product_research_expiry_idx ON product_research(expires_at);
