# Template publish fan-out worker

Caseware Staff Java take-home. The worker plans template publication jobs for eligible engagements, persists progress in PostgreSQL and coordinates execution against a shared downstream capacity pool.

## Worker architecture

Planning commits each page of jobs with its checkpoint in PostgreSQL. Execution uses the same operation ID for admission, dispatch, retries and reconciliation. The executable demo connects these paths and runs two logical workers in one JVM against a simulated downstream.

<!-- Image exported from the Mermaid diagram in docs/worker-architecture.md. -->
![Caseware worker architecture showing PostgreSQL publication planning and downstream execution sharing an operation ID.](docs/worker-architecture.svg)

[Worker architecture and verification](docs/worker-architecture.md) · [Worker design decisions](docs/worker-design.md) · [System architecture and implementation appendix](docs/architecture.pdf) · [AWS cost model with editable assumptions](docs/aws-cost-model.xlsx)

## Run locally

Use JDK 25. The checked-in Apache Maven Wrapper downloads Maven 3.9.16 on its first run and verifies the distribution checksum. Spring Boot 4.1.2 provides the application entry point.

```sh
./mvnw --version
./mvnw -DfailIfNoTests=true verify
```

Windows users can use `mvnw.cmd`. Packaging produces `target/template-fanout-worker.jar`.

### PostgreSQL integration tests and executable demo

From a clean checkout with JDK 25 and Docker Compose, start the PostgreSQL 18.6 service in [`compose.yaml`](compose.yaml), run the tests and launch the packaged demo.

```sh
export FANOUT_DB_PASSWORD='your-local-password'
docker compose up -d --wait
./mvnw -Ppostgres-integration -DfailIfNoTests=true verify
java -jar target/template-fanout-worker.jar --spring.profiles.active=demo --demo.report=target/fanout-demo.json
cat target/fanout-demo.json
docker compose down
```

Compose binds PostgreSQL to `127.0.0.1:55432` and keeps its data in a named volume. Reuse the same password when restarting with that volume. The default Maven build runs the unit tests. The `postgres-integration` profile adds tests for resume, replay and rollback against PostgreSQL. The demo uses a disposable schema and removes it after the run.

If macOS file metadata interferes with a build on an external drive, add `-Dfanout.build.directory=/private/tmp/caseware-fanout-target` to the Maven command and use that directory in the `java -jar` path.

The sample publication has seven synthetic engagements and a page size of two. A fresh planner resumes after the first committed page. Two logical workers then execute the persisted job IDs under a shared limit of three. The downstream records one effect and drops its reply. The worker looks up the result with the same operation ID and completes that job without resubmitting it.

| Report field | Expected | Meaning |
| --- | ---: | --- |
| `plannedPages` | 4 | Pages committed to PostgreSQL after planning and resume |
| `submitted` | 7 | Persisted jobs submitted for worker execution |
| `completed` | 7 | Jobs that reached confirmed success |
| `appliedEffects` | 7 | Effects recorded by the simulated downstream |
| `duplicateEffects` | 0 | Effects applied again for a previously applied operation ID |
| `limit` | 3 | Shared admission capacity |
| `peakInFlight` | 3 | Peak simultaneous operations observed by the downstream |
| `dispatchAttempts` | 7 | Calls to submit work to the downstream |
| `reconciliations` | 1 | Status lookups after an ambiguous reply |

The demo measures shared capacity within one JVM. PostgreSQL makes planning durable, while the simulator keeps the effect ledger in memory. Production integration requires a durable job consumer, admission shared across worker processes and a downstream that enforces operation IDs as idempotency keys and provides authoritative status. The retry budget is scoped to a worker execution. See the [design decisions](docs/worker-design.md) for the tradeoffs.

## GitHub Actions

- **Verify and scan** runs on pull requests, pushes to main, or manual dispatch. The `verify` job runs the unit and PostgreSQL integration tests, packages the executable JAR, and uploads both sets of test reports. The `security` job scans dependencies and tracked sources with Trivy. High or critical findings fail the scan.
- **SonarQube quality** runs on pushes to `main` and on manual dispatch within **Verify and scan**. It combines unit and PostgreSQL test coverage, requires full JaCoCo line and branch coverage and enforces a 100% overall SonarQube Quality Gate. [Run and inspect code quality checks](docs/code-quality.md)
- **Run local deployment demo** is a manual smoke workflow on an isolated runner. It starts a PostgreSQL service, builds the JAR and launches the demo profile. The workflow checks the report for successful completion, single effects, shared capacity, resumed planning and reconciliation, then uploads the JSON report as an artifact. [Demo workflow](.github/workflows/demo.yml)

## AI assistance and human judgment

I used ChatGPT and Codex to compare implementation options and draft Java code, tests, CI configuration and documentation. During Issue #2, Codex initially proposed H2 for the durable checkpoint store. I asked to use PostgreSQL in Docker. The implementation used PostgreSQL transactions, a named development volume and integration tests against PostgreSQL in CI.

Production decisions still require human judgment. These include choosing who owns shared admission across processes, deciding what downstream evidence is authoritative after a timeout and weighing the capacity cost of retaining a permit while the outcome is unknown.

## Implementation checkpoints

1. [Two workers share admission to the downstream service](https://github.com/luisfm44/caseware-template-fanout-worker/issues/1). Instrument the fake service to count peak simultaneous calls.
2. [Page through engagements and resume the fan-out after a crash](https://github.com/luisfm44/caseware-template-fanout-worker/issues/2). Replaying a publication preserves job identity.
3. [Lose the response after a successful downstream effect](https://github.com/luisfm44/caseware-template-fanout-worker/issues/3). Retry with the same operation key or reconcile the recorded outcome.
4. [Verify the executable artifact and record implementation tradeoffs and the actual use of AI in this README](https://github.com/luisfm44/caseware-template-fanout-worker/issues/4).

### Follow-up issues

- [SonarQube code quality analysis and reproduction](docs/code-quality.md) · [Issue #8](https://github.com/luisfm44/caseware-template-fanout-worker/issues/8)
- [Full Java test coverage](https://github.com/luisfm44/caseware-template-fanout-worker/issues/12)
- [GitHub Wiki for contributors and reviewers](https://github.com/luisfm44/caseware-template-fanout-worker/issues/9)
