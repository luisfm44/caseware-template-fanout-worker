# Worker architecture

The Java 25 and Spring Boot 4.1.2 application plans publications into PostgreSQL. Independent JVMs running the `postgres-worker` profile consume jobs from the same schema and coordinate downstream capacity through PostgreSQL. The `demo` profile exercises planning and downstream recovery with two logical workers in a single JVM.

## Durable PostgreSQL runtime

```mermaid
---
config:
  theme: neutral
  htmlLabels: false
  flowchart:
    rankSpacing: 28
    nodeSpacing: 20
    wrappingWidth: 220
    subGraphTitleMargin:
      top: 4
      bottom: 18
---
flowchart TB
    accTitle: Durable PostgreSQL worker
    accDescr: PublicationFanout commits planned jobs and a pinned snapshot checkpoint to a shared PostgreSQL schema. Two independent JVMs claim work through the same capacity pool and firm cursor. Each uses the HTTP adapter to submit or resolve an attempt using the same operation and attempt IDs.

    PLANNER["PublicationFanout<br/>Read a bounded page from a pinned snapshot"]
    DB[("Shared PostgreSQL schema<br/>Jobs, checkpoints, leases, capacity and firm cursor")]

    subgraph WORKERS["Independent JVMs · postgres-worker"]
        direction LR
        FIRST["Worker JVM A<br/>DurableWorker and HTTP adapter"]
        SECOND["Worker JVM B<br/>DurableWorker and HTTP adapter"]
        FIRST ~~~ SECOND
    end

    DOWNSTREAM["Downstream HTTP contract<br/>OperationId and attemptId"]

    PLANNER -->|"Commit jobs and checkpoint together"| DB
    DB <-->|"Claim, defer or settle"| WORKERS
    WORKERS <-->|"Submit or resolve"| DOWNSTREAM

    classDef dependency fill:#f6f8fa,stroke:#8c959f,color:#24292f
    classDef planning fill:#e6ffec,stroke:#2da44e,color:#24292f
    classDef execution fill:#ddf4ff,stroke:#218bff,color:#24292f
    class DOWNSTREAM dependency
    class PLANNER,DB planning
    class FIRST,SECOND execution
```

1. [`DurableWorkerRunner`][durable-runner] starts the polling loop. All replicas in a pool share a PostgreSQL schema, capacity and downstream origin. The planner initializes the schema and inserts the jobs before workers consume them.
2. The polling loop alternates its preferred claim between due reconciliation and new admission after each successful claim. It tries the other kind when the preferred claim is unavailable. [`PostgresJobQueue`][queue] reclaims attempts whose claims expired or were deferred and are due again. For new work it locks the pool row, checks for an available slot, selects the next eligible firm, leases a job and records an authorized attempt. The reservation and firm cursor advance in the same transaction.
3. [`DurableWorker`][durable-worker] submits a new attempt through [`HttpAttemptTransport`][http-transport]. A reclaimed attempt goes to the status endpoint with its original operation and attempt IDs. A pending or uncertain response defers the next lookup only while the same token and attempt ID still match. The reservation stays with the attempt. A confirmed result settles the attempt and frees capacity.

Lease expiry allows another process to replace the claim token while the downstream operation still occupies its slot. Reclaimed status lookups get the HTTP deadline plus the configured claim margin, and the HTTP adapter bounds the full response. A confirmed result can settle while the token and attempt ID still match, including after lease expiry. Once the token is replaced, the old worker reports the lost claim and leaves settlement to the new claimant. The durable attempt budget counts authorizations, including a process exit before an HTTP call. On a transient PostgreSQL failure, the worker backs off and resumes from the persisted queue state. The regional deployment depends on an engagement manager that validates the engagement revision and owns authoritative status, idempotency and the regional capacity limit. [Durable worker design](worker-design.md#durable-consumption-and-shared-capacity) · [Proposed regional architecture](system-architecture.md)

## Package structure

`TemplateFanoutWorkerApplication` is in the root package. Spring Boot scans the profile runners below it.

| Package | Responsibility |
| --- | --- |
| `planning` | Plans publications and defines the engagement source and fan-out store contracts. |
| `execution.model`, `execution.admission` | Hold the operation identity and capacity reservation contracts. |
| `execution.service`, `execution.port`, `execution.retry` | Orchestrate execution, define job queue and downstream contracts, and implement retries. |
| `adapter.postgres`, `adapter.http`, `adapter.memory` | Implement JDBC planning and claims, the downstream HTTP adapter and the demo semaphore. |
| `bootstrap`, `demo` | Wire the `postgres-worker` profile and run the self-contained `demo` profile. |

`OperationId` is an immutable value object shared by planning and execution. The HTTP adapter maps its fields to the wire format. `Permit` represents a slot with an explicit release lifecycle.

## Components

| Component | Responsibility |
| --- | --- |
| [`OperationId`][operation] | Identifies work by firm, engagement, publication and engagement revision. The same logical work can reuse the ID across workers and attempts. |
| [`FanoutWorker`][worker] | Acquires a permit before dispatch, passes its operation ID to the downstream and closes the permit when execution reaches a confirmed terminal state. |
| [`AdmissionControl`][admission] and [`InMemoryAdmissionControl`][memory] | Define and implement atomic admission. The in-memory implementation uses a semaphore and returns an empty result immediately when every slot is reserved. |
| [`Permit`][permit] | Represents a reservation for one operation. Its first `close()` returns the slot to the pool. Repeated calls have no effect. |
| [`DownstreamClient`][downstream] | Dispatches an admitted operation and returns a `CompletionStage`. Success or failure on that stage means the downstream confirmed that execution ended or never started. |
| [`FakeDownstreamClient`][fake] | Test implementation that keeps operations active until the test finishes them explicitly. It records both the current and peak number of active operations. |
| [`SharedAdmissionTest`][test] | Creates two workers with the same dependencies, makes them compete for the last slot and verifies that the peak is three. It also verifies slot retention after a caller timeout. |
| [`PublicationFanout`][planner] and [`EngagementSource`][source] | Read bounded, stable pages of eligible engagements and produce job IDs. |
| [`PostgresFanoutStore`][store] | Persists each page's jobs and snapshot checkpoint in one transaction. |
| [`PostgresJobQueue`][queue] and [`JobQueue`][job-port] | Claim due attempts and reserve schema-wide capacity for fresh jobs in firm rotation. The queue compares the current token and attempt ID for both deferral and settlement. |
| [`HttpAttemptTransport`][http-transport] and [`AttemptTransport`][attempt-port] | Submit and resolve authorized attempts over HTTP using the stable operation ID and attempt ID. |
| [`RetryingDownstreamClient`][retry] and [`DownstreamTransport`][transport] | Apply the retry policy and submit or reconcile a downstream operation by ID. |
| [`TemplateFanoutWorkerApplication`][boot] | Starts Spring Boot 4.1.2 and discovers the runner for the selected profile. |
| [`DurableWorkerRunner`][durable-runner] and [`DurableWorker`][durable-worker] | The runner wires the durable profile. The worker uses the job queue and downstream contracts to dispatch and reconcile attempts. |
| [`DemoRunner`][demo] | Plans and reads the sample publication in PostgreSQL, runs two logical workers and writes the JSON report. |
| [`SimulatedDownstream`][simulator] | Controls when replies arrive, records effects by operation ID and loses one reply after applying its effect. |

## Demo operation lifecycle

1. A caller invokes `tryStart(operationId)`. If all slots are reserved, the worker returns `Optional.empty()` without dispatching anything. The caller can retry with the same ID.
2. If admitted, the worker calls `DownstreamClient.start` and registers `Permit.close()` on the terminal stage. A synchronous dispatch failure also closes the permit because the downstream contract guarantees that this call started no work.
3. The caller gets a separate `CompletableFuture`. A timeout or cancellation on that future ends the caller's wait while the downstream operation can continue to occupy its slot.
4. When the downstream confirms a terminal outcome, the worker closes the permit. The next attempt from either worker can claim the freed slot.

The timeout test starts three operations, times out the caller's future for one of them and checks that both workers are denied a fourth start. Once the fake finishes the original operation, the fourth can start and the peak stays at three.

The demo semaphore shares capacity within a single JVM. The durable profile uses the PostgreSQL pool described above.

## Durable publication planning

[`PublicationFanout`][planner] asks an [`EngagementSource`][source] for the first bounded page of eligible engagements and its snapshot ID. The planner converts each engagement to the same `OperationId` used for downstream execution. Subsequent requests send the snapshot ID and cursor read from the checkpoint.

[`PostgresFanoutStore`][store] commits the page in a single transaction.

1. Pin the first page's snapshot ID. For later pages, check that the stored snapshot, cursor and page count still match the checkpoint used to read the page.
2. Insert the jobs into `fanout_job`. Its primary key is the four fields of `OperationId`. Replaying an operation key preserves its execution state.
3. Commit the jobs and checkpoint together. The checkpoint stores the snapshot ID, next cursor and page count. A final page marks the checkpoint complete. A failed job insert rolls back the entire page.

If two planners read different initial snapshots, the first successful commit pins the snapshot. The other planner reloads the committed checkpoint and uses its snapshot and cursor on the next request. A fresh process resumes the same way after an interruption. An unavailable snapshot or a response for a different snapshot stops planning before the next commit. Replaying a completed publication returns immediately. [Snapshot contract and legacy checkpoints](worker-design.md#engagement-snapshots)

## Demo retries and reconciliation

[`RetryingDownstreamClient`][retry] implements `DownstreamClient` and uses the same `OperationId` for every submission and lookup. `FanoutWorker` keeps the permit reserved across dispatch, backoff and reconciliation until the terminal stage completes.

| Downstream outcome | Worker behavior |
| --- | --- |
| Applied | Complete successfully and release the permit. |
| Throttled or confirmed transient failure | Retry if the attempt had no effect and can no longer execute. Use exponential backoff with jitter and wait at least as long as the downstream's `Retry-After` hint. |
| Timeout or lost reply | Ask the [transport][transport] to reconcile the original operation by ID. Confirmed success completes the operation without another submission. A retry requires confirmation that the earlier attempt had no effect and can no longer execute. |
| Permanent rejection | Return a terminal failure and release the permit. |

Each execution allows three submissions, including the initial attempt. A confirmed rejection or exhausted retry budget produces a [`DownstreamFailure`][failure] with the reason, submission count and last submission outcome. An unresolved lookup keeps the terminal stage pending, so a caller timeout cannot free the slot or cause a second dispatch.

The transport must supply authoritative status and use the operation ID as its idempotency key. The controlled test transport records applied effects and can lose a reply after recording an effect. The worker then reconciles by ID, and the test verifies one submission and one effect. The retry counter is scoped to a worker execution.

### Recovering a lost reply

```mermaid
---
config:
  theme: neutral
  sequence:
    mirrorActors: false
---
sequenceDiagram
    participant D as DemoRunner
    participant W as FanoutWorker
    participant A as Shared admission
    participant C as RetryingDownstreamClient
    participant T as SimulatedDownstream

    D->>W: tryStart(OperationId)
    W->>A: Reserve one permit
    A-->>W: Permit
    W->>C: Start with OperationId
    C->>T: Submit OperationId
    W-->>D: Caller future
    D->>T: Complete the pending batch
    Note over T: Record effect by ID
    T-->>C: Reply lost after effect
    C->>T: Reconcile the same OperationId
    T-->>C: APPLIED
    C-->>W: Confirmed terminal success
    W->>A: Close the permit
    W-->>D: Complete the caller future
```

The status lookup completes the original operation. If the lookup cannot confirm the outcome, the worker keeps the slot reserved until the downstream reports a terminal state.

## Executable demo

1. The runner creates a disposable PostgreSQL schema. It plans the first page of a seven-engagement publication and creates a fresh planner to resume from its committed checkpoint.
2. After planning finishes in four pages, the runner reads the stored job IDs. It alternates submissions between two workers until their shared pool of three slots is full.
3. The simulator completes a batch and applies each effect once. For one job it loses the reply after applying the effect. The client confirms the result by operation ID without resubmitting.
4. The runner checks the report totals, removes the demo schema and writes the JSON report. [Run the demo](../README.md#postgresql-integration-tests-and-executable-demo)

## Verification

Run the process suite with JDK 25 and PostgreSQL started as described in [Run locally](../README.md#postgresql-integration-tests-and-executable-demo).

```sh
./mvnw -Ppostgres-integration -Dit.test=DurableWorkerProcessIT verify
```

[`DurableWorkerProcessIT`][process-test] launches independent `postgres-worker` JVMs with distinct PIDs in the cross-process scenarios. They share a disposable PostgreSQL schema and a controlled loopback HTTP server. The server records submissions, resolutions, effects and peak active operations. The suite also queries the persisted job state.

| Scenario | Observed outcome |
| --- | --- |
| Process exit with an uncertain attempt | A second JVM reclaims the original attempt. The first JVM is forcibly stopped. During the status outage, the existing attempt remains reserved and the next job waits. When status becomes available, both jobs finish with one effect each and a peak of one active operation. |
| Confirmed result after lease expiry | With two JVMs running, the first JVM records a late confirmation while it still holds the stored token. The next job runs, each operation has one effect, and the peak is one. |
| Confirmed result after claim replacement | The first JVM receives a late confirmation after the second JVM replaces the token. Its update changes zero rows. The next job waits until the second JVM settles the same attempt. Each operation has one effect and the peak is one. |
| Fenced delayed request | The downstream confirms no effect and fences the old attempt. It rejects the delayed request when delivered. The surviving JVM retries with a different attempt ID under the same operation ID. The final effect count is one per operation. |
| Stale engagement revision | The controlled downstream rejects the stale operation without an effect. The worker records the terminal rejection. |
| Small firm joins a large ready backlog | Two JVMs fill a pool of two. The small firm receives the next free slot while 30 large-firm jobs remain ready. The large firm resumes after that turn and the measured peak stays at two. |
| Small firm joins during slow reconciliation | The small firm uses the fifth slot and reaches `APPLIED` with one effect while four original large-firm attempts stay reserved. The measured peak is five. |

### Process loss test sequence

```mermaid
---
config:
  theme: neutral
  sequence:
    mirrorActors: false
---
sequenceDiagram
    participant T as Test harness
    participant A as Worker JVM A
    participant B as Worker JVM B
    participant P as Shared PostgreSQL
    participant H as Controlled HTTP downstream

    T->>P: Seed two ready jobs and set limit to one
    A->>P: Claim job 1 and reserve the only slot
    A->>H: Submit original attempt
    Note over A,H: Hold the reply
    B->>P: Reclaim the expired lease
    B->>H: Resolve the same attempt
    T->>H: Make status unavailable
    T->>A: Forcibly stop the process
    T->>H: Apply job 1 and drop the reply
    B->>H: Retry status lookup
    Note over B,P: Job 1 stays submitted and job 2 waits
    T->>H: Restore authoritative status
    B->>H: Resolve job 1 as applied
    B->>P: Settle job 1 and claim job 2
    B->>H: Submit job 2
    B->>P: Record job 2 as applied
    Note over B,H: Both jobs applied, one effect each, peak of one
```

### Late confirmation test sequence

These two scenarios keep worker A alive and hold its original HTTP reply until the lease expires. PostgreSQL settles a confirmed outcome only if the stored claim token and attempt ID match the worker's update.

```mermaid
---
config:
  theme: neutral
  sequence:
    mirrorActors: false
---
sequenceDiagram
    participant A as Worker JVM A
    participant B as Worker JVM B
    participant P as Shared PostgreSQL
    participant H as Controlled HTTP downstream

    A->>P: Claim job 1 and reserve the only slot
    A->>H: Submit the original attempt
    Note over A,P: The lease expires with the reply held
    alt Original token still stored
        H-->>A: Return confirmed APPLIED for the original attempt
        A->>P: Settle with the original token and attempt ID
        P-->>A: Update one row and free the slot
    else B has replaced the token
        B->>P: Reclaim the same reserved attempt
        B->>H: Resolve the original attempt
        H-->>A: Return confirmed APPLIED for the original attempt
        A->>P: Try to settle with the replaced token
        P-->>A: Update zero rows and keep the slot reserved
        Note over A,P: A logs the discarded outcome
        H-->>B: Return confirmed APPLIED for the original attempt
        B->>P: Settle with the new token and original attempt ID
        P-->>B: Update one row and free the slot
    end
    Note over B,H: Job 2 can start, one effect per operation, peak of one
```

### Mixed admission and reconciliation test sequence

The test holds both JVMs in status lookups while four large-firm attempts reserve slots in a pool of five. After adding the small firm's job, the fixture releases one pending reply. Two other reconciliations are still due. The next downstream request submits the small firm's job.

```mermaid
---
config:
  theme: neutral
  sequence:
    mirrorActors: false
---
sequenceDiagram
    participant A as Worker JVM A
    participant B as Worker JVM B
    participant P as Shared PostgreSQL
    participant H as Controlled HTTP downstream

    Note over P,H: Four large-firm attempts are SUBMITTED. The fifth slot is free.
    par Worker A reconciles
        A->>P: Claim a due large-firm attempt
        A->>H: Resolve the original attempt
    and Worker B reconciles
        B->>P: Claim another due large-firm attempt
        B->>H: Resolve the original attempt
    end
    Note over P: Small-firm job becomes READY
    H-->>A: Return PENDING after a delay
    A->>P: Defer the lookup and keep its reservation
    A->>P: Try fresh admission before the next reconciliation
    P-->>A: Reserve the free slot for the small firm
    A->>H: Submit the small-firm attempt
    H-->>A: Return APPLIED after one effect
    A->>P: Settle the small-firm attempt and release its slot
    Note over P,H: Four original reservations are still active. The measured peak is five.
```

- [`SharedAdmissionTest`][test] measures the downstream concurrency peak and verifies permit retention after a caller timeout.
- [`PublicationFanoutIT`][planning-test] runs against PostgreSQL and checks resume, replay, rollback and stale checkpoints.
- [`PublicationSnapshotIT`][snapshot-test] checks that a fresh JVM resumes the same source snapshot after the live source changes. It also checks that an unavailable or mismatched snapshot leaves the checkpoint and committed jobs intact.
- [`RetryingDownstreamClientTest`][retry-test] checks the single-effect recovery, bounded retries and rejection paths with controlled responses.
- The [manual demo workflow](../.github/workflows/demo.yml) builds and runs the packaged JAR against PostgreSQL, checks the report and uploads it as a workflow artifact.
- The [SonarQube quality job](code-quality.md) verifies zero missed JaCoCo lines and branches, imports the combined unit and PostgreSQL coverage, and enforces the 100% overall gate. GitHub Actions publishes both reports on PRs to `main`, on pushes to `main` and on manual runs.

[Verified CI runs and reports](evidence/README.md) · [Worker design decisions](worker-design.md)

[operation]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/model/OperationId.java
[worker]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/service/FanoutWorker.java
[admission]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/admission/AdmissionControl.java
[memory]: ../src/main/java/io/github/luisfm44/caseware/worker/adapter/memory/InMemoryAdmissionControl.java
[permit]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/admission/Permit.java
[downstream]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/port/DownstreamClient.java
[fake]: ../src/test/java/io/github/luisfm44/caseware/worker/execution/admission/FakeDownstreamClient.java
[test]: ../src/test/java/io/github/luisfm44/caseware/worker/execution/admission/SharedAdmissionTest.java
[boot]: ../src/main/java/io/github/luisfm44/caseware/worker/TemplateFanoutWorkerApplication.java
[planner]: ../src/main/java/io/github/luisfm44/caseware/worker/planning/PublicationFanout.java
[source]: ../src/main/java/io/github/luisfm44/caseware/worker/planning/EngagementSource.java
[store]: ../src/main/java/io/github/luisfm44/caseware/worker/adapter/postgres/PostgresFanoutStore.java
[retry]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/retry/RetryingDownstreamClient.java
[transport]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/port/DownstreamTransport.java
[failure]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/retry/DownstreamFailure.java
[planning-test]: ../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationFanoutIT.java
[retry-test]: ../src/test/java/io/github/luisfm44/caseware/worker/execution/retry/RetryingDownstreamClientTest.java
[durable-runner]: ../src/main/java/io/github/luisfm44/caseware/worker/bootstrap/DurableWorkerRunner.java
[durable-worker]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/service/DurableWorker.java
[demo]: ../src/main/java/io/github/luisfm44/caseware/worker/demo/DemoRunner.java
[simulator]: ../src/main/java/io/github/luisfm44/caseware/worker/demo/SimulatedDownstream.java
[queue]: ../src/main/java/io/github/luisfm44/caseware/worker/adapter/postgres/PostgresJobQueue.java
[job-port]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/port/JobQueue.java
[http-transport]: ../src/main/java/io/github/luisfm44/caseware/worker/adapter/http/HttpAttemptTransport.java
[attempt-port]: ../src/main/java/io/github/luisfm44/caseware/worker/execution/port/AttemptTransport.java
[snapshot-test]: ../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationSnapshotIT.java
[process-test]: ../src/test/java/io/github/luisfm44/caseware/worker/bootstrap/DurableWorkerProcessIT.java
