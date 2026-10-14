ALTER TABLE sites
    ADD COLUMN custom_nginx_locations TEXT NOT NULL DEFAULT '[]';
