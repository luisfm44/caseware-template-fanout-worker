# Evidencia HTTP del mockup regional · #26

La ejecución con JDK 25 terminó con 13 escenarios aprobados, 35 solicitudes HTTP y cero fallos. Spring Boot atendió las solicitudes en un puerto local y la suite verificó cada respuesta. El informe se generó a partir del XML de Surefire y de la traza HTTP que registró el cliente durante las pruebas.

[Instrucciones para ejecutar la suite y regenerar el informe](../../../examples/regional-version-selection/README.md)

| Escenario | Resultado observado | Captura |
| --- | --- | --- |
| 01 · Ruta aprobada | `200`. La API ofrece v7 con el par de resumen v4 → v7. | [Abrir](01-ruta-aprobada.png) |
| 02 · Ruta ausente | `200`. La oferta y el par del resumen quedan en `null`. | [Abrir](02-ruta-ausente.png) |
| 03 · Destino ambiguo | `200`. La oferta queda retenida ante dos destinos designados. | [Abrir](03-destino-ambiguo.png) |
| 04 · Rechazo exacto | `204` al rechazar. El archivo rechazado pierde esa oferta. Otro archivo y otra versión base conservan sus ofertas. | [Abrir](04-rechazo-exacto.png) |
| 05 · Oferta posterior | Después de rechazar v5, la API ofrece v7 con el par v4 → v7. | [Abrir](05-oferta-posterior.png) |
| 06 · Retiro sin reemplazo | La oferta queda retenida y el archivo que ya aplicó v7 conserva v7. | [Abrir](06-retiro-sin-reemplazo.png) |
| 07 · Retiro con reemplazo incompatible | La oferta queda retenida y las versiones confirmadas se conservan. | [Abrir](07-retiro-reemplazo-incompatible.png) |
| 08 · Retiro con reemplazo compatible | La API ofrece v8 desde v4. El archivo que ya aplicó v7 conserva v7. | [Abrir](08-retiro-reemplazo-compatible.png) |
| 09 · Base sin confirmar | `200` con versión confirmada, oferta y resumen en `null`. | [Abrir](09-base-sin-confirmar.png) |
| 10 · Aplicación fallida | `202` con `PENDING`, luego `FAILED`. La versión confirmada queda en v4. | [Abrir](10-aplicacion-fallida.png) |
| 11 · Aplicación confirmada | `202` con `PENDING`, luego `APPLIED`. La versión confirmada pasa a v7. | [Abrir](11-aplicacion-confirmada.png) |
| 12 · Destino obsoleto | La API devuelve `409` al aplicar o rechazar el destino anterior y devuelve la nueva oferta al consultar. | [Abrir](12-destino-obsoleto.png) |
| 13 · Aislamiento entre firmas | `401` sin autenticación y `404` en consultas e intentos de aplicación sobre un archivo de otra firma. Su firma dueña obtiene `200`. | [Abrir](13-aislamiento-entre-firmas.png) |

La evidencia corresponde a la API de referencia con catálogo y manager controlados. La ejecución en CI de la API regional dueña se sigue en el [issue #26](https://github.com/luisfm44/caseware-template-fanout-worker/issues/26).
