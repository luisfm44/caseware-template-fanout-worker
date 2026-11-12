# Caseware feedback and test evidence

The baseline is [commit `b80c148` on `main`](https://github.com/luisfm44/caseware-template-fanout-worker/commit/b80c1483c7e6380249fb2815281db0feff26ee84). The linked CI archives were checked against their Surefire and Failsafe results. All 105 tests across the worker and both reference APIs passed with zero failures, errors or skips.

| CI evidence | Verified result | Open the report |
| --- | --- | --- |
| Worker build and tests | **48 unit tests and 29 PostgreSQL integration tests**. Includes four worker process scenarios, four snapshot scenarios and the executable demo entry point. | [Test reports and worker JAR](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43134129499/artifacts/13905974454) · [Workflow run](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43134129499) |
| Worker quality | **SonarQube Quality Gate passed**. JaCoCo line and branch coverage and SonarQube overall worker coverage are 100%. | [Coverage and SonarQube reports](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43134129499/artifacts/13906113674) · [Quality job](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43134129499/job/130905216055) |
| Regional version selection reference API | **13 HTTP scenarios** with a controlled regional catalog and engagement manager. | [HTML report and Surefire XML](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43134129446/artifacts/13906906514) · [Workflow history](https://github.com/luisfm44/caseware-template-fanout-worker/actions/workflows/regional-version-selection.yml) · [Scenario screenshots](issue-26/README.md) |
| Summary validation reference API | **15 HTTP scenarios** with synthetic approved templates and a scripted grouping provider. | [HTML report and Surefire XML](https://github.com/luisfm44/caseware-template-fanout-worker/actions/runs/43134129467/artifacts/13907106040) · [Workflow history](https://github.com/luisfm44/caseware-template-fanout-worker/actions/workflows/summary-validation.yml) |

In the worker archive, look for `TEST-*.xml` under `surefire-reports/` and `failsafe-reports/`. In the quality archive, open `site/jacoco/index.html` and `sonar-quality/summary.md`. For either HTTP suite, extract the archive and open `evidence/index.html`. Each scenario links to its fixture, assertion summary and captured HTTP responses. Artifact downloads require access to the repository and expire according to the workflow retention setting. Open a newer successful run or use the local commands below when an archive expires.

## Feedback, implementation and acceptance

| Caseware feedback | Change and evidence in this repository | Remaining validation |
| --- | --- | --- |
| Version rules and summary checks lacked automated tests | The regional reference API tests the exact version pair, decline scope, withdrawal, confirmation and firm isolation. The summary reference API tests the approved bytes, net changes, grouping, fallback, editorial review and original receipt. [Version-selection PR #29](https://github.com/luisfm44/caseware-template-fanout-worker/pull/29) · [Summary-validation PR #31](https://github.com/luisfm44/caseware-template-fanout-worker/pull/31) | The owning API and summary service run equivalent suites in their own CI using their actual dependencies. [Owner acceptance](../owner-acceptance.md) |
| Capacity was shared only within one process | A single PostgreSQL pool coordinates workers in independent JVMs. In the recovery test the configured limit and measured peak are both **1**. An unresolved attempt holds the slot even while the original JVM is down. [Shared admission PR #21](https://github.com/luisfm44/caseware-template-fanout-worker/pull/21) | Regional integration verifies the engagement manager's shared admission and authoritative outcomes. [Regional verification](../regional-verification.md) |
| The code lacked scheduling across firms | Durable claims rotate among eligible firms. A small firm takes the next free slot while **30** large-firm jobs are still ready and the measured peak stays at **2**. [Firm scheduling PR #22](https://github.com/luisfm44/caseware-template-fanout-worker/pull/22) | The proposed SQS Fair Queues deployment needs its own regional dwell-time evidence. [Regional verification](../regional-verification.md) |
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
| Fencing and stale revisions | Delay one request until the survivor obtains a definitive fence, then deliver the old attempt. In a separate scenario change the downstream's current engagement revision. | The delayed attempt is rejected and the new attempt can apply once. The stale engagement revision is rejected without an effect. |
| Firm scheduling | Fill two slots with a large firm's jobs across two JVMs. Add a small firm's job, then release one slot. | The next claim goes to the small firm with 30 large-firm jobs still ready. The downstream peak stays at two. |
| Snapshot resume | Commit page one, change the HTTP source's current snapshot, then start a separate planner JVM. Exercise expiration, mismatch and legacy checkpoint handling separately. | The new process reads the stored snapshot and cursor, keeps the original job set and refuses an unsafe resume. |

[Durable worker process test](../../src/test/java/io/github/luisfm44/caseware/worker/bootstrap/DurableWorkerProcessIT.java) · [Snapshot test](../../src/test/java/io/github/luisfm44/caseware/worker/planning/PublicationSnapshotIT.java) · [Process topology and architecture](../worker-architecture.md)

The executable demo uses one JVM with two logical workers, a simulated downstream and a disposable PostgreSQL schema. Its smoke test asserts seven completions, seven effects, zero duplicates, a peak of three and one reconciliation. [Demo command and report](../../README.md#postgresql-integration-tests-and-executable-demo)

## Reproduce the two HTTP suites

Use JDK 25. Each suite starts its own Spring Boot server on loopback, sends real HTTP requests and generates Surefire XML. Python 3 renders that XML and the captured exchanges into the HTML scenario report.

```sh
./mvnw -f examples/regional-version-selection/pom.xml -DfailIfNoTests=true clean test
./mvnw -f examples/summary-validation/pom.xml -DfailIfNoTests=true clean test
```

- **Regional version selection** uses controlled catalog releases, authenticated firm and file context and a manager with controllable baseline and apply status. The suite asserts exact offers and holds, withdrawal and replacement, scoped declines, and when confirmed success advances the baseline. [Generate the regional report](../../examples/regional-version-selection/README.md)
- **Summary validation** varies the approved v4 and v7 bytes, grouping output and provider outcome. It checks C17 and C18 only, complete rules on failure, the editorial hold and the exact French v7 artifact returned after v8 is promoted. [Generate the summary report](../../examples/summary-validation/README.md)

The regional owner suites, SQS delivery and admission in a deployed region have separate acceptance criteria. [System architecture](../system-architecture.md) · [Owner acceptance](../owner-acceptance.md) · [Regional rollout verification](../regional-verification.md)
