-- Service-level domain bindings. One row maps a service to a hostname via the
-- container port the domain should route to. container_port is nullable so a
-- domain can be registered before the first deployment publishes a port.
-- domain is globally unique: no two services may share a hostname.
-- status tracks binding lifecycle: pending (port not yet assigned), bound
-- (port assigned and published), port_unavailable (port dropped by a later
-- deployment; binding is orphaned and must not route).
CREATE TABLE service_domains (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    container_port INTEGER NULL,
    domain TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'bound', 'port_unavailable')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_domains_service ON service_domains(service_id);
CREATE INDEX idx_service_domains_domain ON service_domains(domain);

-- Backfill existing service.domain values as null-port bindings so legacy
-- single-domain services keep working through the services.domain compat
-- projection. Only services that already have a domain and no service_domains
-- row yet are backfilled. Duplicate domains across services are skipped
-- safely (the first service wins); such conflicts require manual resolution.
INSERT INTO service_domains (id, service_id, container_port, domain, status)
SELECT
    gen_random_uuid(),
    svc.id,
    NULL,
    svc.domain,
    'pending'
FROM services svc
WHERE svc.domain IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM service_domains sd WHERE sd.service_id = svc.id
  )
ON CONFLICT (domain) DO NOTHING;
