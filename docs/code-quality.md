# SonarQube code quality

The `quality` job runs on pull requests targeting `main`, pushes to `main` and manual dispatch. The required `verify` check depends on this result. Pull request jobs use the checked-out merge candidate and a disposable Community Build project without credentials for a shared SonarQube server. [Verify and scan workflow](../.github/workflows/ci.yml) · [Pipeline triggers and jobs](pipelines.md)

## Coverage and Quality Gate

The root Maven project's `coverage` profile uses JaCoCo 0.8.16 to collect execution data from Surefire and Failsafe. The `postgres-integration` profile adds PostgreSQL integration tests to the worker's unit suite. Use `clean verify` to collect execution data for the current build. SonarScanner for Maven 5.8.0.7211 imports the XML. The regional and summary reference APIs each have a separate Maven project. Their standalone and connected HTTP workflows publish their own results and contribute to the required check. [Worker Maven configuration](../pom.xml) · [Acceptance reports](evidence/README.md)

The coverage build checks that JaCoCo reports zero missed production lines and branches. The default quality job starts SonarQube Community Build 26.11 on the runner, bound to loopback. It uses a disposable private project, a temporary project analysis token and a Quality Gate with a **100% minimum for overall coverage**. The runner removes the container and its data after the scan.

Open the workflow's **Summary** for the gate decision and coverage for that commit. The `sonarqube-quality` artifact retains the JaCoCo XML and HTML, gate conditions, measurements and available SonarQube findings for 14 days. Record the run URL on the pull request. [Worker CI runs](https://github.com/luisfm44/caseware-template-fanout-worker/actions/workflows/ci.yml)

## Run locally

Use JDK 25, Docker with Compose, Bash, `curl`, `jq` and `openssl`. Run these commands from the repository root.

```sh
export FANOUT_DB_PASSWORD='your-local-password'
docker compose up -d --wait
./mvnw -Ppostgres-integration,coverage -DfailIfNoTests=true clean verify
bash scripts/run-sonarqube.sh
docker compose down
```

Open `target/site/jacoco/index.html` to inspect per-class coverage and `target/sonar-quality/summary.md` for the Quality Gate result. If macOS metadata on an external drive interferes with the build, keep PostgreSQL running and pass the same internal-disk directory to Maven and the scanner.

```sh
export FANOUT_BUILD_DIRECTORY=/private/tmp/caseware-fanout-target
./mvnw "-Dfanout.build.directory=$FANOUT_BUILD_DIRECTORY" \
  -Ppostgres-integration,coverage -DfailIfNoTests=true clean verify
bash scripts/run-sonarqube.sh
```

To verify failure propagation, select `prove_gate_failure` when manually running **Verify and scan**. The probe analyzes fresh unit-only coverage in an isolated project with the 100% overall coverage gate. Maven skips its coverage check for that run so the SonarQube gate makes the decision. A rejected analysis returns a nonzero exit code and records the failing condition.

Run these commands to reproduce the probe locally.

```sh
./mvnw -Pcoverage -Dfanout.coverage.check.skip=true -DfailIfNoTests=true clean verify
bash scripts/run-sonarqube.sh --prove-gate-failure
```

## Connect an existing SonarQube server

Configure the project with a 100% overall coverage gate on an instance reachable from the GitHub runner. In GitHub Actions, set the repository variable `SONAR_HOST_URL` and the repository secret `SONAR_TOKEN` to a project analysis token. The optional repository variable `SONAR_PROJECT_KEY` selects the project key. It defaults to `caseware-template-fanout-worker`. The workflow uses these settings on `main`. The server controls its own gate thresholds and dashboard access.

[SonarQube Community Build CI integration](https://docs.sonarsource.com/sonarqube-community-build/devops-platform-integration/github-integration/adding-analysis-to-github-actions-workflow) · [JaCoCo Maven documentation](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
