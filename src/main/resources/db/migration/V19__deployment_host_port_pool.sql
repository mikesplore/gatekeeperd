CREATE TABLE deployment_port_allocations (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    environment TEXT NOT NULL,
    slot INTEGER NOT NULL CHECK (slot IN (0, 1)),
    container_port INTEGER NOT NULL CHECK (container_port BETWEEN 1 AND 65535),
    host_port INTEGER NOT NULL UNIQUE CHECK (host_port BETWEEN 1024 AND 65535),
    CONSTRAINT uq_deployment_port_slot_container UNIQUE (service_id, environment, slot, container_port)
);

CREATE INDEX idx_deployment_port_allocations_service
    ON deployment_port_allocations(service_id, environment, slot);
