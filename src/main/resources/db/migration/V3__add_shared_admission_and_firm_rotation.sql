CREATE TABLE fanout_capacity (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    last_firm_id TEXT NOT NULL DEFAULT ''
);

CREATE INDEX fanout_job_ready_by_firm ON fanout_job
    (firm_id, available_at, publication_id, engagement_id, engagement_revision)
    WHERE execution_state = 'READY';
