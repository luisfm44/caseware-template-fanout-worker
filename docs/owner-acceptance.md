# Reference API evidence and regional acceptance

This repository contains the Java worker and two local reference APIs. The proposed regional architecture assigns offer decisions to a regional updates API and summary promotion to a summary service. Those responsibilities describe deployment boundaries. Issues #26 and #27 delivered the standalone reference suites for those boundaries. Issue #35 adds executable evidence of the two references working together over HTTP. [Connected test guide](../examples/connected-reference-apis/README.md) · [Repository test evidence](evidence/README.md) · [Update policy and summary design, pp. 3–4](architecture.pdf#page=3)

The connected suite forwards the offer's exact pair, checks the approved bytes and the cumulative summary, and reads the original receipt after a later revision. It also confirms that a withheld offer sends no summary request and a hash mismatch stops the flow. The test driver coordinates both loopback calls and the reference archive lasts for the test process. Validate service-to-service communication, persistence across restarts and regional delivery when the deployment exists. [Regional rollout criteria](regional-verification.md)

## Regional updates API

The reference suite uses a controlled regional catalog, authenticated firm and file context, and an engagement manager fixture that controls the confirmed baseline and apply status. Apply these scenarios to the deployed regional API and its actual dependencies when a regional environment is available. [Issue #26](https://github.com/luisfm44/caseware-template-fanout-worker/issues/26) · [Local reference API](../examples/regional-version-selection/README.md) · [Scenario evidence](evidence/issue-26/README.md)

Use one product and market with two engagements, A and B, in the same firm. Both initially have confirmed baseline v4. Give each release an immutable revision identifier and explicitly configure the approved paths. Record the selected target and the requested summary pair in the response or downstream call trace.

| Case | Given and when | Required outcome |
| --- | --- | --- |
| Approved upgrade path | v7 is the active target. Run with an approved path from v4, then with that path removed, then with an ambiguous target designation. | The approved case offers v7 and requests the exact v4 → v7 summary. The missing and ambiguous cases hold the offer. |
| Exact decline | A declined the v4 → v5 offer. With v5 active and compatible, request offers for A and B. In a separate fixture replace A's decline with v3 → v5 while its confirmed baseline remains v4. | A's exact decline suppresses its v5 offer. B still receives v5. The v3 → v5 decline leaves A's v4 → v5 offer available. |
| Later compatible target | A declined v4 → v5. Keep its baseline at v4 and designate v7 as the compatible target. | A receives the v7 offer and one cumulative v4 → v7 summary. |
| Withdrawn target | Withdraw active v7. Test with no replacement, an incompatible replacement and an explicitly designated compatible replacement. Include a file whose confirmed baseline is already v7. | New v7 offers are withdrawn. Only the designated compatible replacement can be offered. The confirmed v7 baseline stays v7 for the already-applied file. |
| Unconfirmed baseline | Remove the confirmed baseline while the owner reports an unknown or flagged version. | Hold the version-specific offer and summary request until verification establishes a baseline. |
| Apply outcome | Submit an allowed apply decision. Keep the owner response pending, then report failure or success in separate runs. | Pending and failed outcomes leave the confirmed baseline unchanged. Only confirmed success advances it. |
| Original offer receipt | Read v4 → v7, then change the confirmed baseline to v5 with v7 still eligible. Separately change only the engagement revision or catalog epoch, or try the receipt on another file or firm. | Both decisions reject the stale or mismatched receipt before recording anything. A new eligible offer carries a new receipt. |
| Atomic admission and replay | Submit the same receipt concurrently. Exercise withdrawal first on an unused receipt, and admission first followed by withdrawal and replay. | The same action records one decision. Withdrawal first rejects a new admission. An earlier admission replays its existing decision. Completion checks the expected file revision before updating it. |

The regional mock exercises these receipt and replay cases with an in-memory catalog and engagement manager that share an admission lock. A deployed manager would durably bind the receipt to the authorized auditor and file and order decision admission with the template authority. [Receipt contract and test results](../examples/regional-version-selection/README.md#make-a-decision-with-the-original-receipt) · [Issue #34](https://github.com/luisfm44/caseware-template-fanout-worker/issues/34)

For regional validation, capture the catalog revision, effective baseline, offered target and requested summary pair. Exercise the deployed authentication and authorization path for unauthenticated requests, the owning firm and another firm. Record the regional CI run and test the withdrawal-versus-admission race with the engagement manager and template authority once Product approves the ordering policy.

## Summary service

The reference suite executes comparison, validation and rendering over HTTP using approved template fixtures, deterministic grouping and an in-memory archive. Pin the expected SHA-256 hash of each approved version. Change the bytes returned for the same key and confirm the service checks both artifacts before generating a summary. For regional acceptance, run equivalent scenarios with the deployed artifact store, renderer and durable archive. [Issue #27](https://github.com/luisfm44/caseware-template-fanout-worker/issues/27) · [Local reference API](../examples/summary-validation/README.md) · [Repository test evidence](evidence/README.md)

Use a v4 baseline with `/inventory/signoffRequired` set to `false` and no `/inventory/exceptionReason`. Let v5 add a temporary field that v7 removes. In v7, require sign-off and add the exception-reason field. Label those two net changes C17 and C18 in the grouping fixture and supply the permitted topic set for each.

| Case | Given and when | Required outcome |
| --- | --- | --- |
| Exact pair and net diff | Compare the approved v4 and v7 artifacts. Run separately with a baseline hash mismatch and a target hash mismatch. | The correct pair produces C17 and C18. The temporary field's addition and reversal leave no net change. Either hash mismatch stops generation and promotion for that pair. |
| Complete valid grouping | Group both IDs exactly once under allowed topics. Render the approved text in the requested locale. | Every rendered sentence maps to a verified change ID, JSON Pointer, operation and typed before/after values. The exception-reason addition describes field presence. A requirement to fill it needs separate evidence. |
| Invalid grouping | Omit C18, duplicate C17 or supply a fabricated ID. In separate cases use a disallowed topic, extra prose, trailing content or malformed JSON. | Each invalid grouping is rejected before promotion. The service keeps an existing verified summary for that pair and locale, or serves a complete rules summary if none exists. |
| Unsupported material edit | Include a material change with no safe approved wording. | Hold the new summary for editor review. A partial rules summary remains ineligible for promotion. |
| Grouping unavailable | Simulate timeout, input-limit rejection or budget exhaustion after promoting a verified rules summary. | Keep the exact previously promoted rules artifact. |
| Traceability | Promote a valid French Canadian artifact, record a view and then promote a later revision. Read the original receipt from the archive. | The receipt still points to the exact archived version and `fr-CA` locale. Its sentences trace to C17/C18 and the original baseline and target hashes. |

For a deployed summary service, attach a CI run with fixture hashes, expected net change IDs and promotion decisions. Include a separate editorial evaluation if optional model grouping is enabled. The proposal requires held-out releases by market and language, coverage of all critical changes and zero unsupported claims. The deterministic validation suite supplies evidence for the automated promotion checks.
