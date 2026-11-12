# GitHub Actions pipelines

The root Maven project builds the Java worker. **Verify and scan** calls both standalone HTTP workflows and the connected reference workflow, then combines their outcomes with the worker quality result. [Report and test evidence](evidence/README.md)

| GitHub event | Verify and scan | Reference HTTP workflows | Run local deployment demo |
| --- | --- | --- | --- |
| Pull request targeting `main` | `verify`, `security`, `quality` | All three called by Verify and scan | — |
| Push to `main`, including a merge | `verify`, `security`, `quality` | All three called by Verify and scan | — |
| Push to another branch | — | — | — |
| Manual dispatch | `verify`, `security`, `quality` | Called by Verify and scan or dispatched individually | `smoke` |

The automatic checks run across all PRs to `main`. Use manual dispatch to reproduce an individual HTTP workflow on a selected branch. [Verify and scan](../.github/workflows/ci.yml) · [Demo workflow](../.github/workflows/demo.yml) · [Regional workflow](../.github/workflows/regional-version-selection.yml) · [Summary workflow](../.github/workflows/summary-validation.yml) · [Connected workflow](../.github/workflows/connected-reference-apis.yml)

## Jobs and pull request checks

- **`verify`** is the required merge check. It waits for `quality` and the three reference HTTP workflows and requires a successful result from each.
- **`security`** uses Trivy to scan the checkout for high and critical vulnerabilities and secrets. Findings in those severity levels fail the job.
- **`quality`** uses JDK 25 and PostgreSQL 18.6 to run the worker unit and integration tests, enforce JaCoCo, package the worker and run SonarQube. `worker-and-test-results` contains the JAR and both sets of XML reports for seven days. `sonarqube-quality` contains the coverage and analysis reports for 14 days.

## Code quality

The worker build requires **100% line and branch coverage in JaCoCo**. The repository-managed SonarQube Quality Gate requires **100% overall worker coverage**. The PostgreSQL and HTTP suites assert behavior, including recovery, shared capacity, offer rules and summary promotion. Open the PR's **Verify and scan** run to inspect the results. To reproduce the full workflow manually, leave `prove_gate_failure` unchecked.

The manual `prove_gate_failure` input builds a unit-only coverage report for a separate analysis. SonarQube rejects that report when coverage is below 100%. The failed `quality` job makes `verify` fail as well. Use a normal run to record acceptance evidence. [Local reproduction and reports](code-quality.md)

## Worker process tests and demo

The PostgreSQL integration suite runs `DurableWorkerProcessIT` against a controlled HTTP manager. It verifies that another worker JVM recovers after a process dies, applies each operation once and respects the shared capacity limit. One case checks that a small firm takes the next free slot while the large firm still has ready jobs. Another keeps a slot free while both JVMs reconcile large-firm attempts. It checks that the small firm reaches `APPLIED` with one effect, the original reservations remain active and the shared limit holds. The `worker-and-test-results` artifact contains the Failsafe XML. [Two-JVM integration tests](../src/test/java/io/github/luisfm44/caseware/worker/bootstrap/DurableWorkerProcessIT.java)

From **Actions → Run local deployment demo → Run workflow**, select the branch to validate. The `smoke` job starts PostgreSQL, verifies and packages the worker, then launches the `demo` profile with two logical workers in one JVM and an in-process simulator. Its JSON checks cover seven jobs across four pages, three peak simultaneous operations, zero duplicate effects and one lost-reply reconciliation. The `fanout-demo` artifact contains `fanout-demo.json` for seven days. [Run the demo locally](../README.md#postgresql-integration-tests-and-executable-demo)

## HTTP acceptance reports

The workflows use JDK 25 and send real loopback HTTP requests to Spring Boot with synthetic fixtures. Their reports include Surefire outcomes, fixture details and recorded exchanges.

| Workflow | Artifact | Local instructions |
| --- | --- | --- |
| Regional version selection E2E | `regional-version-selection-e2e` | [Regional version selection mockup](../examples/regional-version-selection/README.md) |
| Summary validation E2E | `summary-validation-e2e` | [Summary validation mockup](../examples/summary-validation/README.md) |
| Connected reference APIs | `connected-reference-apis` | [Connected flow with mocks](../examples/connected-reference-apis/README.md) |

Open **Verify and scan** or a standalone run, then its **Summary** or **Artifacts** section. Download the archive and open `evidence/index.html` after extracting it. Each HTTP workflow retains reports for 30 days. The examples verify reference behavior with controlled dependencies. A future regional rollout requires evidence from the deployed services, durable storage and queue. [Evidence index](evidence/README.md) · [Owner acceptance criteria](owner-acceptance.md) · [Regional readiness](regional-verification.md)

## Diagnose a run

1. Open the run and confirm its event, branch and commit.
2. Open the failed job and read the first failed step. Download the test or quality reports to identify the exact failure.
3. For `quality`, read the condition in the run summary. If the run used `prove_gate_failure`, use a normal run to assess the PR. For an HTTP job, read the scenario output and Surefire report if the run produced them.
4. Update the linked issue and PR with the result, then rerun the affected workflow after applying the fix.

[Contributor workflow](contributing.md) · [Worker architecture](worker-architecture.md)
