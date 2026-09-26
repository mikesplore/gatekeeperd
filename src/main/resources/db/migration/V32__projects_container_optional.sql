-- Projects may be created before a runtime container exists.
ALTER TABLE projects ALTER COLUMN container_name DROP NOT NULL;
