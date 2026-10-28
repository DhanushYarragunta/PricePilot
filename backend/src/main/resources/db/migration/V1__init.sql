-- ===========================================================================
-- PricePilot initial schema
--
-- Design notes:
--  * All timestamps are `timestamptz` and always stored in UTC. The frontend
--    renders in the viewer's local zone.
--  * Money is `numeric(12,2)` — never float. 12 digits covers anything an
--    Indian retailer will quote in INR.
--  * `scrape_attempt` records EVERY fetch, successful or not. It is the source
--    of the reliability numbers shown on the /metrics page; those numbers are
--    measured, not asserted.
-- ===========================================================================

-- ------------------------------------------------------------------ users --
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(120),
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

-- Case-insensitive uniqueness: Foo@x.com and foo@x.com are the same account.
CREATE UNIQUE INDEX ux_app_user_email ON app_user (LOWER(email));

-- --------------------------------------------------------------- products --
-- A `product` is the logical thing a user cares about ("iPhone 15 128GB Black").
-- It may be sold by several retailers, each of which is a `listing`.
CREATE TABLE product (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(500) NOT NULL,
    brand      VARCHAR(160),
    category   VARCHAR(120),
    image_url  VARCHAR(1000),
    currency   VARCHAR(8)   NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- --------------------------------------------------------------- listings --
CREATE TABLE listing (
    id                     BIGSERIAL PRIMARY KEY,
    product_id             BIGINT       NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    retailer               VARCHAR(64)  NOT NULL,
    url                    VARCHAR(2048) NOT NULL,
    -- SHA-256 of the canonicalised URL. Two users tracking the same product
    -- share one listing, so we fetch it once, not twice.
    url_hash               VARCHAR(64)  NOT NULL,
    title                  VARCHAR(500),
    -- ACTIVE | PAUSED | BLOCKED | DEAD
    status                 VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    last_checked_at        TIMESTAMPTZ,
    last_success_at        TIMESTAMPTZ,
    last_strategy          VARCHAR(48),
    consecutive_failures   INTEGER      NOT NULL DEFAULT 0,
    -- The adaptive scheduler owns these two columns.
    check_interval_minutes INTEGER      NOT NULL DEFAULT 180,
    next_check_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_primary             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_listing_url_hash ON listing (url_hash);
CREATE INDEX idx_listing_product ON listing (product_id);
-- The scheduler's hot query: "what is due right now?"
CREATE INDEX idx_listing_due ON listing (next_check_at) WHERE status = 'ACTIVE';

-- ----------------------------------------------------------- price series --
CREATE TABLE price_point (
    id              BIGSERIAL PRIMARY KEY,
    listing_id      BIGINT        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    price           NUMERIC(12, 2) NOT NULL,
    -- The retailer's claimed "was" price. Compared against real observed
    -- history to catch permanently-inflated MRP discounts.
    list_price      NUMERIC(12, 2),
    currency        VARCHAR(8)    NOT NULL DEFAULT 'INR',
    in_stock        BOOLEAN       NOT NULL DEFAULT TRUE,
    seller          VARCHAR(200),
    rating          NUMERIC(3, 2),
    review_count    INTEGER,
    captured_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Which strategy in the fetch chain produced this number.
    source_strategy VARCHAR(48)   NOT NULL,
    -- FALSE when this observation matched the previous one. Lets the chart
    -- query skip flat runs while keeping a full audit trail.
    changed         BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_price_point_listing_time ON price_point (listing_id, captured_at DESC);

-- ---------------------------------------------------------------- trackers --
-- A user's interest in a product, plus their alert preferences.
CREATE TABLE tracker (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    product_id     BIGINT        NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    target_price   NUMERIC(12, 2),
    notify_on_deal BOOLEAN       NOT NULL DEFAULT TRUE,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    note           VARCHAR(500),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_alert_at  TIMESTAMPTZ,
    CONSTRAINT ux_tracker_user_product UNIQUE (user_id, product_id)
);

CREATE INDEX idx_tracker_user ON tracker (user_id);
CREATE INDEX idx_tracker_product ON tracker (product_id);

-- ---------------------------------------------------------- Claude output --
CREATE TABLE deal_analysis (
    id             BIGSERIAL PRIMARY KEY,
    product_id     BIGINT        NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    -- STRONG_BUY | GOOD_VALUE | FAIR | WAIT | OVERPRICED | INSUFFICIENT_DATA
    verdict        VARCHAR(32)   NOT NULL,
    confidence     NUMERIC(4, 3),
    headline       VARCHAR(300),
    reasoning      TEXT,
    fake_discount  BOOLEAN       NOT NULL DEFAULT FALSE,
    suggested_price NUMERIC(12, 2),
    -- The price that was analysed, so we know when the verdict went stale.
    analysed_price NUMERIC(12, 2),
    model          VARCHAR(80),
    input_tokens   INTEGER,
    output_tokens  INTEGER,
    cost_usd       NUMERIC(10, 6),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_deal_analysis_product ON deal_analysis (product_id, created_at DESC);

-- ------------------------------------------------------- scrape telemetry --
-- One row per fetch attempt. This table is what makes the reliability claims
-- on the metrics page defensible in an interview.
CREATE TABLE scrape_attempt (
    id             BIGSERIAL PRIMARY KEY,
    listing_id     BIGINT REFERENCES listing (id) ON DELETE CASCADE,
    host           VARCHAR(160) NOT NULL,
    strategy       VARCHAR(48)  NOT NULL,
    -- SUCCESS | NO_PRICE | BLOCKED | HTTP_ERROR | TIMEOUT | ERROR | SKIPPED_FRESH
    outcome        VARCHAR(32)  NOT NULL,
    http_status    INTEGER,
    latency_ms     INTEGER,
    bytes          INTEGER,
    error_message  VARCHAR(500),
    -- SCHEDULER | MANUAL | ONBOARD | DISCOVERY
    trigger_source VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULER',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_scrape_attempt_time ON scrape_attempt (created_at DESC);
CREATE INDEX idx_scrape_attempt_host_time ON scrape_attempt (host, created_at DESC);
CREATE INDEX idx_scrape_attempt_listing ON scrape_attempt (listing_id);

-- ------------------------------------------------------------------ alerts --
CREATE TABLE alert_log (
    id             BIGSERIAL PRIMARY KEY,
    tracker_id     BIGINT        NOT NULL REFERENCES tracker (id) ON DELETE CASCADE,
    -- TARGET_HIT | DEAL_DETECTED | ALL_TIME_LOW | BACK_IN_STOCK
    kind           VARCHAR(32)   NOT NULL,
    price          NUMERIC(12, 2) NOT NULL,
    message        VARCHAR(600),
    delivered      BOOLEAN       NOT NULL DEFAULT FALSE,
    delivery_error VARCHAR(400),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_alert_log_tracker_time ON alert_log (tracker_id, created_at DESC);

-- ------------------------------------------------- third-party API budgets --
-- Monthly counters so a runaway loop can never drain the Claude credits or
-- the unblocker quota. Checked before every outbound paid call.
CREATE TABLE api_usage (
    id            BIGSERIAL PRIMARY KEY,
    -- ANTHROPIC | TAVILY | UNBLOCKER
    provider      VARCHAR(32)   NOT NULL,
    -- YYYY-MM
    period        VARCHAR(7)    NOT NULL,
    calls         INTEGER       NOT NULL DEFAULT 0,
    input_tokens  BIGINT        NOT NULL DEFAULT 0,
    output_tokens BIGINT        NOT NULL DEFAULT 0,
    cost_usd      NUMERIC(12, 6) NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_api_usage_provider_period UNIQUE (provider, period)
);
