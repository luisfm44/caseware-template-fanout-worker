# GitHub Actions pipelines

The repository uses two workflows. [Verify and scan](../.github/workflows/ci.yml) builds, tests, scans dependencies and runs SonarQube for the events shown below. [Run local deployment demo](../.github/workflows/demo.yml) verifies the packaged application against a simulated downstream.

| GitHub event | `verify` | `security` | `quality` | Demo |
| --- | --- | --- | --- | --- |
| Pull request targeting `main` | Runs | Runs | Skipped | — |
| Push to `main`, including a merge | Runs | Runs | Runs | — |
| Manual **Verify and scan** | Runs | Runs | Runs | — |
| Manual **Run local deployment demo** | — | — | — | Runs tests, packages the JAR and runs the demo |

## Jobs and pull request checks

- **`verify`** uses JDK 25 and PostgreSQL 18.6. It runs the unit and integration tests, builds the Spring Boot JAR, and uploads the JAR and both test report sets in the `worker-and-test-results` artifact.
- **`security`** uses Trivy to scan dependencies and repository files. High and critical findings fail the job.
- **`quality`** uses SonarQube Community Build. Its PR check is expected to show **Skipped**. Use a manual run on the PR branch to obtain a Quality Gate result before merging changes to Java or its tests.

## Code quality

Every Java change must preserve **100% line coverage and 100% branch coverage in JaCoCo** across the unit and PostgreSQL integration suites. The repository-managed **SonarQube Quality Gate requires 100% overall coverage**. Add assertions for the changed behavior, including failure paths relevant to the change.

From **Actions → Verify and scan → Run workflow**, select the PR branch and leave **prove_gate_failure** unchecked for normal validation. A repository maintainer can dispatch the workflow for contributors without write access. Confirm that the successful run uses the PR's current commit. The run summary shows the gate result and coverage. The `sonarqube-quality` artifact contains the JaCoCo reports and SonarQube analysis results. Attach the run link to the PR when recording Test evidence.

The **prove_gate_failure** input intentionally builds a unit-only coverage report in an isolated analysis. SonarQube rejects that report when coverage is below 100%, so GitHub marks the `quality` job as failed and may send a failure notification. Use the full run as acceptance evidence. [Local reproduction and reports](code-quality.md)

## Executable smoke test

From **Actions → Run local deployment demo → Run workflow**, select the branch to validate. The job starts PostgreSQL, runs the tests, builds the JAR and launches Spring Boot with the `demo` profile. The `fanout-demo` artifact contains the JSON report. A successful run completes seven jobs across four pages, peaks at three simultaneous operations, records zero duplicate effects and reconciles one lost reply. [Run the same demo locally](../README.md#postgresql-integration-tests-and-executable-demo)

## Diagnose a run

1. Open the run and confirm its event, branch and commit.
2. Open the failed job and read the first failed step. Download the test or quality reports to identify the exact failure.
3. For `quality`, read the condition in the run summary. If the run used **prove_gate_failure**, use the corresponding full run to assess the PR.
4. Update the linked issue and PR with the result, then rerun the affected workflow after applying the fix.

[Contributor workflow](contributing.md) · [Worker architecture](worker-architecture.md)
