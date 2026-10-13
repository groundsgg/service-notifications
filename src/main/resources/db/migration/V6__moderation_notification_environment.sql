-- Retain legacy rows without assigning an invented environment.
ALTER TABLE moderation_notification_projection
    ADD COLUMN environment TEXT CHECK (environment IN ('stage', 'prod')),
    ADD COLUMN projection_id UUID NOT NULL DEFAULT gen_random_uuid(),
    DROP CONSTRAINT moderation_notification_projection_pkey,
    DROP CONSTRAINT moderation_notification_projection_source_event_id_key;

ALTER TABLE moderation_notification_projection ADD PRIMARY KEY (projection_id);

CREATE UNIQUE INDEX moderation_projection_legacy_case_key
    ON moderation_notification_projection (case_id, notification_type)
    WHERE environment IS NULL;
CREATE UNIQUE INDEX moderation_projection_environment_case_key
    ON moderation_notification_projection (environment, case_id, notification_type)
    WHERE environment IS NOT NULL;
CREATE UNIQUE INDEX moderation_projection_legacy_event_key
    ON moderation_notification_projection (source_event_id)
    WHERE environment IS NULL;
CREATE UNIQUE INDEX moderation_projection_environment_event_key
    ON moderation_notification_projection (environment, source_event_id)
    WHERE environment IS NOT NULL;

ALTER TABLE notification_actions
    ADD COLUMN environment TEXT CHECK (environment IN ('stage', 'prod')),
    ADD CONSTRAINT notification_action_environment_kind_check
        CHECK (environment IS NULL OR action_kind = 'OPEN_PORTAL_CASE');

ALTER TABLE notification_channel_clients
    ADD COLUMN moderation_environment TEXT CHECK (moderation_environment IN ('stage', 'prod')),
    ADD CONSTRAINT notification_channel_moderation_environment_kind_check
        CHECK (moderation_environment IS NULL OR channel = 'minecraft');

CREATE TABLE moderation_notification_processed_event (
    environment TEXT NOT NULL CHECK (environment IN ('stage', 'prod')),
    event_id UUID NOT NULL,
    case_id UUID NOT NULL,
    case_revision BIGINT NOT NULL CHECK (case_revision >= 1),
    payload_fingerprint BYTEA NOT NULL CHECK (octet_length(payload_fingerprint) = 32),
    notification_id UUID NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    result_status TEXT NOT NULL CHECK (result_status IN ('CREATED', 'UPDATED', 'STALE', 'DUPLICATE')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (environment, event_id)
);
