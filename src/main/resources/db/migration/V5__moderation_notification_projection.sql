ALTER TABLE notification_actions
    ALTER COLUMN command DROP NOT NULL,
    ADD COLUMN action_kind TEXT NOT NULL DEFAULT 'COMMAND'
        CHECK (action_kind IN ('COMMAND', 'OPEN_PORTAL_CASE')),
    ADD COLUMN entity_type TEXT,
    ADD COLUMN entity_id TEXT;

-- Empty commands were accepted by the legacy API. They cannot be executed safely and
-- would violate the typed action shape, so remove those invalid actions before validating
-- the new constraint. Referencing action results are removed by their existing cascade.
DELETE FROM notification_actions
WHERE length(trim(command)) = 0;

ALTER TABLE notification_actions
    ADD CONSTRAINT notification_action_typed_shape_check CHECK (
        (
            action_kind = 'COMMAND'
            AND command IS NOT NULL
            AND length(trim(command)) > 0
            AND entity_type IS NULL
            AND entity_id IS NULL
        )
        OR (
            action_kind = 'OPEN_PORTAL_CASE'
            AND command IS NULL
            AND entity_type = 'CASE'
            AND entity_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND NOT (payload ? 'url')
        )
    );

CREATE TABLE moderation_notification_projection (
    case_id UUID NOT NULL,
    notification_type TEXT NOT NULL,
    notification_id UUID NOT NULL UNIQUE REFERENCES notifications (id) ON DELETE CASCADE,
    source_event_id UUID NOT NULL UNIQUE,
    last_case_revision BIGINT NOT NULL CHECK (last_case_revision >= 1),
    audience_fingerprint BYTEA NOT NULL CHECK (octet_length(audience_fingerprint) = 32),
    audience_resolved_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (case_id, notification_type)
);
