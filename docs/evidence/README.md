# Caseware feedback and test evidence

## Recorded baseline

The [CI run](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43142807632) for commit `362ef1c` on `main` passed all six jobs. The downloaded Surefire and Failsafe results record **118 passing tests** with zero failures, errors or skips.

| CI evidence | Verified result | Open the report |
| --- | --- | --- |
| Worker build and tests | **48 unit tests and 31 PostgreSQL integration tests**. Includes six worker process scenarios, four snapshot scenarios and the executable demo entry point. | [Test reports and worker JAR](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43142807632/artifacts/13910411563) |
| Worker quality | **SonarQube Quality Gate passed**. JaCoCo line and branch coverage and SonarQube overall worker coverage are 100%. | [Coverage and SonarQube reports](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43142807632/artifacts/13910676292) |
| Regional version selection | **21 HTTP scenarios** with a controlled catalog and engagement manager. Includes stale offer receipts, scope and idempotent replay. | [HTML and Surefire XML](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43142807632/artifacts/13910591752) |
| Summary validation | **15 HTTP scenarios** with approved template fixtures and scripted grouping. | [HTML and Surefire XML](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43142807632/artifacts/13910501976) |
| Connected reference APIs | **3 HTTP scenarios** with a test driver forwarding the offered pair to summary validation. | [HTML and Surefire XML](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43142807632/artifacts/13910556286) |

Find the worker XML under `surefire-reports/` and `failsafe-reports/`. The quality archive contains `site/jacoco/index.html` and `sonar-quality/summary.md`. For any of the three HTTP suites, extract the archive and open `evidence/index.html` for the scenarios, fixtures and captured responses. GitHub applies the workflow retention periods to these downloads. Use a newer run or the local commands below when an archive expires. [Verify and scan history](https://github.com/luisfm44/caseware-template-fanout-worker/actions/workflows/ci.yml) · [Artifact retention](../pipelines.md)

The previous baseline at `b80c148` recorded 105 tests before the late-lease, offer-receipt and connected-flow changes. The #26 screenshots show the original 13 regional scenarios. [Historical CI run](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43134129499) · [Historical screenshots](issue-26/README.md)

## Follow-up regressions

The current suite adds slow-response and stale-deferral coverage for [#44](https://github.com/luisfm44/caseware-template-fanout-worker/issues/44), database outage recovery for [#45](https://github.com/luisfm44/caseware-template-fanout-worker/issues/45) and receipt isolation across mock instances for [#46](https://github.com/luisfm44/caseware-template-fanout-worker/issues/46). The worker process suite also checks that a small firm can use spare capacity while two JVMs are busy with slow pending lookups. The latest successful main run has the current reports and SonarQube gate result. [Main CI runs](https://github.com/luisfm44/caseware-template-fanout-worker/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)

## Feedback, implementation and acceptance

| Caseware feedback | Change and evidence in this repository | Remaining validation |
| --- | --- | --- |
| Version rules and summary checks lacked automated tests | The regional reference API tests the exact version pair, decline scope, withdrawal, confirmation and firm isolation. The summary reference API tests the approved bytes, net changes, grouping, fallback, editorial review and original receipt. The connected suite forwards the offered pair to summary validation over HTTP. [Version-selection PR #29](https://github.com/luisfm44/caseware-template-fanout-worker/pull/29) · [Summary-validation PR #31](https://github.com/luisfm44/caseware-template-fanout-worker/pull/31) · [Connected test](../../examples/connected-reference-apis/README.md) | For a regional deployment, run equivalent suites with the actual service dependencies. [Regional acceptance](../owner-acceptance.md) |
| Capacity was shared only within one process | A single PostgreSQL pool coordinates workers in independent JVMs. In the recovery test the configured limit and measured peak are both **1**. An unresolved attempt holds the slot even while the original JVM is down. [Shared admission PR #21](https://github.com/luisfm44/caseware-template-fanout-worker/pull/21) | Regional integration verifies the engagement manager's shared admission and authoritative outcomes. [Regional verification](../regional-verification.md) |
| The code lacked scheduling across firms | Durable claims rotate among eligible firms. A small firm takes the next free slot while **30** large-firm jobs are still ready and the measured peak stays at **2**. A separate case verifies that it can also complete during slow pending reconciliation, with its own single effect and the large firm's reservations intact. [Firm scheduling PR #22](https://github.com/luisfm44/caseware-template-fanout-worker/pull/22) | The proposed SQS Fair Queues deployment needs its own regional dwell-time evidence. [Regional verification](../regional-verification.md) |
| The job consumer was missing | The `postgres-worker` profile polls and leases persisted jobs. A survivor reclaims an expired lease, reconciles the original attempt and records **one applied effect per operation** in the controlled downstream. [Durable worker PR #21](https://github.com/luisfm44/caseware-template-fanout-worker/pull/21) | Implement and verify regional SQS delivery, the downstream adapter to the owning engagement manager and acknowledgement after durable completion. [Regional verification](../regional-verification.md) |
| Follow-up on a changing eligibility source | Planning persists the snapshot ID and cursor with each committed page. A fresh JVM resumes against the pinned source even after the fixture publishes a different snapshot. Expired or mismatched snapshots halt planning. [Snapshot PR #25](https://github.com/luisfm44/caseware-template-fanout-worker/pull/25) | The regional source must provide the stable snapshot and retention contract. [Worker design](../worker-design.md) |

## Reproduce the PostgreSQL tests

Run with JDK 25 and Docker Compose from the repository root.

```sh
export FANOUT_DB_PASSWORD='your-local-password'
docker compose up -d --wait
./mvnw -Ppostgres-integration -DfailIfNoTests=true verify
./mvnw -Ppostgres-integration -Dit.test=DurableWorkerProcessIT,PublicationSnapshotIT verify
docker compose down
```

The full profile runs the unit and PostgreSQL integration suites. The final Maven command narrows the integration suite to the cross-process worker and pinned-snapshot tests. Read `target/failsafe-reports/` after the chosen run.

| Scenario | How the test controls the system | What the test asserts |
| --- | --- | --- |
| Process loss and ambiguous outcome | Seed two jobs. Start worker A and B with distinct PIDs, one PostgreSQL schema, a global limit of one and a controlled HTTP downstream. B takes over the expired lease while A is still waiting for a reply. Disable status lookups, kill A and let the first request apply without a reply. | Once status returns, B reconciles the original attempt. The second job waits until the outcome is terminal, both jobs succeed, each operation has one effect and the peak is one. |
| Confirmation after expiry with the same token | Expire A's lease and delay reconciliation eligibility while the HTTP response is held. Release A's confirmed reply with both JVMs running. | A settles the original attempt and the next job starts without a reconciliation. Both jobs succeed with one effect each and a peak of one. |
| Confirmation after the token changes | Expire A's lease, let B reclaim the attempt and hold B's status reply. Release A's confirmed reply before B's. | A logs the stale settlement and leaves the job and slot reserved. B settles the same attempt and the next job starts. Both jobs succeed with one effect each and a peak of one. |
| Fencing and stale revisions | Delay one request until the survivor obtains a definitive fence, then deliver the old attempt. In a separate scenario change the downstream's current engagement revision. | The delayed attempt is rejected and the new attempt can apply once. The stale engagement revision is rejected without an effect. |
| Firm scheduling with a ready backlog | Fill two slots with a large firm's jobs across two JVMs. Add a small firm's job, then release one slot. | The next claim goes to the small firm with 30 large-firm jobs still ready. The downstream peak stays at two. |
| Firm scheduling during slow reconciliation | Reserve four of five slots for the large firm and hold both JVMs in status lookups. Add a small-firm job and release one pending reply while two other reconciliations remain due. | The next downstream request submits the small-firm job. It reaches `APPLIED` with one effect. All four original reservations retain their attempt IDs and the measured peak is five. |
| Slow status lookup and late deferral | Hold one lookup across several nominal claim durations with both worker JVMs alive. Test deferred writes separately with expired, replaced, and mismatched claim identities. | The healthy lookup retains authority, the next job advances, each operation has one effect, and the peak is one. Deferral preserves its delay only for the matching claim and attempt. |
| PostgreSQL connection loss during settlement | Record a downstream effect, interrupt worker connections to PostgreSQL and release the confirmation. Restore connectivity after observing the durable reservation. | The existing workers resume from durable state, reconcile the original attempt, and complete the next job with one effect per operation and a peak of one. |
| Snapshot resume | Commit page one, change the HTTP source's current snapshot, then start a separate planner JVM. Exercise expiration, mismatch and legacy checkpoint handling separately. | The new process reads the stored snapshot and cursor, keeps the original job set and refuses an unsafe resume. |

[Durable worker process test](../../src/test/java/io/github/luisfm44/caseware/worker/bootstrap/DurableWorkerProcessIT.java) · [Snapshot test](../../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationSnapshotIT.java) · [Process topology and architecture](../worker-architecture.md)

The [manual two-JVM demo](../../README.md#run-two-durable-workers-locally) seeds and completes 12 jobs in a shared schema and exposes a loopback report. The separate executable demo uses one JVM with two logical workers, a simulated downstream and a disposable PostgreSQL schema. Its smoke test asserts seven completions, seven effects, zero duplicates, a peak of three and one reconciliation. [Demo command and report](../../README.md#postgresql-integration-tests-and-executable-demo)

## Reproduce the standalone HTTP suites

Use JDK 25. Each suite starts its own Spring Boot server on loopback, sends real HTTP requests and generates Surefire XML. Python 3 renders that XML and the captured exchanges into the HTML scenario report.

```sh
./mvnw -f examples/regional-version-selection/pom.xml -DfailIfNoTests=true clean test
./mvnw -f examples/summary-validation/pom.xml -DfailIfNoTests=true clean test
```

- **Regional version selection** uses controlled catalog releases, authenticated firm and file context and a manager with controllable baseline and apply status. The suite asserts exact offers and holds, withdrawal and replacement, scoped declines, the original offer receipt, and when confirmed success advances the baseline. [Generate the regional report](../../examples/regional-version-selection/README.md)
- **Summary validation** varies the approved v4 and v7 bytes, grouping output and provider outcome. It checks C17 and C18 only, complete rules on failure, the editorial hold and the exact French v7 artifact returned after v8 is promoted and a 404 for an older instance’s identifiers after a replacement mock archives v8. [Generate the summary report](../../examples/summary-validation/README.md)

## Reproduce the connected HTTP flow

Run both standalone suites and the three connected scenarios in one Maven reactor with Java 25.

```bash
./mvnw -f examples/pom.xml -DfailIfNoTests=true clean test
```

The connected test driver forwards the exact regional offer pair and checks the original summary view receipt after a later revision. The summary server records zero generation requests for a withheld offer and returns 422 for changed artifact bytes. The connected workflow publishes the captured HTTP exchanges and Surefire XML. [Run and generate the connected report](../../examples/connected-reference-apis/README.md)

A deployed region has separate acceptance criteria for service-to-service calls, durable archive recovery, SQS delivery and downstream admission. [System architecture](../system-architecture.md) · [Regional acceptance](../owner-acceptance.md) · [Regional rollout verification](../regional-verification.md)
