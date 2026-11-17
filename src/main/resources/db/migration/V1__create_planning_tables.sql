CREATE TABLE fanout_checkpoint (
    firm_id TEXT NOT NULL,
    publication_id TEXT NOT NULL,
    pages_committed BIGINT NOT NULL CHECK (pages_committed > 0),
    next_cursor TEXT,
    complete BOOLEAN NOT NULL,
    snapshot_id TEXT,
    PRIMARY KEY (firm_id, publication_id),
    CHECK (complete = (next_cursor IS NULL))
);

CREATE TABLE fanout_job (
    firm_id TEXT NOT NULL,
    publication_id TEXT NOT NULL,
    engagement_id TEXT NOT NULL,
    engagement_revision TEXT NOT NULL,
    PRIMARY KEY (firm_id, publication_id, engagement_id, engagement_revision),
    FOREIGN KEY (firm_id, publication_id)
        REFERENCES fanout_checkpoint (firm_id, publication_id)
);
