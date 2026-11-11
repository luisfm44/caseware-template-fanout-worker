# SonarQube code quality

The [Verify and scan workflow](../.github/workflows/ci.yml) runs the `quality` job on pushes to `main` and on manual dispatch. The required `verify` and `security` jobs continue to run on pull requests. Community Build analyzes a single main branch. A manual run on a feature branch uses that checkout as the main branch of an isolated, temporary project.

## Coverage and Quality Gate

The Maven `coverage` profile uses JaCoCo 0.8.16 to collect execution data from Surefire and Failsafe. With the `postgres-integration` profile active, the XML report includes both the unit and PostgreSQL integration tests. Use `clean verify` so the report contains only execution data from the current build. SonarScanner for Maven 5.8.0.7211 imports the XML.

The default job starts SonarQube Community Build 26.11 on the runner, bound to loopback. It uses a disposable private project, a temporary project analysis token and a Quality Gate with a **50% minimum for overall coverage**. The runner removes the container and its data after the scan. The gate measures coverage of production code exercised by the two test suites. The separately executed demo is a smoke test.

The initial baseline from 13 tests was **52.0% overall coverage**, with **54.4% line coverage** and **45.9% branch coverage**. The initial floor leaves room for incremental improvement while catching regressions against that baseline.

Open the workflow's **Summary** for the gate decision and coverage. Download the **sonarqube-quality** artifact for the JaCoCo XML and HTML, gate conditions, measured values and the available SonarQube findings. This mode publishes a snapshot per run. A persistent server provides a dashboard and historical trends.

## Run locally

From the repository root with JDK 25, Docker Compose, Bash, `curl`, `jq` and `openssl`:

```sh
export FANOUT_DB_PASSWORD='your-local-password'
docker compose up -d --wait
./mvnw -Ppostgres-integration,coverage -DfailIfNoTests=true clean verify
bash scripts/run-sonarqube.sh
docker compose down
```

Open `target/site/jacoco/index.html` to inspect per-class coverage and `target/sonar-quality/summary.md` for the Quality Gate result. When using the internal-disk workaround on macOS, pass the same directory to Maven and the scanner.

```sh
export FANOUT_BUILD_DIRECTORY=/private/tmp/caseware-fanout-target
./mvnw "-Dfanout.build.directory=$FANOUT_BUILD_DIRECTORY" \
  -Ppostgres-integration,coverage -DfailIfNoTests=true clean verify
bash scripts/run-sonarqube.sh
```

To verify failure propagation, select **prove_gate_failure** when manually running **Verify and scan**, or run `bash scripts/run-sonarqube.sh --prove-gate-failure` after building coverage locally. The probe creates its own isolated project with a 100% overall coverage gate. The scanner exits nonzero when the gate fails and the reports show the failing condition.

## Connect an existing SonarQube server

Configure the project and Quality Gate on an instance reachable from the GitHub runner. In GitHub Actions, set the repository variable `SONAR_HOST_URL` and the repository secret `SONAR_TOKEN` to a project analysis token. The optional repository variable `SONAR_PROJECT_KEY` selects the project key. It defaults to `caseware-template-fanout-worker`. The workflow uses these settings on `main`. The server controls its own gate thresholds and dashboard access.

[SonarQube Community Build CI integration](https://docs.sonarsource.com/sonarqube-community-build/devops-platform-integration/github-integration/adding-analysis-to-github-actions-workflow) · [JaCoCo Maven documentation](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
