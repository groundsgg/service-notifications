CREATE INDEX notification_outbox_pending_available_idx
  ON notification_outbox(available_at, created_at)
  WHERE status = 'pending';
