# Worker design decisions

The repository implements publication planning and the durable PostgreSQL worker. The executable demo illustrates the same operation identity and recovery contract with two logical workers in one JVM. The [system design and implementation appendix](architecture.pdf) place these contracts within the wider Caseware proposal. The [worker architecture](worker-architecture.md) traces the code paths and their boundaries.

## Demo admission · Issue [#1][issue-1]

Two logical workers acquire permits from one in-memory semaphore. A caller can time out or cancel its own future while the worker retains the permit until the downstream reaches a confirmed terminal state. A full pool returns immediately so the caller can decide when to try again. The tests verify a peak of three operations across both workers and prove that a caller timeout leaves the occupied slot reserved. [Shared admission tests][admission-test]

The semaphore coordinates workers in the single-JVM demo. The durable worker profile uses PostgreSQL to coordinate independent processes.

## Durable planning · Issue [#2][issue-2]

The planner converts each bounded page of eligible engagements into jobs keyed by `OperationId`, which combines firm, engagement, publication and engagement revision. PostgreSQL commits the page's jobs and checkpoint together. A checkpoint comparison rejects a stale writer, and the job key makes page replay safe. [PostgreSQL integration tests][planning-test]

`PostgresFanoutStore` uses JDBC directly. The SQL expresses the checkpoint comparison and unique job key, and the store commits or rolls back the page explicitly. The planning schema starts with two tables. An ORM such as Hibernate would provide entity mapping if the model grows to include more relationships and entity lifecycles.

### Engagement snapshots

Issue [#19][issue-19]. The engagement source returns a snapshot ID with the first page. It must preserve eligible membership, order, engagement revisions and cursor meaning within that snapshot, including across process restarts. Each resumed request supplies the pinned ID and cursor. An expired or unavailable snapshot is an explicit source error. The planner checks that the response identifies the requested snapshot before accepting the page. The source and regional API own eligibility and target-version rules.

A fresh planner resumes from the last committed snapshot ID and cursor. Legacy completed checkpoints still end planning immediately. An incomplete legacy checkpoint without a snapshot ID stops with an explicit error because its cursor has no verified snapshot. The durable worker profile claims the persisted jobs for execution. Upgrade all planners sharing the schema before relying on snapshot pinning across processes.

The [snapshot integration tests][snapshot-test] resume in a fresh JVM after the source inserts, deletes and revises engagements. They verify that expiry and mismatched responses leave the committed jobs and checkpoint intact.

Regional validation must confirm that the real engagement source can reopen the pinned snapshot after a process restart and that its retention period covers the expected publication recovery window. [Regional validation][issue-20]

## Demo reconciliation · Issue [#3][issue-3]

The retrying client carries the same operation ID through every submission and status lookup. After a lost reply, confirmed success completes the original execution. A new submission requires confirmation that the previous one had no effect and can no longer run. The client returns a typed failure after a permanent rejection or when the last allowed submission is confirmed to have no effect. [Retry and reconciliation tests][retry-test]

The demo's three-submission budget applies to one worker execution and is held in memory. The worker retains its permit during backoff and unresolved reconciliation, which can reduce throughput when status is unavailable. A live downstream adapter must enforce idempotency and supply authoritative status. The current tests use a controlled transport that can record an effect, lose the reply, and confirm that only one effect occurred.

## Executable demo · Issue [#4][issue-4]

The Spring Boot `demo` profile connects the planning and execution paths in one bounded run. It uses PostgreSQL in a disposable schema, commits the first page of a seven-engagement publication, and creates a fresh planner to finish from the persisted cursor. The demo reads the resulting job IDs and passes them to two logical workers that share three admission slots.

The simulator holds submissions under test control so the demo can measure overlap. For one operation it applies the effect and drops the reply. The worker uses the same key to reconcile the applied effect, and the demo reports dispatch attempts and lookups alongside the effect count and observed peak. The run removes its temporary schema. [Run the demo](../README.md#postgresql-integration-tests-and-executable-demo) · [Smoke workflow](../.github/workflows/demo.yml)

## Durable consumption and shared capacity

Issues [#15](https://github.com/luisfm44/caseware-template-fanout-worker/issues/15), [#16](https://github.com/luisfm44/caseware-template-fanout-worker/issues/16) and [#17](https://github.com/luisfm44/caseware-template-fanout-worker/issues/17).

- The [durable runner][durable-runner] starts one polling loop per JVM. The planner initializes the schema and writes ready jobs. Each replica uses the same schema, capacity and downstream origin.
- The [PostgreSQL queue][queue] reclaims due attempts using `FOR UPDATE SKIP LOCKED`. A new submission is claimed for `worker.lease-ms`. A reclaimed lookup has the HTTP deadline plus that lease duration to give a healthy request time to finish and persist its result. When the lease expires, another process can replace the token and reconcile the same attempt.
- A pending response schedules the next lookup after the greater of `Retry-After` and the poll backoff. An unavailable or invalid HTTP reply uses poll backoff. PostgreSQL defers the lookup and increments its poll count only while the token and attempt ID match, even if the lease has expired. The reservation stays with the unresolved attempt.
- The `fanout_capacity` row serializes fresh admissions for the schema. The transaction that claims fresh work also reserves the slot, records a new attempt ID and increments the durable submission count. This counts authorizations even when the process dies before sending. Three authorizations are allowed per job. Status queries have their own bounded backoff.
- An attempt reserves a slot until the downstream confirms success, rejection or definitive no effect. One SQL update compares the claim token and attempt ID, records the result and clears the reservation. The same token can settle after its lease expires if it still matches. A replaced token updates zero rows, so the worker logs that the current claimant must reconcile. A confirmed no-effect outcome schedules a new attempt if budget remains. [Lease settlement regression (#33)](https://github.com/luisfm44/caseware-template-fanout-worker/issues/33)
- Track unresolved work from `fanout_job.authorized_at`. An alert can use `SELECT max(clock_timestamp() - authorized_at) FROM fanout_job WHERE attempt_id IS NOT NULL`. The elapsed time measures uncertainty and does not determine when a slot is safe to release.

The HTTP adapter uses `POST /submit` and `POST /resolve` at the configured downstream origin. Both carry URL-encoded form fields `firmId`, `engagementId`, `publicationId`, `engagementRevision` and `attemptId`. A successful HTTP response has a plain-text outcome of `APPLIED`, `REJECTED`, `NOT_APPLIED` or `PENDING`, with optional `Retry-After` in seconds. The HTTP deadline covers the response headers and body. The worker cancels a locally timed-out exchange and treats its outcome as uncertain. Errors and unusable responses also leave the outcome uncertain. The engagement manager owns durable idempotency by operation ID and validates the expected revision. A `NOT_APPLIED` response certifies that the attempt had no effect and is fenced against late execution. The status contract requires fencing even if the manager has yet to see the original submission.

The [process integration test][process-test] starts independent JVMs with a common PostgreSQL schema and controlled HTTP downstream. It verifies lease recovery across process loss, slot retention through status unavailability, a single effect per operation, fencing of a delayed request and rejection of a stale engagement revision. [Process test command and expected outcomes](worker-architecture.md#verification) · [Verified CI reports](evidence/README.md)

Transient PostgreSQL connection failures and unknown commit outcomes pause polling with capped backoff. On reconnection the worker reads the persisted queue state and reconciles any expired attempt. An interrupted database write may already have committed. SQL failures outside the recoverable states remain fatal.

[Run the two-JVM demo](../README.md#run-two-durable-workers-locally) to seed and consume 12 jobs with a loopback HTTP mock.

## Scheduling across firms

Issue [#18](https://github.com/luisfm44/caseware-template-fanout-worker/issues/18). Each new reservation advances a shared cursor to the admitted firm in the PostgreSQL capacity row. With a slot available, the scheduler selects the next firm with eligible work in firm ID order, wrapping to the beginning as needed. Within that firm it orders ready jobs across publications by eligibility time and breaks ties by publication, engagement and revision. Selection, reservation and cursor advancement commit together. This prevents a worker from claiming a fresh large-firm job ahead of capacity and then bypassing the next firm's turn.

With a fixed set of continuously eligible firms, each receives a reservation within one rotation. Two eligible firms alternate when both have ready work. A sole eligible firm can use all available capacity. Deferred retries participate when they become due. Reconciliation keeps the existing reservation and leaves the cursor in place. The guarantee is expressed in new reservations when slots become available. Independent HTTP calls can reach the downstream in a different order when several slots open together.

The [two-JVM integration test][process-test] fills a two-slot pool with large-firm work, adds a small firm and releases one slot. It checks that the next downstream start belongs to the small firm with 30 large-firm jobs still ready. It then verifies that the large firm resumes and that the measured peak stays at two. Apply the worker version to all replicas sharing the schema before relying on the rotation across the pool.

## Regional delivery boundary

Issue [#20][issue-20]. The exercise uses the PostgreSQL job table as its dispatch queue. The proposed regional deployment uses SQS Standard for delivery and a regional PostgreSQL ledger for claims, authorized attempts and terminal results. The engagement manager provides the hard limit and owns the effect ledger. A worker restart or redelivery therefore resumes the same operation and reconciles any uncertain attempt. The regional worker acknowledges only after recording a terminal result.

The PostgreSQL worker tests use a controlled downstream and engagement source. Regional release criteria require a real manager, source and SQS test queue. The proposed SQS Fair Queues configuration uses the firm ID to reduce dwell time for quieter firms. The implemented PostgreSQL scheduler advances a firm cursor on each new reservation. Version selection and summary validation belong to their owning services.

[Regional deployment design](system-architecture.md#dispatch-by-environment) · [Regional verification plan](regional-verification.md) · [Owner-side acceptance tests](owner-acceptance.md)

## Code quality · Issue [#8][issue-8]

JaCoCo combines coverage from the unit and PostgreSQL integration suites. SonarQube Community Build enforces the overall coverage floor as part of the required PR check and on pushes to `main`. Manual verification is also available. The default runner-local instance publishes the gate, metrics and findings as artifacts. An existing SonarQube instance can provide historical analysis through a configured project token. [Quality workflow and reproduction](code-quality.md)

[Issue #12][issue-12] sets the floor at 100% and checks line and branch coverage independently in Maven. Tests exercise worker validation, retry and reconciliation paths, real PostgreSQL transactions and the demo. The isolated negative probe uses unit-only execution data to verify that SonarQube rejects a drop in coverage.

The [GitHub Wiki][wiki] provides navigation for reviewers and contributors. The [contributor guide](contributing.md) and [pipeline reference](pipelines.md) record the delivery workflow in the repository. [Issue #9][issue-9]

When an issue changes a contract, component boundary, or verification step, update the affected design decision and architecture section in the same pull request. Link the issue and the test or workflow that demonstrates the change. Update the system design appendix when the change affects the relationship between the implementation and the proposed deployment.

[issue-1]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/1
[issue-2]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/2
[issue-3]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/3
[issue-4]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/4
[issue-8]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/8
[issue-9]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/9
[issue-12]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/12
[issue-19]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/19
[issue-20]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/20
[wiki]: https://github.com/luisfm44/caseware-template-fanout-worker/wiki
[admission-test]: ../src/test/java/io/github/luisfm44/caseware/worker/execution/admission/SharedAdmissionTest.java
[planning-test]: ../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationFanoutIT.java
[snapshot-test]: ../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationSnapshotIT.java
[retry-test]: ../src/test/java/io/github/luisfm44/caseware/worker/execution/retry/RetryingDownstreamClientTest.java
[durable-runner]: ../src/main/java/io/github/luisfm44/caseware/worker/bootstrap/DurableWorkerRunner.java
[queue]: ../src/main/java/io/github/luisfm44/caseware/worker/adapter/postgres/PostgresJobQueue.java
[process-test]: ../src/test/java/io/github/luisfm44/caseware/worker/bootstrap/DurableWorkerProcessIT.java
