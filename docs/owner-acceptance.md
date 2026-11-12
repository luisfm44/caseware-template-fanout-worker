# Owner-side acceptance suites

The regional updates API owns the offer shown to an auditor. The summary service owns the generated summary and its promotion checks. Each owner needs to exercise the criteria below in its CI. This repository has 13 passing regional version-selection scenarios and 15 passing summary validation scenarios over loopback HTTP with controlled dependencies. The Java worker's PostgreSQL and multi-process tests cover its planning and execution contracts. CI evidence from the owning regional API and summary service remains pending. [Repository test evidence](evidence/README.md) · [Update policy and summary design, pp. 3–4](architecture.pdf#page=3)

## Regional updates API

Run the offer tests through the owning API with a controlled regional catalog, authenticated firm and file context, and an engagement manager fixture that controls the confirmed baseline and apply status. The 13 scenarios in the reference API provide runnable fixtures for this follow-up. [Issue #26](https://github.com/luisfm44/caseware-template-fanout-worker/issues/26) · [Local reference API](../examples/regional-version-selection/README.md) · [Scenario evidence](evidence/issue-26/README.md)

Use one product and market with two engagements, A and B, in the same firm. Both initially have confirmed baseline v4. Give each release an immutable revision identifier and explicitly configure the approved paths. Record the selected target and the requested summary pair in the response or downstream call trace.

| Case | Given and when | Required outcome |
| --- | --- | --- |
| Approved upgrade path | v7 is the active target. Run with an approved path from v4, then with that path removed, then with an ambiguous target designation. | The approved case offers v7 and requests the exact v4 → v7 summary. The missing and ambiguous cases hold the offer. |
| Exact decline | A declined the v4 → v5 offer. With v5 active and compatible, request offers for A and B. In a separate fixture replace A's decline with v3 → v5 while its confirmed baseline remains v4. | A's exact decline suppresses its v5 offer. B still receives v5. The v3 → v5 decline leaves A's v4 → v5 offer available. |
| Later compatible target | A declined v4 → v5. Keep its baseline at v4 and designate v7 as the compatible target. | A receives the v7 offer and one cumulative v4 → v7 summary. |
| Withdrawn target | Withdraw active v7. Test with no replacement, an incompatible replacement and an explicitly designated compatible replacement. Include a file whose confirmed baseline is already v7. | New v7 offers are withdrawn. Only the designated compatible replacement can be offered. The confirmed v7 baseline stays v7 for the already-applied file. |
| Unconfirmed baseline | Remove the confirmed baseline while the owner reports an unknown or flagged version. | Hold the version-specific offer and summary request until verification establishes a baseline. |
| Apply outcome | Submit an allowed apply decision. Keep the owner response pending, then report failure or success in separate runs. | Pending and failed outcomes leave the confirmed baseline unchanged. Only confirmed success advances it. |

Capture fixture inputs and assertions, including the catalog revision, effective baseline, offered target and requested summary pair. Exercise access through the API's real authentication and authorization path for unauthenticated requests, the owning firm and another firm. Attach the owning API's CI run to the follow-up. Evidence of the withdrawal-versus-admission race belongs with the engagement manager and template authority once Product approves the ordering policy described in the proposal.

## Summary service

Run the owning service's comparison, validation and rendering paths with archived template fixtures and a deterministic substitute for optional model grouping. Pin the expected SHA-256 hash of each approved version. Change the bytes returned for the same key and confirm the service checks both artifacts before generating a summary. The reference API exercises 15 scenarios over HTTP using an in-memory archive. Acceptance evidence comes from equivalent scenarios in the owner's CI using its artifact store, renderer and durable archive. [Issue #27](https://github.com/luisfm44/caseware-template-fanout-worker/issues/27) · [Local reference API](../examples/summary-validation/README.md) · [Repository test evidence](evidence/README.md)

Use a v4 baseline with `/inventory/signoffRequired` set to `false` and no `/inventory/exceptionReason`. Let v5 add a temporary field that v7 removes. In v7, require sign-off and add the exception-reason field. Label those two net changes C17 and C18 in the grouping fixture and supply the permitted topic set for each.

| Case | Given and when | Required outcome |
| --- | --- | --- |
| Exact pair and net diff | Compare the approved v4 and v7 artifacts. Run separately with a baseline hash mismatch and a target hash mismatch. | The correct pair produces C17 and C18. The temporary field's addition and reversal leave no net change. Either hash mismatch stops generation and promotion for that pair. |
| Complete valid grouping | Group both IDs exactly once under allowed topics. Render the approved text in the requested locale. | Every rendered sentence maps to a verified change ID, JSON Pointer, operation and typed before/after values. The exception-reason addition describes field presence. A requirement to fill it needs separate evidence. |
| Invalid grouping | Omit C18, duplicate C17 or supply a fabricated ID. In separate cases use a disallowed topic, extra prose, trailing content or malformed JSON. | Each invalid grouping is rejected before promotion. The service keeps an existing verified summary for that pair and locale, or serves a complete rules summary if none exists. |
| Unsupported material edit | Include a material change with no safe approved wording. | Hold the new summary for editor review. A partial rules summary remains ineligible for promotion. |
| Grouping unavailable | Simulate timeout, input-limit rejection or budget exhaustion after promoting a verified rules summary. | Keep the exact previously promoted rules artifact. |
| Traceability | Promote a valid French Canadian artifact, record a view and then promote a later revision. Read the original receipt from the archive. | The receipt still points to the exact archived version and `fr-CA` locale. Its sentences trace to C17/C18 and the original baseline and target hashes. |

Attach a CI run with fixture hashes, expected net change IDs and promotion decisions. Include a separate editorial evaluation if optional model grouping is enabled. The proposal requires held-out releases by market and language, coverage of all critical changes and zero unsupported claims. The deterministic validation suite supplies evidence for the automated promotion checks.
