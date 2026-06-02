CREATE TABLE notification_type_defaults (
  id UUID PRIMARY KEY,
  notification_type TEXT NOT NULL UNIQUE,
  category TEXT NOT NULL,
  web_enabled BOOLEAN NOT NULL DEFAULT true,
  minecraft_enabled BOOLEAN NOT NULL DEFAULT false,
  email_enabled BOOLEAN NOT NULL DEFAULT false,
  discord_enabled BOOLEAN NOT NULL DEFAULT false,
  push_enabled BOOLEAN NOT NULL DEFAULT false,
  allowed_producers TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_notification_settings (
  id UUID PRIMARY KEY,
  team_id TEXT NOT NULL,
  category TEXT NOT NULL,
  notification_type TEXT,
  web_default BOOLEAN NOT NULL DEFAULT true,
  minecraft_default BOOLEAN NOT NULL DEFAULT true,
  email_default BOOLEAN NOT NULL DEFAULT false,
  discord_default BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX team_notification_settings_unique_idx
  ON team_notification_settings(team_id, category, COALESCE(notification_type, ''));

CREATE TABLE notification_admin_audit_events (
  id UUID PRIMARY KEY,
  actor_user_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX notification_deliveries_admin_status_idx
  ON notification_deliveries(status, channel, created_at DESC);

CREATE INDEX notification_action_results_admin_status_idx
  ON notification_action_results(status, created_at DESC);

CREATE INDEX notification_admin_audit_events_created_idx
  ON notification_admin_audit_events(created_at DESC);
