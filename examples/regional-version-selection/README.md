# Mockup regional de selección de versiones

Este ejemplo ejecuta el [issue #26](https://github.com/luisfm44/caseware-template-fanout-worker/issues/26) en una API de referencia. Spring Boot inicia un servidor HTTP en `127.0.0.1` con un puerto dinámico. Los tests autentican dos firmas sintéticas, envían solicitudes HTTP reales y comparan las respuestas con los resultados esperados. El catálogo regional y el engagement manager son adaptadores de prueba. La API calcula la oferta y el par de versiones para el resumen.

## Ejecutar

Con JDK 25, desde la raíz del repositorio:

```sh
./mvnw -f examples/regional-version-selection/pom.xml test
```

En Windows, usa `mvnw.cmd` con los mismos argumentos. Maven descarga las dependencias en la primera ejecución. El servidor se inicia y se detiene con la suite.

## Ver el resultado de cada escenario

Con Python 3, desde la raíz del repositorio:

```sh
python3 examples/regional-version-selection/tools/render_evidence.py \
  examples/regional-version-selection/target/surefire-reports/TEST-io.github.luisfm44.caseware.regional.RegionalVersionSelectionHttpTest.xml \
  examples/regional-version-selection/target/evidence
```

Abre `examples/regional-version-selection/target/evidence/index.html`. El informe muestra el estado del test, los cambios que hizo el fixture y las solicitudes y respuestas HTTP observadas. Si Maven reporta fallos, ejecuta el comando del informe después de que termine la suite para verlos. Las credenciales sintéticas se excluyen de la traza.

Las [capturas por escenario](../../docs/evidence/issue-26/README.md) corresponden a una ejecución verificada.

## Qué puede devolver la API

| Solicitud | Resultados que comprueba la suite |
| --- | --- |
| `GET /engagements/{id}/offer` | `200` con destino elegible y par de resumen, o con ambos campos en `null` cuando la oferta queda retenida. |
| `POST /engagements/{id}/decline` | `204` al registrar el rechazo exacto. `409` si el destino dejó de estar ofrecido. |
| `POST /engagements/{id}/apply` | `202` con una solicitud pendiente. `409` si el destino dejó de estar ofrecido. |
| `GET /engagements/{id}/applications/{decisionId}` | `200` con `PENDING`, `FAILED` o `APPLIED`. Solo la confirmación del manager cambia la versión efectiva. |
| Acceso a la oferta y aplicación | `401` sin autenticación para la consulta de oferta. `404` al intentar consultar o aplicar en un archivo de otra firma. |

La suite verifica el comportamiento de la API de referencia con datos sintéticos. Para cerrar el #26 queda pendiente ejecutar la suite de aceptación contra la API regional dueña y vincular su resultado de CI.
