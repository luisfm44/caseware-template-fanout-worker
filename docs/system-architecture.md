# Proposed regional system architecture

The proposal distributes template releases once per product and market, then resolves each auditor's update offer from verified metadata in the firm's home region. The Java fan-out worker is the background verification component in this design. The [Java worker](worker-architecture.md) exercises its planning, admission and recovery contracts locally with PostgreSQL and a controlled downstream. The following regional deployment design is proposed in [issue #20](https://github.com/luisfm44/caseware-template-fanout-worker/issues/20).

```mermaid
---
config:
  theme: neutral
  htmlLabels: false
  flowchart:
    rankSpacing: 26
    nodeSpacing: 20
    wrappingWidth: 205
    subGraphTitleMargin:
      top: 4
      bottom: 20
---
flowchart TB
    accTitle: Proposed regional system architecture
    accDescr: Shared template services publish release revisions to a regional catalog and replicate approved summaries to a regional evidence store. The regional updates API serves the auditor using authorized firm data. A regional exception planner enqueues SQS verification jobs for the Java worker, which submits and reconciles work through the engagement manager.

    subgraph SHARED["Shared template services"]
        direction LR
        RELEASE["Template service<br/>Release and outbox"]
        SUMMARY["Summary service<br/>Compare and archive"]
    end

    subgraph REGION["Firm home region"]
        direction TB
        CATALOG["Regional catalog"]
        EVIDENCE[("Regional evidence store")]
        API["Regional updates API"]
        AUDITOR["Auditor"]
        PLANNER["Exception planner"]
        QUEUE["Regional SQS"]
        WORKER["Java fan-out worker"]
        MANAGER["Engagement manager"]
        FIRM[("Firm database<br/>Index and receipts")]

        CATALOG --> API
        EVIDENCE --> API
        AUDITOR -->|"List, inspect, decide"| API
        API -->|"Read index and save receipts"| FIRM
        CATALOG --> PLANNER
        PLANNER -->|"Queue flagged work"| QUEUE
        QUEUE -->|"Deliver verification job"| WORKER
        WORKER -->|"Submit or query status"| MANAGER
        API -->|"Submit a decision"| MANAGER
        MANAGER -->|"Commit verified metadata"| FIRM
    end

    RELEASE -->|"Relay release revision"| CATALOG
    SUMMARY -->|"Replicate archive"| EVIDENCE

    classDef shared fill:#f6f8fa,stroke:#8c959f,color:#24292f
    classDef regional fill:#e6ffec,stroke:#2da44e,color:#24292f
    classDef background fill:#ddf4ff,stroke:#218bff,color:#24292f
    class RELEASE,SUMMARY shared
    class CATALOG,EVIDENCE,API,AUDITOR,MANAGER,FIRM regional
    class PLANNER,QUEUE,WORKER background
```

## Ownership and data flow

| Area | Proposed responsibility |
| --- | --- |
| Template service and regional catalog | Commit the release and outbox revision in one transaction. Regional consumers ignore older revisions and fetch a catalog snapshot on gaps. |
| Regional updates API | Derive firm and file access from the authenticated session. Use the active release, the verified effective version and prior decisions to determine which update to offer. Link each displayed summary to its archived text and a view receipt. |
| Summary and evidence | Compare exact approved template versions. Archive reviewed summary text and replicate it to regional evidence stores. The shared summary service uses template facts. |
| Engagement manager | Own file creation, opening and decisions. Enforce admission for the actual downstream pool, validate the expected engagement revision, deduplicate effects and provide authoritative status. The template authority orders decision admission against withdrawal. |
| Regional exception planner | Plan jobs for unknown or flagged baselines against the publication's pinned engagement snapshot. Commit each page and its outbox records in the same transaction. |
| Regional Java worker | Claim deliveries in a durable execution ledger, authorize submission attempts, reconcile uncertain outcomes and acknowledge SQS after committing a terminal result. |

## Dispatch by environment

| Environment | Dispatch | State and capacity |
| --- | --- | --- |
| Local exercise | PostgreSQL job table polled by the `postgres-worker` profile. | The same schema holds planning checkpoints, durable claims and the shared admission cursor and reservations. Two-JVM tests use a controlled HTTP downstream. |
| Proposed regional deployment | Amazon SQS Standard in each firm's home region, with a same-region dead-letter queue (DLQ). The worker long-polls SQS with bounded prefetch. | A regional PostgreSQL database holds the producer outbox and worker execution ledger. Use existing regional PostgreSQL or Amazon RDS for PostgreSQL if managed hosting is needed. The engagement manager owns the hard limit for its downstream pool. ECS on Fargate can run and replace worker replicas. |

The regional worker receives new work from SQS. Its ledger stores ownership and execution state. A recovery task scans the ledger for unresolved attempts, including work whose delivery reached the DLQ. It can resolve existing attempts independently of delivery. New attempts for DLQ work require redrive. The PostgreSQL polling path for fresh jobs belongs to the exercise. A regional SQS consumer and the real manager adapter are deployment work. [Amazon RDS for PostgreSQL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_PostgreSQL.html) · [Amazon ECS services](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs_services.html)

### Producer and delivery

1. The exception planner commits its snapshot ID, cursor and one outbox record per planned `OperationId` atomically. A relay publishes committed records and marks each sent only after SQS confirms it. After a rollback there is nothing to publish. If the relay loses the response or stops before recording the send, it republishes with the same operation ID. The consumer must tolerate duplicate deliveries and reordering.
2. Use an opaque firm identifier as the SQS `MessageGroupId`. Fair Queues can reduce the delay a busy firm causes for other firms. Per-firm ordering, a guaranteed next turn and strict quotas require application controls. The local PostgreSQL rotation is covered by a separate test.
3. Put only the operation key and the routing fields needed by the worker in the message. Workpapers, decisions, receipts, queues, logs, keys and backups stay in the firm's home region. The engagement manager's API remains the boundary for workpaper access.

[Transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html) · [SQS Standard queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues.html) · [SQS Fair Queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-fair-queues.html)

### Claim, admission and completion

1. On receipt, claim the `OperationId` in the regional ledger using an expiring claim and a token that guards writes. If a terminal result is already present, delete the delivery using its current receipt handle. If another claimant is active, defer the delivery. For a new submission, persist its attempt ID and reserve one authorization against the durable budget before sending.
2. The engagement manager atomically admits work against the shared limit and keeps the slot until authoritative completion or enforced cancellation. It durably ties the result to the operation key so duplicate requests can observe the same effect. If the pool is full, the manager can return a distinct, fenced capacity denial. Persist that denial, return the reserved authorization once for that attempt and defer the delivery. Uncertain submissions keep their budget reservation while the worker reconciles them. A worker lease, SQS visibility deadline and ECS task lifetime are delivery or ownership signals. Capacity follows the operation at the manager. All replicas sharing the same physical pool use the same authority, even if their deliveries are regional. **20 owner slots** remains a sizing assumption to validate.
3. After a timeout or expired claim, recover the same attempt and query the manager. An absent record alone leaves the outcome uncertain. The manager must confirm success, reject permanently, or prove that an earlier attempt had no effect and cannot execute late. A confirmed no-effect result permits a new attempt within budget. A fenced capacity denial also allows a later submission after deferral. Use the same operation ID and a distinct attempt ID for each new submission.
4. While the worker owns a delivery, extend visibility as needed within SQS limits. Commit the terminal result before calling `DeleteMessage`. A duplicate after commit can be acknowledged from that result. An unresolved attempt stays in the ledger for reconciliation after visibility expiry, task loss or DLQ movement. DLQ redrive uses the original operation ID and preserves its attempt history and budget. Retain idempotency and ledger state through the supported redrive and late-request window. Set visibility and the receive limit using the tested saturation and outage windows so normal recovery has time to complete before redrive is needed.

[SQS visibility timeout](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html) · [SQS dead-letter queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)

## Evidence required for regional readiness

Use a regional test queue, the real engagement manager and multiple worker tasks. Confirm both the manager's shared capacity limit and effect count under redelivery and failures. Verify the real source can reopen the pinned snapshot from a fresh process after the current data changes. The owner-side policy suites run in the regional updates API and summary service.

[Regional verification plan](regional-verification.md) · [Owner-side acceptance tests](owner-acceptance.md) · [Worker snapshot contract](worker-design.md#engagement-snapshots)

[System proposal and implementation appendix](architecture.pdf) · [Worker design decisions](worker-design.md) · [AWS cost model](aws-cost-model.xlsx)
