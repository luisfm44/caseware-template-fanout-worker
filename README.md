# Template publish fan-out worker

Caseware Staff Java take-home. The worker plans template publication jobs for eligible engagements, persists progress in PostgreSQL and coordinates execution against a shared downstream capacity pool.

## Worker architecture

Planning commits each page of jobs with its checkpoint in PostgreSQL. Execution uses the same operation ID for admission, dispatch, retries and reconciliation. The tests exercise shared admission within one JVM and use a simulated downstream.

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
- **Run local deployment demo** is a manual smoke workflow on an isolated runner. The demo entry point is tracked in Issue #4. The workflow builds the JAR and launches its demo profile against a simulated downstream. The profile must write a JSON report to the file supplied through `--demo.report`. The workflow checks that every submitted engagement completed, no side effect was duplicated, and peak concurrency respected the configured limit. A missing report or implementation fails the run.

## Implementation checkpoints

1. [Two workers share admission to the downstream service](https://github.com/luisfm44/caseware-template-fanout-worker/issues/1). Instrument the fake service to count peak simultaneous calls.
2. [Page through engagements and resume the fan-out after a crash](https://github.com/luisfm44/caseware-template-fanout-worker/issues/2). Replaying a publication preserves job identity.
3. [Lose the response after a successful downstream effect](https://github.com/luisfm44/caseware-template-fanout-worker/issues/3). Retry with the same operation key or reconcile the recorded outcome.
4. [Verify the executable artifact and record implementation tradeoffs and the actual use of AI in this README](https://github.com/luisfm44/caseware-template-fanout-worker/issues/4).

### Follow-up issues

- [SonarQube code quality analysis](https://github.com/luisfm44/caseware-template-fanout-worker/issues/8)
- [GitHub Wiki for contributors and reviewers](https://github.com/luisfm44/caseware-template-fanout-worker/issues/9)
