CREATE TABLE sm_control.audit_log (
    -- Technical identity for stable keyset pagination; AuditEntry carries the approved semantic tuple below.
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at timestamptz NOT NULL,
    actor_principal_id uuid NOT NULL,
    verb text NOT NULL,
    resource_kind text NOT NULL,
    resource_id uuid NOT NULL,
    tenant uuid NOT NULL,
    outcome text NOT NULL CONSTRAINT audit_log_outcome CHECK (outcome IN ('allow', 'deny')),
    detail jsonb NOT NULL,
    CONSTRAINT audit_log_actor_principal_fk FOREIGN KEY (actor_principal_id)
        REFERENCES sm_control.principal (id) ON DELETE RESTRICT,
    CONSTRAINT audit_log_verb_fk FOREIGN KEY (verb)
        REFERENCES sm_control.permission_catalog (permission_id) ON DELETE RESTRICT,
    CONSTRAINT audit_log_tenant_fk FOREIGN KEY (tenant)
        REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT
);

CREATE INDEX audit_log_tenant_at_audit_id_idx ON sm_control.audit_log (tenant, at, audit_id);
