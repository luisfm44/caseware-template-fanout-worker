ALTER TABLE fanout_job
    ADD COLUMN execution_state TEXT NOT NULL DEFAULT 'READY',
    ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN claim_token UUID,
    ADD COLUMN claim_until TIMESTAMPTZ,
    ADD COLUMN attempt_id UUID,
    ADD COLUMN authorized_at TIMESTAMPTZ,
    ADD COLUMN submissions INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN poll_count INTEGER NOT NULL DEFAULT 0,
    ADD CHECK (execution_state IN ('READY', 'SUBMITTED', 'APPLIED', 'REJECTED', 'RETRY_LIMIT')),
    ADD CHECK ((execution_state = 'SUBMITTED') = (attempt_id IS NOT NULL)),
    ADD CHECK ((attempt_id IS NULL) = (authorized_at IS NULL)),
    ADD CHECK ((claim_token IS NULL) = (claim_until IS NULL));

CREATE INDEX fanout_job_ready ON fanout_job
    (execution_state DESC, available_at, firm_id, publication_id, engagement_id, engagement_revision)
    WHERE execution_state IN ('READY', 'SUBMITTED');

CREATE UNIQUE INDEX fanout_job_reservations ON fanout_job (attempt_id)
    WHERE attempt_id IS NOT NULL;
