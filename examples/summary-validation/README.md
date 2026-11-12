# Summary validation mockup

This reference API exercises the promotion rules from [issue #27](https://github.com/luisfm44/caseware-template-fanout-worker/issues/27). The tests send real HTTP requests to a Spring Boot server on a dynamic port at `127.0.0.1`. They use synthetic template revisions and scripted grouping responses. The application verifies artifact hashes, compares the approved baseline and target, renders localized sentences, and records which summary version a receipt points to. Each mock instance uses an in-memory archive. Summary versions and receipts use distinct UUID-based identifiers. A replacement mock returns HTTP 404 for an earlier identifier even after archiving a new comparison. The tests require zero cloud services, model calls, or credentials.

## Run the tests locally

Use JDK 25 and run the command from the repository root.

```sh
./mvnw -f examples/summary-validation/pom.xml -DfailIfNoTests=true clean test
```

On Windows, use `mvnw.cmd` with the same arguments. Maven downloads dependencies on the first run. The suite starts and stops the server.

## View each scenario

Run the report command with Python 3 after the suite finishes. The renderer reads the Surefire results, including any recorded failures.

```sh
python3 examples/regional-version-selection/tools/render_evidence.py \
  examples/summary-validation/target/surefire-reports/TEST-io.github.luisfm44.caseware.summary.SummaryValidationHttpTest.xml \
  examples/summary-validation/target/evidence \
  --manifest examples/summary-validation/report-scenarios.json
```

Open `examples/summary-validation/target/evidence/index.html` to see each scenario's result, fixture details, and recorded HTTP requests and responses.

**Verify and scan** runs the same suite. Download `summary-validation-e2e` from the run, extract it and open `evidence/index.html`. You can also run this suite by itself from its workflow. [Latest verified reports](https://github.com/luisfm44/caseware-template-fanout-worker/wiki/Test-Evidence) · [Standalone workflow](https://github.com/luisfm44/caseware-template-fanout-worker/actions/workflows/summary-validation.yml)

## HTTP outcomes covered by the suite

| Request | Tested outcomes |
| --- | --- |
| `POST /summaries` | `200 GROUPED` when the grouping covers every verified net change once and uses allowed topics. C17 requires inventory sign-off. C18 adds a field for the reason for an exception and establishes field presence only. |
| `POST /summaries` | `422 HASH_MISMATCH` when the returned baseline or target bytes differ from the approved hash. The response contains no summary artifact. |
| `POST /summaries` | `200 RULES` when the grouping is rejected and the verified changes can all be rendered with approved wording. The suite exercises an omitted, duplicate, or fabricated ID, an invalid topic, extra prose, trailing content, and malformed JSON. |
| `POST /summaries` | `200 RULES` with the existing artifact when the grouping provider reports timeout, input-limit rejection, or budget exhaustion. A rejected grouping also preserves an existing verified `GROUPED` artifact for that comparison. |
| `POST /summaries` | `200 EDITOR_REVIEW` with no summary artifact when a material edit has no approved wording. |
| `POST /summaries/{version}/views` and `GET /views/{receiptId}` | `201` creates a receipt for a specific summary version. `200` retrieves the original French artifact with the original hashes, verified changes, and localized sentences after a later revision is rendered. |

The [owner acceptance criteria](../../docs/owner-acceptance.md#summary-service) describe the evidence to collect in the owning service's CI using its artifact store and rendering path.
