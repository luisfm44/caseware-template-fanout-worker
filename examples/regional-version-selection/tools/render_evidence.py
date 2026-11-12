#!/usr/bin/env python3
"""Render a Caseware mockup's Surefire results and captured HTTP traffic as offline HTML."""

from __future__ import annotations

import argparse
import base64
import binascii
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from html import escape
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


@dataclass(frozen=True)
class Scenario:
    test: str
    slug: str
    title: str
    expected: str


@dataclass(frozen=True)
class Exchange:
    method: str
    path: str
    firm: str
    target: str
    status: str
    response: str


@dataclass(frozen=True)
class Result:
    outcome: str
    duration: Decimal
    events: tuple[Exchange | str, ...]


SCENARIOS = (
    Scenario("offersAnApprovedTargetAndTheExactCumulativeSummaryPair",
             "approved-path", "Approved upgrade path",
             "The confirmed version stays at v4. The API offers v7 with the cumulative summary pair v4 → v7."),
    Scenario("withholdsAnOfferWithoutOneApprovedTarget(String)[1]",
             "missing-path", "Missing upgrade path",
             "The baseline stays at v4. Without an approved path, the API withholds the offer and summary pair."),
    Scenario("withholdsAnOfferWithoutOneApprovedTarget(String)[2]",
             "ambiguous-target", "Ambiguous target",
             "With two active targets, the API withholds the offer and summary pair. The baseline stays at v4."),
    Scenario("exactDeclineIsScopedToTheFileAndConfirmedBaseline",
             "exact-decline", "Decline scoped to the file and baseline",
             "Declining v4 → v5 suppresses that offer for file A. File B and the v3 → v5 path remain eligible."),
    Scenario("anExactDeclineDoesNotSuppressALaterCompatibleTarget",
             "later-offer", "Later compatible offer",
             "After declining v5, the file receives the new v7 offer with the summary pair v4 → v7."),
    Scenario("handlesWithdrawalWithoutRewritingTheConfirmedBaseline(String)[1]",
             "withdrawal-no-replacement", "Withdrawal without a replacement",
             "The API withholds the offer for the v4 file. The file already on v7 stays at v7."),
    Scenario("handlesWithdrawalWithoutRewritingTheConfirmedBaseline(String)[2]",
             "withdrawal-incompatible-replacement", "Withdrawal with an incompatible replacement",
             "The API withholds the v8 offer for the v4 file. The file already on v7 stays at v7."),
    Scenario("handlesWithdrawalWithoutRewritingTheConfirmedBaseline(String)[3]",
             "withdrawal-compatible-replacement", "Withdrawal with a compatible replacement",
             "The API offers v8 from v4 along the approved path. The file already on v7 stays at v7."),
    Scenario("holdsTheOfferAndSummaryWhenTheManagerHasNoConfirmedBaseline",
             "unconfirmed-baseline", "Unconfirmed baseline",
             "The API returns null for the baseline, target, and summary pair."),
    Scenario("baselineAdvancesOnlyAfterTheManagerConfirmsSuccess(ApplyStatus)[1]",
             "application-failed", "Pending application that fails",
             "The API accepts the request and returns PENDING. After FAILED, the confirmed version stays at v4."),
    Scenario("baselineAdvancesOnlyAfterTheManagerConfirmsSuccess(ApplyStatus)[2]",
             "application-confirmed", "Pending application that succeeds",
             "The confirmed version stays at v4 during PENDING and advances to v7 after APPLIED."),
    Scenario("rejectsAStaleTargetBeforeCallingTheManager",
             "stale-target", "Decision for a stale target",
             "The API returns 409 for requests to apply or decline v7 while the current offer is v8."),
    Scenario("usesTheAuthenticatedFirmAndKeepsAnotherFirmsFileOutOfReach",
             "firm-isolation", "Authentication and firm isolation",
             "The API returns 401 without authentication, 404 for another firm's file, and 200 for the firm that owns it."),
)


STYLES = """
:root { color-scheme: light; font-family: system-ui, sans-serif; color: #152741; background: #edf2f8; }
* { box-sizing: border-box; }
body { margin: 0; }
main { width: min(1110px, calc(100% - 52px)); margin: 26px auto; }
header { padding: 24px 32px; background: #142b49; color: white; border-radius: 14px; }
header a { color: #b7d4f7; }
h1 { margin: 7px 0 10px; font-size: clamp(25px, 3.3vw, 42px); line-height: 1.12; letter-spacing: -.03em; }
h2 { font-size: 17px; margin: 0 0 6px; }
p { margin: 0; line-height: 1.45; }
.kicker, .meta { font-size: 14px; color: #c4d5ea; }
.kicker { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.kicker span + span { padding-left: 10px; border-left: 1px solid #6c829c; }
.metrics { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.badge { display: inline-block; font-size: 13px; font-weight: 750; padding: 4px 9px; border-radius: 5px; }
.pass { background: #d9f7e7; color: #095a38; }
.fail { background: #ffe1e5; color: #8a1933; }
.skip { background: #fff0cc; color: #724800; }
.assertion, .empty { margin: 12px 0; padding: 12px 18px; background: #fff; border: 1px solid #cad6e4; border-radius: 10px; }
.assertion strong { margin-right: 7px; }
.timeline { padding: 0; margin: 0; list-style: none; display: grid; gap: 7px; }
.fixture { padding: 7px 14px; background: #e2edf9; border-left: 3px solid #4c7ab4; font-size: 13px; }
.fixture strong { margin-right: 6px; }
.http { padding: 9px 14px; border: 1px solid #cad6e4; border-radius: 9px; background: white; }
.request { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
.request code { overflow-wrap: anywhere; }
.method, .code { font-family: ui-monospace, monospace; font-weight: 750; }
.method { color: #1d57a2; }
.code { margin-left: auto; }
.firm, .target { font-size: 12px; color: #455870; }
.response { margin-top: 5px; padding: 6px 9px; background: #f2f5f9; border-radius: 5px; }
.response code { font-size: 13px; overflow-wrap: anywhere; }
.empty-body { font-size: 12px; color: #4a5b71; }
footer { margin-top: 13px; font-size: 12px; color: #455870; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(275px, 1fr)); gap: 10px; margin-top: 12px; }
.grid a { display: block; text-decoration: none; color: inherit; padding: 15px; border: 1px solid #cad6e4; border-radius: 9px; background: white; }
.grid a:hover, .grid a:focus { border-color: #315f98; }
.grid h2 { margin-top: 8px; }
.grid p { font-size: 14px; color: #455870; }
"""


def parse_exchange(line):
    fields = line.split("\t")
    if len(fields) != 7:
        raise ValueError("incomplete HTTP trace")
    marker, method, path, firm, target, status, encoded_body = fields
    if marker != "CASEWARE_HTTP" or method not in {"GET", "POST"} or not path.startswith("/") or not firm:
        raise ValueError("invalid HTTP request")
    if not re.fullmatch(r"[1-5][0-9]{2}", status):
        raise ValueError("invalid HTTP status code")
    try:
        response = base64.b64decode(encoded_body, validate=True).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError) as error:
        raise ValueError("invalid HTTP response body") from error
    return Exchange(method, path, firm, target, status, response)


def parse_events(testcase):
    events = []
    for output in testcase.findall("system-out"):
        for line in (output.text or "").splitlines():
            if line.startswith("CASEWARE_HTTP"):
                events.append(parse_exchange(line))
            elif line.startswith("CASEWARE_FIXTURE"):
                marker, separator, action = line.partition("\t")
                if marker != "CASEWARE_FIXTURE" or not separator or not action.strip():
                    raise ValueError("invalid test data event")
                events.append(action.strip())
    return tuple(events)


def read_results(xml, scenarios):
    suite = ET.parse(xml).getroot()
    expected = {scenario.test for scenario in scenarios}
    results = {}
    for testcase in suite.findall("testcase"):
        name = testcase.get("name")
        if name not in expected:
            raise ValueError(f"unrecognized test in Surefire: {name}")
        if name in results:
            raise ValueError(f"duplicate test in Surefire: {name}")
        try:
            duration = Decimal(testcase.get("time", ""))
            if not duration.is_finite() or duration < 0:
                raise ValueError("invalid duration")
            if testcase.find("failure") is not None or testcase.find("error") is not None:
                outcome = "fail"
            elif testcase.find("skipped") is not None:
                outcome = "skip"
            else:
                outcome = "pass"
            events = parse_events(testcase)
            if outcome == "pass" and not any(isinstance(event, Exchange) for event in events):
                raise ValueError("missing HTTP requests for a passing test")
        except (InvalidOperation, ValueError) as error:
            raise ValueError(f"{name}: {error}") from error
        results[name] = Result(outcome, duration, events)
    if missing := expected - results.keys():
        raise ValueError(f"missing tests in Surefire: {', '.join(sorted(missing))}")
    return results


def badge(outcome):
    labels = {"pass": "PASSED", "fail": "FAILED", "skip": "SKIPPED"}
    return f'<span class="badge {outcome}">{labels[outcome]}</span>'


def filename(index, scenario):
    return f"{index:02d}-{scenario.slug}.html"


def layout(title, contents, issue):
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{escape(title)} · Caseware #{issue}</title>
<style>{STYLES}</style>
</head>
<body><main>{contents}
<footer>Evidence source · Maven Surefire XML and HTTP client traces from the local mockup.</footer>
</main></body>
</html>
"""


def render_exchange(event):
    response = event.response
    if response:
        try:
            response = json.dumps(json.loads(response), ensure_ascii=False)
        except json.JSONDecodeError:
            pass
        body = f"<code>{escape(response)}</code>"
    else:
        body = '<span class="empty-body">No response body</span>'
    target = f'<span class="target">Target <code>{escape(event.target)}</code></span>' if event.target else ""
    return f"""
<li class="http">
    <div class="request"><span class="method">{escape(event.method)}</span><code>{escape(event.path)}</code>
    <span class="firm">{escape(event.firm)}</span>{target}<span class="code">HTTP {event.status}</span></div>
    <div class="response">{body}</div>
</li>"""


def render_case(index, scenario, result, total, issue):
    http_count = sum(isinstance(event, Exchange) for event in result.events)
    request_label = "request" if http_count == 1 else "requests"
    steps = "\n".join(
        render_exchange(event) if isinstance(event, Exchange)
        else f'<li class="fixture"><strong>Test data</strong> {escape(event)}</li>'
        for event in result.events
    )
    timeline = f'<ol class="timeline">{steps}</ol>' if steps else '<p class="empty">No recorded events.</p>'
    content = f"""
<header>
    <div class="kicker"><a href="index.html">End-to-end tests</a><span>Scenario {index:02d} / {total}</span></div>
    <h1>{escape(scenario.title)}</h1>
    <div class="metrics">{badge(result.outcome)}
      <span class="meta">{http_count} HTTP {request_label} · {result.duration:.3f} s</span></div>
</header>
<p class="assertion"><strong>Expected result</strong> {escape(scenario.expected)}</p>
{timeline}
"""
    return layout(scenario.title, content, issue)


def render_index(results, scenarios, title, issue):
    links = []
    for index, scenario in enumerate(scenarios, start=1):
        result = results[scenario.test]
        links.append(f"""
<a href="{filename(index, scenario)}">{badge(result.outcome)}
<h2>{index:02d} · {escape(scenario.title)}</h2>
<p>{escape(scenario.expected)}</p></a>""")
    counts = {status: sum(r.outcome == status for r in results.values()) for status in ("pass", "fail", "skip")}
    return layout(title, f"""
<header>
    <div class="kicker">Caseware · Issue #{issue}</div>
    <h1>{escape(title)}</h1>
    <p class="meta">{len(results)} tests · {counts['pass']} passed · {counts['fail']} failed · {counts['skip']} skipped</p>
</header>
<div class="grid">{''.join(links)}</div>
""", issue)


def read_manifest(path):
    if path is None:
        return 26, "Regional mockup results", SCENARIOS
    data = json.loads(path.read_text(encoding="utf-8"))
    try:
        issue = int(data["issue"])
        title = data["title"]
        scenarios = tuple(Scenario(**row) for row in data["scenarios"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("invalid scenario manifest") from error
    if issue < 1 or not isinstance(title, str) or not title.strip() or not scenarios:
        raise ValueError("invalid report metadata")
    for scenario in scenarios:
        if not all(isinstance(value, str) and value.strip()
                   for value in (scenario.test, scenario.slug, scenario.title, scenario.expected)):
            raise ValueError("invalid scenario description")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", scenario.slug):
            raise ValueError("invalid scenario filename")
    if len({s.test for s in scenarios}) != len(scenarios):
        raise ValueError("duplicate scenario test")
    return issue, title, scenarios


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("surefire_xml", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--manifest", type=Path, help="Read scenario metadata for a different suite.")
    args = parser.parse_args()
    try:
        issue, title, scenarios = read_manifest(args.manifest)
        results = read_results(args.surefire_xml, scenarios)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        for index, scenario in enumerate(scenarios, start=1):
            (args.output_dir / filename(index, scenario)).write_text(
                render_case(index, scenario, results[scenario.test], len(scenarios), issue), encoding="utf-8"
            )
        (args.output_dir / "index.html").write_text(
            render_index(results, scenarios, title, issue), encoding="utf-8")
    except (ET.ParseError, OSError, ValueError) as error:
        parser.exit(1, f"Could not generate the report: {error}\n")


if __name__ == "__main__":
    main()
