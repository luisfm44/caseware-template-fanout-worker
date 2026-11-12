# Regional version selection mockup

This example exercises the proposed policy from [issue #26](https://github.com/luisfm44/caseware-template-fanout-worker/issues/26) through a reference API. Spring Boot starts an HTTP server on a dynamic port at `127.0.0.1`. The tests authenticate two synthetic firms, send real HTTP requests and assert the responses. Test adapters control the regional catalog and engagement manager. The API calculates the offer and the baseline and target versions for the summary.

## Run the tests locally

Use JDK 25 and run the command from the repository root.

```sh
./mvnw -f examples/regional-version-selection/pom.xml test
```

On Windows, use `mvnw.cmd` with the same arguments. Maven downloads dependencies on the first run. The suite starts and stops the server.

## View each scenario

Use Python 3 to generate the report from the Surefire results.

```sh
python3 examples/regional-version-selection/tools/render_evidence.py \
  examples/regional-version-selection/target/surefire-reports/TEST-io.github.luisfm44.caseware.regional.RegionalVersionSelectionHttpTest.xml \
  examples/regional-version-selection/target/evidence
```

Open `examples/regional-version-selection/target/evidence/index.html`. The report shows each test result, changes to the fixtures and the observed HTTP requests and responses. Run the reporting command after the suite finishes to inspect failures as well. The trace excludes synthetic credentials.

GitHub Actions runs the same suite and publishes the HTML report and Surefire XML as `regional-version-selection-e2e`. Download the artifact and open `evidence/index.html`. The [root README](../../README.md#regional-version-selection-e2e-tests) links the verified run, and the [scenario screenshots](../../docs/evidence/issue-26/README.md) are checked into the branch.

## HTTP outcomes covered by the suite

| Request | Tested outcomes |
| --- | --- |
| `GET /engagements/{id}/offer` | `200` with an eligible target and summary pair, or `200` with both fields set to `null` when the offer is withheld. |
| `POST /engagements/{id}/decline` | `204` after recording the exact decline. `409` if the target is no longer offered. |
| `POST /engagements/{id}/apply` | `202` with a pending application. `409` if the target is no longer offered. |
| `GET /engagements/{id}/applications/{decisionId}` | `200` with `PENDING`, `FAILED` or `APPLIED`. The confirmed baseline advances after the manager confirms success. |
| Offer and application access | `401` for an unauthenticated offer request. `404` for reading or applying to a file owned by another firm. |

The suite uses synthetic data and controlled dependencies. Closing #26 requires running acceptance tests against the owning regional API and linking its CI evidence.
