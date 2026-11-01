CREATE TABLE access_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_key TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    description TEXT,
    is_super_admin BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT access_roles_key_format CHECK (role_key ~ '^[a-z][a-z0-9_-]{1,63}$')
);

CREATE TABLE rbac_permissions (
    code TEXT PRIMARY KEY,
    category TEXT NOT NULL,
    action TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES access_roles(id) ON DELETE CASCADE,
    permission_code TEXT NOT NULL REFERENCES rbac_permissions(code) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_code)
);

INSERT INTO access_roles (role_key, display_name, description, is_super_admin)
VALUES
    ('admin', 'Administrator', 'Built-in superuser role with unrestricted access.', TRUE),
    ('operator', 'Operator', 'Default operational access; permissions can be changed by an administrator.', FALSE),
    ('viewer', 'Viewer', 'Default read-only access; permissions can be changed by an administrator.', FALSE)
ON CONFLICT (role_key) DO NOTHING;

-- Preserve existing role names as roles so existing user assignments remain valid.
INSERT INTO access_roles (role_key, display_name, description)
SELECT DISTINCT role, INITCAP(REPLACE(role, '_', ' ')), 'Migrated from the existing user role.'
FROM users
WHERE role ~ '^[a-z][a-z0-9_-]{1,63}$'
ON CONFLICT (role_key) DO NOTHING;

INSERT INTO rbac_permissions (code, category, action, description) VALUES
    ('roles.read', 'roles', 'read', 'View role definitions and available permissions.'),
    ('roles.manage', 'roles', 'manage', 'Create roles and change their permission grants.'),
    ('users.read', 'users', 'read', 'View user accounts.'),
    ('users.manage', 'users', 'manage', 'Create, update, suspend, delete, and assign roles to users.'),
    ('projects.read', 'projects', 'read', 'View projects, project status, and project audit history.'),
    ('projects.manage', 'projects', 'manage', 'Create, update, block, transfer, archive, and configure projects.'),
    ('customers.read', 'customers', 'read', 'View customer records and customer dashboards.'),
    ('customers.manage', 'customers', 'manage', 'Create customers and change customer-project assignments.'),
    ('services.read', 'services', 'read', 'View service configuration, environments, and billing details.'),
    ('services.manage', 'services', 'manage', 'Change services, domains, environments, secrets, and service billing.'),
    ('deployments.read', 'deployments', 'read', 'View deployment jobs, histories, and runtime status.'),
    ('deployments.manage', 'deployments', 'manage', 'Start, cancel, redeploy, roll back, or remove deployments.'),
    ('payments.read', 'payments', 'read', 'View payments, payment events, receipts, and revenue.'),
    ('payments.manage', 'payments', 'manage', 'Record, reconcile, replay, and initialize payments.'),
    ('nginx.read', 'nginx', 'read', 'View nginx sites, configs, certificates, and diagnostics.'),
    ('nginx.manage', 'nginx', 'manage', 'Change nginx sites, configurations, certificates, and upstreams.'),
    ('docker.read', 'docker', 'read', 'View containers, networks, volumes, images, and logs.'),
    ('docker.manage', 'docker', 'manage', 'Create, delete, start, stop, restart, or pull Docker resources.'),
    ('integrations.read', 'integrations', 'read', 'View provider credentials metadata and integration events.'),
    ('integrations.manage', 'integrations', 'manage', 'Change provider credentials and replay integration events.'),
    ('logs.read', 'logs', 'read', 'Read application and system logs.'),
    ('audit.read', 'audit', 'read', 'Read and export audit records.'),
    ('notifications.read', 'notifications', 'read', 'View notification records and delivery state.'),
    ('notifications.manage', 'notifications', 'manage', 'Change notification state and delivery handling.'),
    ('operations.read', 'operations', 'read', 'View system metrics, dashboards, and operational status.'),
    ('system.manage', 'system', 'manage', 'Run system-level operations such as cleanup and manual deployment.')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT role.id, permission.code
FROM access_roles role CROSS JOIN rbac_permissions permission
WHERE role.role_key = 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT role.id, permission.code
FROM access_roles role CROSS JOIN rbac_permissions permission
WHERE role.role_key = 'viewer'
  AND permission.action = 'read'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT role.id, permission.code
FROM access_roles role CROSS JOIN rbac_permissions permission
WHERE role.role_key = 'operator'
  AND permission.code IN (
      'projects.read', 'projects.manage',
      'customers.read', 'customers.manage',
      'services.read', 'services.manage',
      'deployments.read', 'deployments.manage',
      'payments.read', 'payments.manage',
      'nginx.read', 'nginx.manage',
      'docker.read', 'docker.manage',
      'integrations.read', 'integrations.manage',
      'logs.read', 'audit.read',
      'notifications.read', 'notifications.manage',
      'operations.read'
  )
ON CONFLICT DO NOTHING;

ALTER TABLE users
    ADD CONSTRAINT users_role_access_roles_fk
    FOREIGN KEY (role) REFERENCES access_roles(role_key)
    ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX role_permissions_permission_idx ON role_permissions(permission_code);
