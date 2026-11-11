# Worker design decisions

The repository implements the worker contracts for admission, durable planning, and downstream recovery. The [system design and implementation appendix](architecture.pdf) place these contracts within the wider Caseware proposal. The [worker architecture](worker-architecture.md) traces the code paths and their boundaries.

## Shared admission · Issue [#1][issue-1]

Two logical workers acquire permits from one in-memory semaphore. A caller can time out or cancel its own future while the worker retains the permit until the downstream reaches a confirmed terminal state. A full pool returns immediately so the caller can decide when to try again. The tests verify a peak of three operations across both workers and prove that a caller timeout leaves the occupied slot reserved. [Shared admission tests][admission-test]

The semaphore coordinates workers in the single-JVM demo. The durable worker profile uses PostgreSQL to coordinate independent processes.

## Durable planning · Issue [#2][issue-2]

The planner converts each bounded page of eligible engagements into jobs keyed by `OperationId`, which combines firm, engagement, publication, and engagement revision. PostgreSQL commits the page's jobs and checkpoint together. A checkpoint comparison rejects a stale writer, and the job key makes page replay safe. The engagement source must preserve membership, revision, order, and cursor meaning for the life of a publication. [PostgreSQL integration tests][planning-test]

`PostgresFanoutStore` uses JDBC directly. The SQL expresses the checkpoint comparison and unique job key, and the store commits or rolls back the page explicitly. The planning schema starts with two tables. An ORM such as Hibernate would provide entity mapping if the model grows to include more relationships and entity lifecycles.

A fresh planner resumes from the last committed cursor after interruption. The transaction boundary covers planning data. The durable worker profile claims the persisted jobs for execution.

## Downstream reconciliation · Issue [#3][issue-3]

The retrying client carries the same operation ID through every submission and status lookup. After a lost reply, confirmed success completes the original execution. A new submission requires confirmation that the previous one had no effect and can no longer run. The client returns a typed failure after a permanent rejection or when the last allowed submission is confirmed to have no effect. [Retry and reconciliation tests][retry-test]

The three-submission budget applies to one worker execution and is held in memory. The worker retains its permit during backoff and unresolved reconciliation, which can reduce throughput when status is unavailable. A live downstream adapter must enforce idempotency and supply authoritative status. The current tests use a controlled transport that can record an effect, lose the reply, and confirm that only one effect occurred.

## Executable demo · Issue [#4][issue-4]

The Spring Boot `demo` profile connects the planning and execution paths in one bounded run. It uses PostgreSQL in a disposable schema, commits the first page of a seven-engagement publication, and creates a fresh planner to finish from the persisted cursor. The demo reads the resulting job IDs and passes them to two logical workers that share three admission slots.

The simulator holds submissions under test control so the demo can measure overlap. For one operation it applies the effect and drops the reply. The worker uses the same key to reconcile the applied effect, and the demo reports dispatch attempts and lookups alongside the effect count and observed peak. The run removes its temporary schema. [Run the demo](../README.md#postgresql-integration-tests-and-executable-demo) · [Smoke workflow](../.github/workflows/demo.yml)

## Durable consumption and shared capacity

Issues [#15](https://github.com/luisfm44/caseware-template-fanout-worker/issues/15), [#16](https://github.com/luisfm44/caseware-template-fanout-worker/issues/16) and [#17](https://github.com/luisfm44/caseware-template-fanout-worker/issues/17).

- The `postgres-worker` profile processes one attempt at a time per JVM. It reclaims due attempts using `FOR UPDATE SKIP LOCKED`. The claim expires independently of downstream capacity. Each write checks the current claim token. A new claimant reconciles a recorded attempt before it can send again.
- A PostgreSQL capacity row serializes admissions for the schema. The transaction that claims fresh work also reserves the slot, records a new attempt ID and increments the durable submission count. This counts authorizations even when the process dies before sending. Three authorizations are allowed per job. Status queries have their own bounded backoff.
- An attempt reserves a slot until the downstream confirms success, rejection or definitive no effect. One SQL update records the confirmed result and clears the reservation. A confirmed no-effect outcome releases the slot and schedules a new attempt if budget remains.
- Track unresolved work from `fanout_job.authorized_at`. An alert can use `SELECT max(clock_timestamp() - authorized_at) FROM fanout_job WHERE attempt_id IS NOT NULL`. The elapsed time measures uncertainty and does not determine when a slot is safe to release.

The HTTP adapter uses `POST /submit` and `POST /resolve` at the configured downstream origin. Both carry URL-encoded form fields `firmId`, `engagementId`, `publicationId`, `engagementRevision` and `attemptId`. A successful HTTP response has a plain-text outcome of `APPLIED`, `REJECTED`, `NOT_APPLIED` or `PENDING`, with optional `Retry-After` in seconds. Errors and unusable responses leave the outcome uncertain. The engagement manager owns durable idempotency by operation ID and validates the expected revision. A `NOT_APPLIED` response certifies that the attempt had no effect and is fenced against late execution. An absent record alone provides insufficient evidence.

The [process integration test](../src/test/java/io/github/luisfm44/caseware/worker/durable/DurableWorkerProcessIT.java) uses a shared HTTP test downstream. The regional deployment needs a compatible engagement manager to enforce these guarantees and own the real capacity pool. [Regional architecture](system-architecture.md)

## Scheduling across firms

Issue [#18](https://github.com/luisfm44/caseware-template-fanout-worker/issues/18). Each new reservation advances a shared cursor to the admitted firm in the PostgreSQL capacity row. With a slot available, the scheduler selects the next firm with eligible work in firm ID order, wrapping to the beginning as needed. It selects the oldest eligible job within that firm across its publications. Selection, reservation and cursor advancement commit together. This prevents a worker from claiming a fresh large-firm job ahead of capacity and then bypassing the next firm's turn.

With a fixed set of continuously eligible firms, each receives a reservation within one rotation. Two eligible firms alternate when both have ready work. A sole eligible firm can use all available capacity. Deferred retries participate when they become due. Reconciliation keeps the existing reservation and leaves the cursor in place. The guarantee is expressed in new reservations when slots become available. Independent HTTP calls can reach the downstream in a different order when several slots open together.

The [two-JVM integration test](../src/test/java/io/github/luisfm44/caseware/worker/durable/DurableWorkerProcessIT.java) fills a two-slot pool with large-firm work, adds a small firm and releases one slot. It checks that the next downstream start belongs to the small firm with 30 large-firm jobs still ready. It then verifies that the large firm resumes and that the measured peak stays at two. Apply the worker version to all replicas sharing the schema before relying on the rotation across the pool.

## Code quality · Issue [#8][issue-8]

JaCoCo combines coverage from the unit and PostgreSQL integration suites. SonarQube Community Build enforces the overall coverage floor on `main` and supports isolated manual verification. The default runner-local instance publishes the gate, metrics and findings as artifacts. An existing SonarQube instance can provide historical analysis through a configured project token. [Quality workflow and reproduction](code-quality.md)

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
[wiki]: https://github.com/luisfm44/caseware-template-fanout-worker/wiki
[admission-test]: ../src/test/java/io/github/luisfm44/caseware/worker/SharedAdmissionTest.java
[planning-test]: ../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationFanoutIT.java
[retry-test]: ../src/test/java/io/github/luisfm44/caseware/worker/retry/RetryingDownstreamClientTest.java
