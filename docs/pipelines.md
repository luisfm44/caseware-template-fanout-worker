# GitHub Actions pipelines

The root Maven project builds the Java worker. The two reference APIs under `examples/` have separate Maven projects and HTTP acceptance workflows. [Report and test evidence](evidence/README.md)

| GitHub event | Verify and scan | Run local deployment demo | Regional version selection E2E | Summary validation E2E |
| --- | --- | --- | --- | --- |
| Pull request targeting `main` | `verify`, `security` | — | Matching paths | Matching paths |
| Push to `main`, including a merge | `verify`, `security`, `quality` | — | Matching paths | Matching paths |
| Push to another branch | — | — | Matching paths | Matching paths |
| Manual dispatch | `verify`, `security`, `quality` | `smoke` | `e2e` | `e2e` |

Each HTTP workflow matches its example directory, its workflow file and Maven Wrapper files. The summary workflow also matches the shared report renderer in the regional example. A change confined to documentation outside those paths leaves both HTTP workflows untriggered. Use manual dispatch to rerun either suite on a selected branch. [Worker verification workflow](../.github/workflows/ci.yml) · [Demo workflow](../.github/workflows/demo.yml) · [Regional workflow](../.github/workflows/regional-version-selection.yml) · [Summary workflow](../.github/workflows/summary-validation.yml)

## Jobs and pull request checks

- **`verify`** uses JDK 25 and PostgreSQL 18.6. It runs the root project's unit and PostgreSQL integration tests and builds `target/template-fanout-worker.jar`. The `worker-and-test-results` artifact contains the JAR and both XML report sets for seven days.
- **`security`** uses Trivy to scan the checkout for high and critical vulnerabilities and secrets. Findings in those severity levels fail the job.
- **`quality`** collects worker coverage and runs the SonarQube Quality Gate. The job is skipped for a pull request event. Use a manual run on the PR branch to obtain the gate result for worker Java or test changes.

## Code quality

The worker build requires **100% line coverage and 100% branch coverage in JaCoCo** across the unit and PostgreSQL integration suites. The repository-managed **SonarQube Quality Gate requires 100% overall coverage**. The two separate example projects publish their acceptance results through the HTTP workflows.

From **Actions → Verify and scan → Run workflow**, select the PR branch and leave **prove_gate_failure** unchecked for normal validation. A repository maintainer can dispatch the workflow for contributors without write access. Confirm that the successful run uses the PR's current commit. The run summary shows the gate result and coverage. The `sonarqube-quality` artifact contains the JaCoCo reports and SonarQube analysis results. Attach the run link to the PR when recording Test evidence.

The `prove_gate_failure` input builds a unit-only coverage report for a separate analysis. SonarQube rejects that report when coverage is below 100%, so GitHub marks `quality` as failed. Record the full run as acceptance evidence. [Local reproduction and reports](code-quality.md)

## Worker process tests and demo

The PostgreSQL integration suite runs `DurableWorkerProcessIT` against a controlled HTTP manager. It verifies that another worker JVM recovers after a process dies, applies each operation once and respects the shared capacity limit. A separate case fills both slots with a large firm's work, then checks that a small firm takes the next free slot while the large firm remains backlogged. The `worker-and-test-results` artifact contains the Failsafe XML. [Two-JVM integration tests](../src/test/java/io/github/luisfm44/caseware/worker/bootstrap/DurableWorkerProcessIT.java)

From **Actions → Run local deployment demo → Run workflow**, select the branch to validate. The `smoke` job starts PostgreSQL, verifies and packages the worker, then launches the `demo` profile with two logical workers in one JVM and an in-process simulator. Its JSON checks cover seven jobs across four pages, three peak simultaneous operations, zero duplicate effects and one lost-reply reconciliation. The `fanout-demo` artifact contains `fanout-demo.json` for seven days. [Run the demo locally](../README.md#postgresql-integration-tests-and-executable-demo)

## HTTP acceptance reports

Both workflows use JDK 25 and send real loopback HTTP requests to Spring Boot with synthetic fixtures. Their reports include Surefire outcomes, fixture details and recorded exchanges.

| Workflow | Artifact | Local instructions |
| --- | --- | --- |
| Regional version selection E2E | `regional-version-selection-e2e` | [Regional version selection mockup](../examples/regional-version-selection/README.md) |
| Summary validation E2E | `summary-validation-e2e` | [Summary validation mockup](../examples/summary-validation/README.md) |

Open the run **Summary** or **Artifacts** section, download the archive and open `evidence/index.html` after extracting it. Both workflows retain reports for 30 days. The examples verify reference behavior with controlled dependencies. The owning APIs provide separate CI evidence, and regional readiness requires a run in the regional test environment. [Evidence index](evidence/README.md) · [Owner acceptance criteria](owner-acceptance.md) · [Regional readiness](regional-verification.md)

## Diagnose a run

1. Open the run and confirm its event, branch and commit.
2. Open the failed job and read the first failed step. Download the test or quality reports to identify the exact failure.
3. For `quality`, read the condition in the run summary. If the run used `prove_gate_failure`, use a normal run to assess the PR. For an HTTP job, read the scenario output and Surefire report if the run produced them.
4. Update the linked issue and PR with the result, then rerun the affected workflow after applying the fix.

[Contributor workflow](contributing.md) · [Worker architecture](worker-architecture.md)
