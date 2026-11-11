# Template publish fan-out worker

Caseware Staff Java take-home. The worker starts operations for a template publication across engagements using a shared downstream capacity pool. The first checkpoint exercises two workers in one JVM against a simulated downstream.

## Worker architecture

Two logical workers share one admission controller and one simulated downstream in the Issue #1 tests. The controller admits up to three concurrent operations across both workers. If a caller times out, its operation keeps the slot until the downstream confirms it has ended.

<!-- Image exported from the Mermaid diagram in docs/worker-architecture.md. -->
![Two workers share one admission controller with three slots and dispatch admitted operations to the same simulated downstream.](docs/worker-architecture.svg)

[Worker components and operation lifecycle](docs/worker-architecture.md) · [System architecture and diagrams](docs/architecture.pdf) · [AWS cost model with editable assumptions](docs/aws-cost-model.xlsx)

## Run locally

Use JDK 25. The checked-in Apache Maven Wrapper downloads Maven 3.9.16 on its first run and verifies the distribution checksum. Spring Boot 4.1.2 provides the application entry point.

```sh
./mvnw --version
./mvnw -DfailIfNoTests=true verify
```

Windows users can use `mvnw.cmd`. Packaging produces `target/template-fanout-worker.jar`.

### PostgreSQL integration tests

The publication planner stores each page's jobs and cursor in one PostgreSQL transaction. Run the integration suite against the PostgreSQL 18 service in [`compose.yaml`](compose.yaml).

```sh
export FANOUT_DB_PASSWORD='your-local-password'
docker compose up -d --wait
./mvnw -Ppostgres-integration -DfailIfNoTests=true verify
docker compose down
```

Compose binds PostgreSQL to `127.0.0.1:55432` and keeps its data in a named volume. Reuse the same password when restarting with that volume. The default Maven build runs the unit tests. The `postgres-integration` profile adds tests for resume, replay and rollback against PostgreSQL.

If macOS file metadata interferes with a build on an external drive, add `-Dfanout.build.directory=/private/tmp/caseware-fanout-target` to the Maven command to write build output to the internal disk.

## GitHub Actions

- **Verify and scan** runs on pull requests, pushes to main, or manual dispatch. The `verify` job runs the unit and PostgreSQL integration tests, packages the executable JAR, and uploads both sets of test reports. The `security` job scans dependencies and tracked sources with Trivy. High or critical findings fail the scan.
- **Run local deployment demo** is a manual smoke workflow on an isolated runner. It requires the implemented worker, builds the JAR and launches its demo profile against a simulated downstream. The profile must write a JSON report to the file supplied through `--demo.report`. The workflow checks that every submitted engagement completed, no side effect was duplicated, and peak concurrency respected the configured limit. A missing report or implementation fails the run.

## Implementation checkpoints

1. [Two workers share admission to the downstream service](https://github.com/luisfm44/caseware-template-fanout-worker/issues/1). Instrument the fake service to count peak simultaneous calls.
2. [Page through engagements and resume the fan-out after a crash](https://github.com/luisfm44/caseware-template-fanout-worker/issues/2). Replaying a publication preserves job identity.
3. [Lose the response after a successful downstream effect](https://github.com/luisfm44/caseware-template-fanout-worker/issues/3). Retry with the same operation key or reconcile the recorded outcome.
4. [Verify the executable artifact and record implementation tradeoffs and the actual use of AI in this README](https://github.com/luisfm44/caseware-template-fanout-worker/issues/4).
