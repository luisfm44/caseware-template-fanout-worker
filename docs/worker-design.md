# Worker design decisions

The repository implements the worker contracts for admission, durable planning, and downstream recovery. The [system design and implementation appendix](architecture.pdf) place these contracts within the wider Caseware proposal. The [worker architecture](worker-architecture.md) traces the code paths and their boundaries.

## Shared admission · Issue [#1][issue-1]

Two logical workers acquire permits from one in-memory semaphore. A caller can time out or cancel its own future while the worker retains the permit until the downstream reaches a confirmed terminal state. A full pool returns immediately so the caller can decide when to try again. The tests verify a peak of three operations across both workers and prove that a caller timeout leaves the occupied slot reserved. [Shared admission tests][admission-test]

The semaphore coordinates workers in a single JVM. Separate worker processes would need a shared admission authority. The three-slot test configuration demonstrates the invariant with a small workload.

## Durable planning · Issue [#2][issue-2]

The planner converts each bounded page of eligible engagements into jobs keyed by `OperationId`, which combines firm, engagement, publication, and engagement revision. PostgreSQL commits the page's jobs and checkpoint together. A checkpoint comparison rejects a stale writer, and the job key makes page replay safe. The engagement source must preserve membership, revision, order, and cursor meaning for the life of a publication. [PostgreSQL integration tests][planning-test]

`PostgresFanoutStore` uses JDBC directly. The SQL expresses the checkpoint comparison and unique job key, and the store commits or rolls back the page explicitly. The current schema has two tables. An ORM such as Hibernate would provide entity mapping if the model grows to include more relationships and entity lifecycles.

A fresh planner resumes from the last committed cursor after interruption. The transaction boundary covers planning data. The deployment still needs a consumer to claim persisted jobs and pass their operation IDs to the execution path.

## Downstream reconciliation · Issue [#3][issue-3]

The retrying client carries the same operation ID through every submission and status lookup. After a lost reply, confirmed success completes the original execution. A new submission requires confirmation that the previous one had no effect and can no longer run. The client returns a typed failure after a permanent rejection or when the last allowed submission is confirmed to have no effect. [Retry and reconciliation tests][retry-test]

The three-submission budget applies to one worker execution and is held in memory. The worker retains its permit during backoff and unresolved reconciliation, which can reduce throughput when status is unavailable. A live downstream adapter must enforce idempotency and supply authoritative status. The current tests use a controlled transport that can record an effect, lose the reply, and confirm that only one effect occurred.

## Executable demo · Issue [#4][issue-4]

The Spring Boot `demo` profile connects the planning and execution paths in one bounded run. It uses PostgreSQL in a disposable schema, commits the first page of a seven-engagement publication, and creates a fresh planner to finish from the persisted cursor. The demo reads the resulting job IDs and passes them to two logical workers that share three admission slots.

The simulator holds submissions under test control so the demo can measure overlap. For one operation it applies the effect and drops the reply. The worker uses the same key to reconcile the applied effect, and the demo reports dispatch attempts and lookups alongside the effect count and observed peak. The run removes its temporary schema. [Run the demo](../README.md#postgresql-integration-tests-and-executable-demo) · [Smoke workflow](../.github/workflows/demo.yml)

## Deployment and follow-up

The demo reads the jobs for one publication and dispatches them in one process. A production deployment needs a durable consumer to claim jobs, a live downstream adapter with authoritative status and durable idempotency, and shared admission for multiple worker processes. The broader proposal also covers regional ownership and data residency. [System design](architecture.pdf)

## Code quality · Issue [#8][issue-8]

JaCoCo combines coverage from the unit and PostgreSQL integration suites. SonarQube Community Build enforces the overall coverage floor on `main` and supports isolated manual verification. The default runner-local instance publishes the gate, metrics and findings as artifacts. An existing SonarQube instance can provide historical analysis through a configured project token. [Quality workflow and reproduction](code-quality.md)

The [GitHub Wiki][issue-9] remains a follow-up for reviewer and contributor navigation.

When an issue changes a contract, component boundary, or verification step, update the affected design decision and architecture section in the same pull request. Link the issue and the test or workflow that demonstrates the change. Update the system design appendix when the change affects the relationship between the implementation and the proposed deployment.

[issue-1]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/1
[issue-2]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/2
[issue-3]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/3
[issue-4]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/4
[issue-8]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/8
[issue-9]: https://github.com/luisfm44/caseware-template-fanout-worker/issues/9
[admission-test]: ../src/test/java/io/github/luisfm44/caseware/worker/SharedAdmissionTest.java
[planning-test]: ../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationFanoutIT.java
[retry-test]: ../src/test/java/io/github/luisfm44/caseware/worker/retry/RetryingDownstreamClientTest.java
