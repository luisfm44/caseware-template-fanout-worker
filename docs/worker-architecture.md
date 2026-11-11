# Worker architecture

The implementation has a planning path and an execution path. Both use `OperationId` for the same engagement work. Planning records the jobs in PostgreSQL. Execution starts when a caller supplies a job's ID to the worker.

```mermaid
---
config:
  theme: neutral
  htmlLabels: false
  flowchart:
    rankSpacing: 32
    nodeSpacing: 28
    wrappingWidth: 240
---
flowchart TB
    accTitle: Caseware worker architecture
    accDescr: Publication planning records operation IDs and checkpoints in PostgreSQL. Separately, a caller supplies an operation ID to a worker that acquires a permit and submits or reconciles that operation through the downstream transport.

    KEY["OperationId<br/>Shared job and execution identity"]

    subgraph PLAN["Publication planning"]
        direction TB
        PLANNER["PublicationFanout"]
        SOURCE["EngagementSource<br/>Eligible pages"]
        DB[("PostgreSQL<br/>Jobs and checkpoint")]
        PLANNER -->|"Read page"| SOURCE
        PLANNER -->|"Commit page"| DB
    end

    subgraph EXEC["Worker execution"]
        direction TB
        WORKER["FanoutWorker"]
        ADMISSION["AdmissionController<br/>Shared within one JVM"]
        RETRY["RetryingDownstreamClient"]
        TRANSPORT["DownstreamTransport<br/>Simulated in tests"]
        WORKER -->|"Reserve slot"| ADMISSION
        WORKER -->|"Start operation"| RETRY
        RETRY -->|"Submit or reconcile"| TRANSPORT
    end

    KEY -.->|"Job key"| PLAN
    KEY -.->|"Caller supplies ID"| EXEC

    classDef identity fill:#f6f8fa,stroke:#8c959f,color:#24292f
    classDef planning fill:#e6ffec,stroke:#2da44e,color:#24292f
    classDef execution fill:#ddf4ff,stroke:#218bff,color:#24292f
    class KEY identity
    class PLANNER,SOURCE,DB planning
    class WORKER,ADMISSION,RETRY,TRANSPORT execution
```

The dotted lines show where the identity is used. The Spring Boot `demo` profile joins the two paths for a bounded sample by reading the persisted IDs and passing them to the workers. Automatic job claiming and a production transport adapter are deployment work.

## Components

| Component | Responsibility |
| --- | --- |
| [`OperationId`][operation] | Identifies work by firm, engagement, publication and engagement revision. The same logical work can reuse the ID across workers and attempts. |
| [`FanoutWorker`][worker] | Acquires a permit before dispatch, passes its operation ID to the downstream and closes the permit when execution reaches a confirmed terminal state. |
| [`AdmissionController`][admission] and [`InMemoryAdmissionController`][memory] | Define and implement atomic admission. The in-memory implementation uses a semaphore and returns an empty result immediately when every slot is reserved. |
| [`Permit`][permit] | Represents a reservation for one operation. Its first `close()` returns the slot to the pool. Repeated calls have no effect. |
| [`DownstreamClient`][downstream] | Dispatches an admitted operation and returns a `CompletionStage`. Success or failure on that stage means the downstream confirmed that execution ended or never started. |
| [`FakeDownstreamClient`][fake] | Test implementation that keeps operations active until the test finishes them explicitly. It records both the current and peak number of active operations. |
| [`SharedAdmissionTest`][test] | Creates two workers with the same dependencies, makes them compete for the last slot and verifies that the peak is three. It also verifies slot retention after a caller timeout. |
| [`PublicationFanout`][planner] and [`EngagementSource`][source] | Read bounded, stable pages of eligible engagements and produce job IDs. |
| [`PostgresFanoutStore`][store] | Persists each page's jobs and checkpoint in one transaction. |
| [`RetryingDownstreamClient`][retry] and [`DownstreamTransport`][transport] | Apply the retry policy and submit or reconcile a downstream operation by ID. |
| [`TemplateFanoutWorkerApplication`][boot] | Starts Spring Boot 4.1.2 and activates the demo runner when the `demo` profile is selected. |
| [`DemoRunner`][demo] | Plans and reads the sample publication in PostgreSQL, runs two logical workers and writes the JSON report. |
| [`SimulatedDownstream`][simulator] | Controls when replies arrive, records effects by operation ID and loses one reply after applying its effect. |

## Operation lifecycle

1. A caller invokes `tryStart(operationId)`. If all slots are reserved, the worker returns `Optional.empty()` without dispatching anything. The caller can retry with the same ID.
2. If admitted, the worker calls `DownstreamClient.start` and registers `Permit.close()` on the terminal stage. A synchronous dispatch failure also closes the permit because the downstream contract guarantees that this call started no work.
3. The caller gets a separate `CompletableFuture`. A timeout or cancellation on that future ends the caller's wait while the downstream operation can continue to occupy its slot.
4. When the downstream confirms a terminal outcome, the worker closes the permit. The next attempt from either worker can claim the freed slot.

The timeout test starts three operations, times out the caller's future for one of them and checks that both workers are denied a fourth start. Once the fake finishes the original operation, the fourth can start and the peak stays at three.

The semaphore shares capacity within a single JVM. A deployment with separate worker processes needs a shared admission authority. A real downstream client must reconcile an ambiguous transport timeout by operation ID before reporting a terminal outcome. See the [system architecture](architecture.pdf) for the broader design.

## Durable publication planning

[`PublicationFanout`][planner] asks an [`EngagementSource`][source] for one bounded page of eligible engagements at a time. The source keeps each publication's membership, revisions and cursor order stable across restarts. The planner converts each engagement to the same `OperationId` used for downstream execution.

[`PostgresFanoutStore`][store] commits the page in a single transaction.

1. Check that the publication's stored cursor and page count still match the ones used to read the page. Stage the next cursor, or mark planning complete for the final page.
2. Insert the jobs into `fanout_job`. Its primary key is the four fields of `OperationId`. A repeated key leaves the existing job intact.
3. Commit the jobs and the update to `fanout_checkpoint` together. If any job insert fails, PostgreSQL rolls back the entire page. If another planner moved the checkpoint, the caller reads it again before planning another page.

After an interruption, a new planner resumes from the last committed cursor. Replaying a fully planned publication returns immediately. Planning records jobs for downstream execution, which continues to use the separate admission and worker contracts above.

## Downstream retries and reconciliation

[`RetryingDownstreamClient`][retry] implements `DownstreamClient`. It holds the worker's permit across the initial dispatch, any backoff and any reconciliation. Every submission and lookup uses the same `OperationId`.

| Downstream outcome | Worker behavior |
| --- | --- |
| Applied | Complete successfully and release the permit. |
| Throttled or confirmed transient failure | Retry if the attempt had no effect and can no longer execute. Use exponential backoff with jitter and wait at least as long as the downstream's `Retry-After` hint. |
| Timeout or lost reply | Ask the [transport][transport] to reconcile the original operation by ID. Confirmed success completes the operation without another submission. A retry requires confirmation that the earlier attempt had no effect and can no longer execute. |
| Permanent rejection | Return a terminal failure and release the permit. |

Each execution allows three submissions, including the initial attempt. A confirmed rejection or exhausted retry budget produces a [`DownstreamFailure`][failure] with the reason, submission count and last submission outcome. An unresolved lookup keeps the terminal stage pending, so a caller timeout cannot free the slot or cause a second dispatch.

The transport must supply authoritative status and use the operation ID as its idempotency key. The controlled test transport records applied effects and can lose a reply after recording an effect. The worker then reconciles by ID, and the test verifies one submission and one effect. The retry counter is scoped to a worker execution.

## Executable demo

1. The runner creates a disposable PostgreSQL schema. It plans the first page of a seven-engagement publication and creates a fresh planner to resume from its committed checkpoint.
2. After planning finishes in four pages, the runner reads the stored job IDs. It alternates submissions between two workers until their shared pool of three slots is full.
3. The simulator completes a batch and applies each effect once. For one job it loses the reply after applying the effect. The client confirms the result by operation ID without resubmitting.
4. The runner checks the report totals, removes the demo schema and writes the JSON report. [Run the demo](../README.md#postgresql-integration-tests-and-executable-demo)

## Verification

- [`SharedAdmissionTest`][test] measures the downstream concurrency peak and verifies permit retention after a caller timeout.
- [`PublicationFanoutIT`][planning-test] runs against PostgreSQL and checks resume, replay, rollback and stale checkpoints.
- [`RetryingDownstreamClientTest`][retry-test] checks the single-effect recovery, bounded retries and rejection paths with controlled responses.
- The [manual demo workflow](../.github/workflows/demo.yml) builds and runs the packaged JAR against PostgreSQL, checks the report and uploads it as a workflow artifact.
- The [SonarQube quality job](code-quality.md) verifies zero missed JaCoCo lines and branches, imports the combined unit and PostgreSQL coverage, and enforces the 100% overall gate. GitHub Actions publishes both reports on pushes to `main` and on manual runs.

See the [worker design decisions](worker-design.md) for the rationale and how the current implementation maps to the system proposal.

[operation]: ../src/main/java/io/github/luisfm44/caseware/worker/OperationId.java
[worker]: ../src/main/java/io/github/luisfm44/caseware/worker/FanoutWorker.java
[admission]: ../src/main/java/io/github/luisfm44/caseware/worker/AdmissionController.java
[memory]: ../src/main/java/io/github/luisfm44/caseware/worker/InMemoryAdmissionController.java
[permit]: ../src/main/java/io/github/luisfm44/caseware/worker/Permit.java
[downstream]: ../src/main/java/io/github/luisfm44/caseware/worker/DownstreamClient.java
[fake]: ../src/test/java/io/github/luisfm44/caseware/worker/FakeDownstreamClient.java
[test]: ../src/test/java/io/github/luisfm44/caseware/worker/SharedAdmissionTest.java
[boot]: ../src/main/java/io/github/luisfm44/caseware/worker/TemplateFanoutWorkerApplication.java
[planner]: ../src/main/java/io/github/luisfm44/caseware/worker/planning/PublicationFanout.java
[source]: ../src/main/java/io/github/luisfm44/caseware/worker/planning/EngagementSource.java
[store]: ../src/main/java/io/github/luisfm44/caseware/worker/planning/PostgresFanoutStore.java
[retry]: ../src/main/java/io/github/luisfm44/caseware/worker/retry/RetryingDownstreamClient.java
[transport]: ../src/main/java/io/github/luisfm44/caseware/worker/retry/DownstreamTransport.java
[failure]: ../src/main/java/io/github/luisfm44/caseware/worker/retry/DownstreamFailure.java
[planning-test]: ../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationFanoutIT.java
[retry-test]: ../src/test/java/io/github/luisfm44/caseware/worker/retry/RetryingDownstreamClientTest.java
[demo]: ../src/main/java/io/github/luisfm44/caseware/worker/demo/DemoRunner.java
[simulator]: ../src/main/java/io/github/luisfm44/caseware/worker/demo/SimulatedDownstream.java
