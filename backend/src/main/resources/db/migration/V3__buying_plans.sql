-- Private planning state; shared observed prices remain append-only in price_point.
CREATE TABLE buying_plan (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    listing_id BIGINT REFERENCES listing(id) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    next_reassessment_at TIMESTAMPTZ,
    last_observed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_buying_plan_owner_product UNIQUE(user_id, product_id)
);
CREATE INDEX idx_buying_plan_due ON buying_plan(next_reassessment_at);

CREATE TABLE buying_plan_conversation (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES buying_plan(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK(role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_buying_plan_conversation ON buying_plan_conversation(plan_id, id DESC);

CREATE TABLE buying_plan_notification (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES buying_plan(id) ON DELETE CASCADE,
    kind VARCHAR(48) NOT NULL,
    dedup_key VARCHAR(250) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    unconfirmed_at TIMESTAMPTZ,
    obsolete_at TIMESTAMPTZ,
    delivery_error VARCHAR(80),
    claimed_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivery_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ux_buying_plan_notification UNIQUE(plan_id, dedup_key)
);
CREATE INDEX idx_buying_plan_notification_pending ON buying_plan_notification(next_attempt_at) WHERE delivered_at IS NULL;
