CREATE TABLE notifications (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  event_id varchar(50),
  channel varchar(20) NOT NULL,
  title varchar(255) NOT NULL,
  content text NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  sent_at timestamp,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT chk_notification_channel CHECK (channel IN ('EMAIL', 'PUSH', 'IN_APP', 'SMS')),
  CONSTRAINT chk_notification_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'READ'))
);

CREATE TABLE notification_attempts (
  id uuid NOT NULL,
  notification_id uuid NOT NULL,
  attempt_no integer NOT NULL,
  result varchar(20) NOT NULL,
  error text,
  attempted_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
  CONSTRAINT chk_attempt_no CHECK (attempt_no >= 1),
  CONSTRAINT chk_attempt_result CHECK (result IN ('SUCCESS', 'FAILURE', 'RETRY'))
);

CREATE TABLE notification_preferences (
  id uuid NOT NULL,
  user_id uuid NOT NULL,
  notification_type varchar(50) NOT NULL,
  channel varchar(20) NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  PRIMARY KEY (id),
  CONSTRAINT uq_user_notification_pref UNIQUE (user_id, notification_type, channel), -- 1 user không bị cấu hình lặp kênh + loại tin
  CONSTRAINT chk_pref_channel CHECK (channel IN ('EMAIL', 'PUSH', 'IN_APP', 'SMS'))
);

CREATE TABLE event_logs (
  id uuid NOT NULL,
  event_id varchar(50) NOT NULL,
  source_service varchar(50) NOT NULL,
  payload jsonb NOT NULL,
  operator_id varchar(100),
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

CREATE INDEX idx_notifications_user ON notifications(user_id);
CREATE INDEX idx_notifications_status ON notifications(status);
CREATE INDEX idx_event_logs_event ON event_logs(event_id);
CREATE INDEX idx_event_logs_source ON event_logs(source_service);
