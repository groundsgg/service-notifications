-- The legacy inbox and its diagnostics consume only the general notification tables.
ALTER TABLE notifications ADD CONSTRAINT notification_reserved_safety_type
    CHECK (upper(trim(type)) <> 'MODERATION_SAFETY_SIGNAL');
ALTER TABLE notification_actions ADD CONSTRAINT notification_reserved_safety_navigation
    CHECK (lower(regexp_replace(trim(action_key),'[-_]','','g')) <> 'opensafetysignal'
        AND lower(regexp_replace(trim(command),'[-_]','','g')) <> 'opensafetysignal');

CREATE TABLE notification_safety_signal (
  environment text NOT NULL CHECK (environment IN ('stage','prod')),
  event_id uuid NOT NULL,
  signal_ref uuid NOT NULL,
  occurred_at_utc text NOT NULL,
  expires_at timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','LEASED','READY')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  available_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  lease_token uuid,
  lease_until timestamptz,
  PRIMARY KEY(environment,event_id),
  UNIQUE(environment,signal_ref),
  CHECK ((state='LEASED')=(lease_token IS NOT NULL) AND (lease_token IS NULL)=(lease_until IS NULL))
);
CREATE INDEX notification_safety_expiry ON notification_safety_signal(expires_at);
CREATE INDEX notification_safety_due ON notification_safety_signal(environment,available_at) WHERE state<>'READY';

CREATE TABLE notification_safety_inbox (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  environment text NOT NULL,
  event_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(environment,event_id),
  FOREIGN KEY(environment,event_id) REFERENCES notification_safety_signal(environment,event_id) ON DELETE CASCADE
);
CREATE TABLE notification_safety_recipient (
  notification_id uuid NOT NULL REFERENCES notification_safety_inbox(id) ON DELETE CASCADE,
  user_id uuid NOT NULL,
  read_at timestamptz,
  PRIMARY KEY(notification_id,user_id)
);
CREATE INDEX notification_safety_recipient_user ON notification_safety_recipient(user_id,notification_id);
