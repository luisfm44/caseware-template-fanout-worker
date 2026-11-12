# Regional version selection mockup

This example exercises the proposed policy from issues [#26](https://github.com/luisfm44/caseware-template-fanout-worker/issues/26) and [#34](https://github.com/luisfm44/caseware-template-fanout-worker/issues/34) through a reference API. Spring Boot starts an HTTP server on a dynamic port at `127.0.0.1`. The tests authenticate two synthetic firms, send real HTTP requests and assert the responses. Test adapters control the regional catalog and engagement manager. The API calculates the offer and the baseline and target versions for the summary.

## Make a decision with the original receipt

An eligible `GET /engagements/{id}/offer` returns the baseline, target, summary pair and opaque `receiptId`. Send that ID as the body of the apply or decline request for the same authenticated firm and file.

```json
{"receiptId":"7c218fde-69ee-4950-9b0b-928d1b5e1506"}
```

The mock manager binds the receipt to the firm, file, engagement revision, catalog epoch and original version pair. It compares current state and records a decision under the same lock that publishes fixture catalog changes. An unused stale receipt requires a new offer, including when only the baseline, engagement revision or catalog epoch changes. Repeating the same admitted action returns the same decision ID and current outcome for apply, or another `204` for decline. The opposite action cannot reuse a consumed receipt.

The manager fixture holds receipts and decisions in memory. A real regional implementation would supply durable receipt records, enforce the current auditor's rights and expiry, and serialize admission and withdrawal with the template authority. The fixture models both orderings and keeps the newer file revision when a previously admitted application completes late.

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

**Verify and scan** runs the suite and publishes HTML and Surefire XML as `regional-version-selection-e2e`. Download the artifact and open `evidence/index.html`. The original #26 screenshots remain available as historical evidence. [Latest verified reports](https://github.com/luisfm44/caseware-template-fanout-worker/wiki/Test-Evidence) · [Original screenshots](../../docs/evidence/issue-26/README.md)

## HTTP outcomes covered by the suite

| Request | Tested outcomes |
| --- | --- |
| `GET /engagements/{id}/offer` | `200` with an eligible target, summary pair and receipt, or `200` with those fields set to `null` when the offer is withheld. |
| `POST /engagements/{id}/decline` | `204` after recording or replaying the exact decline. `409` if the receipt is stale or conflicts with the action or file. |
| `POST /engagements/{id}/apply` | `202` with a new or replayed application. `409` if the receipt is stale, conflicts with the action or file, or another receipt already has a pending application for the file. |
| `GET /engagements/{id}/applications/{decisionId}` | `200` with `PENDING`, `FAILED` or `APPLIED`. The confirmed baseline advances after the manager confirms success. |
| Decision input and access | `400` for a missing receipt on an accessible file. `401` without authentication. `404` for reading or applying to a file owned by another firm. |

All 21 scenarios use synthetic data and controlled dependencies in this repository. See the [proposed deployment acceptance criteria](../../docs/owner-acceptance.md#regional-updates-api) for the corresponding regional checks.
