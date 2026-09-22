-- Keep joins and cascading operational queries index-backed.
CREATE INDEX IF NOT EXISTS idx_payment_events_payment_id ON payment_events(payment_id);
CREATE INDEX IF NOT EXISTS idx_payment_events_project_id ON payment_events(project_id);
CREATE INDEX IF NOT EXISTS idx_support_requests_project_id ON support_requests(project_id);
CREATE INDEX IF NOT EXISTS idx_notifications_project_id ON notifications(project_id);
CREATE INDEX IF NOT EXISTS idx_deployment_jobs_project_id ON deployment_jobs(project_id);
CREATE INDEX IF NOT EXISTS idx_deployment_executions_job_id ON deployment_executions(job_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
