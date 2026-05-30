CREATE UNIQUE INDEX notification_action_results_action_user_unique_idx
  ON notification_action_results(notification_id, action_id, user_id);
