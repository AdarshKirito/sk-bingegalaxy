-- V2 — cluster-safe scheduling for distribution-service.
--
-- WHY THIS IS ITS OWN TABLE. booking_db gets a shedlock table from
-- infra/init-databases.sql, but distribution-service has a SEPARATE database, and a lock
-- is only a lock if every contender reads the same row. Sharing booking's table would
-- mean a cross-database join that does not exist; not having one at all means every
-- replica runs every sweep.
--
-- WHAT GOES WRONG WITHOUT IT. The credential-expiry sweep notifies an operator that a
-- channel is about to stop working. With three replicas and no lock, that is three
-- identical warnings for one problem — and an alert channel that cries wolf is one
-- people stop reading, which is worse than not warning at all.
--
-- Column names and types are ShedLock's own contract (JdbcTemplateLockProvider); they
-- are not ours to rename.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS
    'ShedLock coordination for distribution-service schedulers. One row per job name; see V2.';
