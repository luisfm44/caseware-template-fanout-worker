#!/usr/bin/env python3
"""Render the regional mock's Surefire results and captured HTTP traffic as offline HTML."""

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
             "ruta-aprobada", "Ruta aprobada",
             "La versión confirmada sigue en v4. La API ofrece v7 y devuelve el par acumulativo v4 → v7."),
    Scenario("withholdsAnOfferWithoutOneApprovedTarget(String)[1]",
             "ruta-ausente", "Ruta de actualización ausente",
             "La base v4 se conserva. La API retiene la oferta y el par de resumen cuando falta una ruta aprobada."),
    Scenario("withholdsAnOfferWithoutOneApprovedTarget(String)[2]",
             "destino-ambiguo", "Destino ambiguo",
             "Dos destinos activos dejan la oferta y el resumen sin determinar. La base v4 se conserva."),
    Scenario("exactDeclineIsScopedToTheFileAndConfirmedBaseline",
             "rechazo-exacto", "Rechazo por archivo y versión base",
             "El rechazo retiene v4 → v5 en el archivo A. El archivo B y la ruta v3 → v5 siguen disponibles."),
    Scenario("anExactDeclineDoesNotSuppressALaterCompatibleTarget",
             "oferta-posterior", "Oferta compatible posterior",
             "Tras rechazar v5, el archivo todavía recibe la nueva oferta v7 con el par v4 → v7."),
    Scenario("handlesWithdrawalWithoutRewritingTheConfirmedBaseline(String)[1]",
             "retiro-sin-reemplazo", "Retiro sin reemplazo",
             "Se retiene la oferta para v4. El archivo que ya tiene v7 conserva esa versión."),
    Scenario("handlesWithdrawalWithoutRewritingTheConfirmedBaseline(String)[2]",
             "retiro-reemplazo-incompatible", "Retiro con reemplazo incompatible",
             "El destino v8 queda retenido para v4. El archivo que ya tiene v7 conserva esa versión."),
    Scenario("handlesWithdrawalWithoutRewritingTheConfirmedBaseline(String)[3]",
             "retiro-reemplazo-compatible", "Retiro con reemplazo compatible",
             "La API ofrece v8 desde v4 por la ruta aprobada. El archivo que ya tiene v7 conserva esa versión."),
    Scenario("holdsTheOfferAndSummaryWhenTheManagerHasNoConfirmedBaseline",
             "base-sin-confirmar", "Versión base sin confirmar",
             "La versión base, el destino y el par de resumen se devuelven como nulos."),
    Scenario("baselineAdvancesOnlyAfterTheManagerConfirmsSuccess(ApplyStatus)[1]",
             "aplicacion-fallida", "Aplicación pendiente y fallida",
             "La API acepta la solicitud y devuelve PENDING. Tras FAILED, la versión confirmada sigue en v4."),
    Scenario("baselineAdvancesOnlyAfterTheManagerConfirmsSuccess(ApplyStatus)[2]",
             "aplicacion-confirmada", "Aplicación pendiente y confirmada",
             "La versión confirmada sigue en v4 durante PENDING y avanza a v7 después de APPLIED."),
    Scenario("rejectsAStaleTargetBeforeCallingTheManager",
             "destino-obsoleto", "Decisión para un destino obsoleto",
             "La API devuelve 409 al aplicar o rechazar v7 cuando la oferta vigente es v8."),
    Scenario("usesTheAuthenticatedFirmAndKeepsAnotherFirmsFileOutOfReach",
             "aislamiento-entre-firmas", "Autenticación y aislamiento entre firmas",
             "La API devuelve 401 sin autenticación, 404 para un archivo de otra firma y 200 para su firma dueña."),
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
        raise ValueError("traza HTTP incompleta")
    marker, method, path, firm, target, status, encoded_body = fields
    if marker != "CASEWARE_HTTP" or method not in {"GET", "POST"} or not path.startswith("/") or not firm:
        raise ValueError("solicitud HTTP inválida")
    if not re.fullmatch(r"[1-5][0-9]{2}", status):
        raise ValueError("código HTTP inválido")
    try:
        response = base64.b64decode(encoded_body, validate=True).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError) as error:
        raise ValueError("cuerpo HTTP inválido") from error
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
                    raise ValueError("evento de datos de prueba inválido")
                events.append(action.strip())
    return tuple(events)


def read_results(xml):
    suite = ET.parse(xml).getroot()
    expected = {scenario.test for scenario in SCENARIOS}
    results = {}
    for testcase in suite.findall("testcase"):
        name = testcase.get("name")
        if name not in expected:
            raise ValueError(f"prueba no reconocida en Surefire: {name}")
        if name in results:
            raise ValueError(f"prueba duplicada en Surefire: {name}")
        try:
            duration = Decimal(testcase.get("time", ""))
            if not duration.is_finite() or duration < 0:
                raise ValueError("duración inválida")
            if testcase.find("failure") is not None or testcase.find("error") is not None:
                outcome = "fail"
            elif testcase.find("skipped") is not None:
                outcome = "skip"
            else:
                outcome = "pass"
            events = parse_events(testcase)
            if outcome == "pass" and not any(isinstance(event, Exchange) for event in events):
                raise ValueError("faltan solicitudes HTTP de la prueba aprobada")
        except (InvalidOperation, ValueError) as error:
            raise ValueError(f"{name}: {error}") from error
        results[name] = Result(outcome, duration, events)
    if missing := expected - results.keys():
        raise ValueError(f"faltan pruebas en Surefire: {', '.join(sorted(missing))}")
    return results


def badge(outcome):
    labels = {"pass": "PASÓ", "fail": "FALLÓ", "skip": "OMITIDA"}
    return f'<span class="badge {outcome}">{labels[outcome]}</span>'


def filename(index, scenario):
    return f"{index:02d}-{scenario.slug}.html"


def layout(title, contents):
    return f"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{escape(title)} · Caseware #26</title>
<style>{STYLES}</style>
</head>
<body><main>{contents}
<footer>Fuente de evidencia · XML de Maven Surefire y traza del cliente HTTP del mockup local.</footer>
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
        body = '<span class="empty-body">Sin cuerpo</span>'
    target = f'<span class="target">Destino <code>{escape(event.target)}</code></span>' if event.target else ""
    return f"""
<li class="http">
    <div class="request"><span class="method">{escape(event.method)}</span><code>{escape(event.path)}</code>
    <span class="firm">{escape(event.firm)}</span>{target}<span class="code">HTTP {event.status}</span></div>
    <div class="response">{body}</div>
</li>"""


def render_case(index, scenario, result):
    http_count = sum(isinstance(event, Exchange) for event in result.events)
    request_label = "solicitud" if http_count == 1 else "solicitudes"
    steps = "\n".join(
        render_exchange(event) if isinstance(event, Exchange)
        else f'<li class="fixture"><strong>Datos de prueba</strong> {escape(event)}</li>'
        for event in result.events
    )
    timeline = f'<ol class="timeline">{steps}</ol>' if steps else '<p class="empty">Sin eventos registrados.</p>'
    content = f"""
<header>
    <div class="kicker"><a href="index.html">Pruebas E2E</a><span>Escenario {index:02d} / {len(SCENARIOS)}</span></div>
    <h1>{escape(scenario.title)}</h1>
    <div class="metrics">{badge(result.outcome)}
      <span class="meta">{http_count} {request_label} HTTP · {result.duration:.3f} s</span></div>
</header>
<p class="assertion"><strong>Resultado esperado</strong> {escape(scenario.expected)}</p>
{timeline}
"""
    return layout(scenario.title, content)


def render_index(results):
    links = []
    for index, scenario in enumerate(SCENARIOS, start=1):
        result = results[scenario.test]
        links.append(f"""
<a href="{filename(index, scenario)}">{badge(result.outcome)}
<h2>{index:02d} · {escape(scenario.title)}</h2>
<p>{escape(scenario.expected)}</p></a>""")
    counts = {status: sum(r.outcome == status for r in results.values()) for status in ("pass", "fail", "skip")}
    return layout("Resultados del mockup regional", f"""
<header>
    <div class="kicker">Caseware · Issue #26</div>
    <h1>Resultados del mockup regional</h1>
    <p class="meta">{len(results)} pruebas · {counts['pass']} aprobadas · {counts['fail']} fallidas · {counts['skip']} omitidas</p>
</header>
<div class="grid">{''.join(links)}</div>
""")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("surefire_xml", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    try:
        results = read_results(args.surefire_xml)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        for index, scenario in enumerate(SCENARIOS, start=1):
            (args.output_dir / filename(index, scenario)).write_text(
                render_case(index, scenario, results[scenario.test]), encoding="utf-8"
            )
        (args.output_dir / "index.html").write_text(render_index(results), encoding="utf-8")
    except (ET.ParseError, OSError, ValueError) as error:
        parser.exit(1, f"No se pudo generar el reporte: {error}\n")


if __name__ == "__main__":
    main()
