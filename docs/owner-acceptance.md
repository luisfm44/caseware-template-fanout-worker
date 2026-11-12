# Owner-side acceptance suites

The regional updates API owns the offer shown to an auditor. The summary service owns the generated summary and its publication checks. These fixture specifications define the executable suites each owner needs to run. The worker's PostgreSQL and multi-process tests cover planning and execution contracts. The owner-side suites and their CI evidence are pending. [Update policy and summary design, pp. 3–4](architecture.pdf#page=3)

## Regional updates API

Implementation follow-up [version-selection suite](https://github.com/luisfm44/caseware-template-fanout-worker/issues/26). The [local reference API](../examples/regional-version-selection/README.md) runs the proposed policy over HTTP with controlled dependencies and records [scenario evidence](evidence/issue-26/README.md). The owning API and its CI run remain the acceptance evidence. Run the offer tests through the API with a controlled regional catalog, authenticated firm and file context, and an engagement manager fixture that controls the confirmed baseline and apply status.

Use one product and market with two engagements, A and B, in the same firm. Both initially have confirmed baseline v4. Give each release an immutable revision identifier and explicitly configure the approved paths. Record the selected target and the requested summary pair in the response or downstream call trace.

| Case | Given and when | Required outcome |
| --- | --- | --- |
| Approved upgrade path | v7 is the active target. Run with an approved path from v4, then with that path removed, then with an ambiguous target designation. | The approved case offers v7 and requests the exact v4 → v7 summary. The missing and ambiguous cases hold the offer. |
| Exact decline | A declined the v4 → v5 offer. With v5 active and compatible, request offers for A and B. In a separate fixture replace A's decline with v3 → v5 while its confirmed baseline remains v4. | A's exact decline suppresses its v5 offer. B still receives v5. The v3 → v5 decline leaves A's v4 → v5 offer available. |
| Later compatible target | A declined v4 → v5. Keep its baseline at v4 and designate v7 as the compatible target. | A receives the v7 offer and one cumulative v4 → v7 summary. |
| Withdrawn target | Withdraw active v7. Test with no replacement, an incompatible replacement and an explicitly designated compatible replacement. Include a file whose confirmed baseline is already v7. | New v7 offers are withdrawn. Only the designated compatible replacement can be offered. The confirmed v7 baseline stays v7 for the already-applied file. |
| Unconfirmed baseline | Remove the confirmed baseline while the owner reports an unknown or flagged version. | Hold the version-specific offer and summary request until verification establishes a baseline. |
| Apply outcome | Submit an allowed apply decision. Keep the owner response pending, then report failure or success in separate runs. | Pending and failed outcomes leave the confirmed baseline unchanged. Only confirmed success advances it. |

Capture fixture inputs and assertions, including the catalog revision and the effective baseline used by the API. Run the suite in the API owner's CI and attach the run to the follow-up. Evidence of the withdrawal-versus-admission race belongs with the engagement manager and template authority once Product approves the ordering policy described in the proposal.

## Summary service

Implementation follow-up [summary-validation suite](https://github.com/luisfm44/caseware-template-fanout-worker/issues/27). Run the real comparison, validation and rendering paths with archived template fixtures and a deterministic substitute for optional model grouping. Build one fixture per approved version and pin the exact content hashes used by the service's artifact contract. Change the returned bytes independently of the requested key to exercise hash validation.

Use a v4 baseline with `/inventory/signoffRequired` set to `false` and no `/inventory/exceptionReason`. Let v5 add a temporary field that v7 removes. In v7, require sign-off and add the exception-reason field. Label those two net changes C17 and C18 in the grouping fixture and supply the permitted topic set for each.

| Case | Given and when | Required outcome |
| --- | --- | --- |
| Exact pair and net diff | Compare the approved v4 and v7 artifacts. Run separately with a baseline hash mismatch and a target hash mismatch. | The correct pair produces C17 and C18. The temporary field's addition and reversal leave no net change. Either hash mismatch stops generation and promotion for that pair. |
| Complete valid grouping | Group both IDs exactly once under allowed topics. Render in the requested locale. | Every rendered sentence maps to verified change IDs and typed before/after values. The exception-reason addition describes field presence. A requirement to fill it needs separate evidence. |
| Omitted, duplicate or unknown change | Supply one grouping per failure with C18 missing, C17 duplicated, or a fabricated ID. Also try an otherwise valid grouping with a disallowed topic or extra prose. | Each invalid grouping is rejected before promotion. A complete rules summary can be served for the verified pair. |
| Unsupported material edit | Include a material change with no safe approved wording. | Hold the new summary for editor review. A partial rules summary remains ineligible for promotion. |
| Grouping unavailable | Supply invalid JSON or simulate timeout, input-limit rejection or budget exhaustion with a verified and complete rules summary available. | Keep the rules summary. The failed grouping leaves the promoted artifact unchanged. |
| Traceability | Promote a valid localized artifact, then render a later revision. Read the original view receipt. | The receipt still points to the exact archived version and locale. Its sentences trace to C17/C18 and the original base and target hashes. |

Attach a CI run with fixture hashes, expected net change IDs and promotion decisions. Include a separate editorial evaluation if optional model grouping is enabled. The proposal requires held-out releases by market and language, coverage of all critical changes and zero unsupported claims. The deterministic validation suite supplies evidence for the automated promotion checks.
