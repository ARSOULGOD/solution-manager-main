CREATE TABLE demo_control.instance_configuration (
    instance_id uuid PRIMARY KEY REFERENCES demo_control.instance(instance_id) ON DELETE CASCADE,
    generation bigint NOT NULL DEFAULT 0,
    values jsonb NOT NULL DEFAULT '{"cache-engine":{}}'::jsonb,
    staged_generation bigint,
    restart_observed_generation bigint,
    updated_at timestamptz NOT NULL,
    CHECK (generation >= 0),
    CHECK (staged_generation IS NULL OR staged_generation BETWEEN 1 AND generation),
    CHECK (restart_observed_generation IS NULL OR restart_observed_generation BETWEEN 1 AND generation)
);

INSERT INTO demo_control.instance_configuration (instance_id, updated_at)
SELECT instance_id, created_at
  FROM demo_control.instance
ON CONFLICT (instance_id) DO NOTHING;
