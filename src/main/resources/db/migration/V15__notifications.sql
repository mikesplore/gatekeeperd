CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    recipient TEXT NOT NULL,
    project_id UUID NULL REFERENCES projects(id),
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    severity TEXT NOT NULL,
    action TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP NULL,
    dismissed_at TIMESTAMP NULL,
    archived_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created ON notifications(recipient, created_at DESC);
