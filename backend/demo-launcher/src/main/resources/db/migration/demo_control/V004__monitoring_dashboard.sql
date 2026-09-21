CREATE TABLE demo_control.monitoring_dashboard (
    dashboard_id text PRIMARY KEY,
    instance_id uuid NOT NULL UNIQUE REFERENCES demo_control.instance(instance_id) ON DELETE CASCADE,
    document jsonb NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (dashboard_id ~ '^db_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CHECK (jsonb_typeof(document) = 'object')
);
