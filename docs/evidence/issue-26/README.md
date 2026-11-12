# Regional mockup HTTP evidence · Issue #26

The JDK 25 run passed all 13 scenarios with 35 HTTP requests and zero failures. Spring Boot served the requests locally and the suite asserted each response. The report uses the Surefire XML and the real HTTP trace collected by the test client.

[Run the suite and regenerate the report](../../../examples/regional-version-selection/README.md) · [GitHub Actions reports](https://github.com/luisfm44/caseware-template-fanout-worker/actions/workflows/regional-version-selection.yml)

| Scenario | Observed outcome | Screenshot |
| --- | --- | --- |
| 01 · Approved path | `200`. The API offers v7 and returns the v4 → v7 summary pair. | [Open](01-approved-path.png) |
| 02 · Missing path | `200`. The target and summary pair are both `null`. | [Open](02-missing-path.png) |
| 03 · Ambiguous target | `200`. The API withholds the offer when two targets are designated. | [Open](03-ambiguous-target.png) |
| 04 · Exact decline | `204` after the decline. It suppresses the offer for the exact file, baseline and target. Another file and another baseline retain their offers. | [Open](04-exact-decline.png) |
| 05 · Later offer | After the v5 decline, the API offers v7 with the v4 → v7 summary pair. | [Open](05-later-offer.png) |
| 06 · Withdrawal without replacement | The offer is withheld and the file that already applied v7 retains v7. | [Open](06-withdrawal-no-replacement.png) |
| 07 · Withdrawal with incompatible replacement | The offer is withheld and confirmed baselines stay unchanged. | [Open](07-withdrawal-incompatible-replacement.png) |
| 08 · Withdrawal with compatible replacement | The API offers v8 from v4. The file that already applied v7 retains v7. | [Open](08-withdrawal-compatible-replacement.png) |
| 09 · Unconfirmed baseline | `200` with the confirmed baseline, target and summary all set to `null`. | [Open](09-unconfirmed-baseline.png) |
| 10 · Application failed | `202` with `PENDING`, followed by `FAILED`. The confirmed baseline stays at v4. | [Open](10-application-failed.png) |
| 11 · Application confirmed | `202` with `PENDING`, followed by `APPLIED`. The confirmed baseline advances to v7. | [Open](11-application-confirmed.png) |
| 12 · Stale target | The API returns `409` for application or decline of the old target and returns the new offer on the next read. | [Open](12-stale-target.png) |
| 13 · Firm isolation | The API returns `401` without authentication and `404` for reads and applications on another firm's file. The owning firm receives `200`. | [Open](13-firm-isolation.png) |

The evidence comes from the reference API with a controlled catalog and engagement manager. The owning regional API's CI evidence is tracked in [issue #26](https://github.com/luisfm44/caseware-template-fanout-worker/issues/26).
