# Transaction Orchestrator Microservice

**Prueba Técnica — TumiPay**

![Java](https://img.shields.io/badge/Java-17-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen) ![Architecture](https://img.shields.io/badge/Architecture-Hexagonal-orange) ![Tests](https://img.shields.io/badge/Tests-Unitarios%20%7C%20Integración-success)

Microservicio de orquestación de transacciones de pago construido con **Spring Boot 3**, **Arquitectura Hexagonal (Ports & Adapters)**, **Resilience4j** y **Apache Kafka**. Expone una API REST para crear y consultar transacciones, enrutando cada operación al proveedor de pago correspondiente mediante un patrón Strategy + Registry, con auditoría asíncrona, control de idempotencia y protección ante fallos del PSP mediante Circuit Breaker.

---

## Tabla de Contenidos

1. [Decisiones Arquitectónicas](#1-decisiones-arquitectónicas)
2. [Suposiciones](#2-suposiciones)
3. [Riesgos Identificados](#3-riesgos-identificados)
4. [Cómo Ejecutar](#4-cómo-ejecutar)
5. [Contrato API](#5-contrato-api)

---

## 1. Decisiones Arquitectónicas

### 1.1. Arquitectura Hexagonal (Ports & Adapters)

**Decisión:** Separar estrictamente dominio, aplicación e infraestructura mediante puertos e interfaces. Ningún componente interno conoce Spring, JPA ni Kafka.

**Razón:** En pagos los PSPs cambian con frecuencia y los frameworks evolucionan. Esta separación permite sustituir cualquier adaptador externo sin tocar la lógica de negocio, y hace que los tests unitarios del dominio y la aplicación corran en milisegundos sin infraestructura.

---

### 1.2. Puertos primarios en la capa de aplicación, no en el dominio

**Decisión:** `TransactionUseCase` y `CreateTransactionCommand` viven en `application/port/in/`. `AuditUseCase` vive en `application/port/` (colaboración interna entre servicios de aplicación). Los puertos de salida permanecen en `domain/port/out/` porque los define el dominio para satisfacer sus propias necesidades.

**Razón:** Un caso de uso orquesta flujo de aplicación (idempotencia, auditoría, circuit breaker) — no es lógica pura de dominio. Colocarlo en `domain/port/in/` implicaría que el dominio sabe cómo es consumido desde afuera, violando la regla de dependencia hacia adentro.

---

### 1.3. Strategy + Registry para proveedores de pago

**Decisión:** Cada PSP implementa `PaymentProviderPort` como `@Component`. `TransactionService` construye un `Map<String, PaymentProviderPort>` en `@PostConstruct`, indexado por `paymentMethodId`.

**Razón:** Agregar un nuevo PSP es únicamente crear una clase nueva, sin modificar `TransactionService` ni ningún condicional existente (principio Open/Closed). Escala a N proveedores sin degradación de legibilidad.

---

### 1.4. Persistir antes de enviar al proveedor

**Decisión:** La transacción se persiste en estado `PENDING` antes de invocar al PSP. `processedAt` es `null` hasta que el PSP responde; se asigna automáticamente en `Transaction.withStatus()` al llegar a un estado terminal (APPROVED, REJECTED, FAILED, EXPIRED, REVERSED).

**Razón:** Garantiza trazabilidad completa: si el PSP falla o hay timeout, la transacción queda registrada y es reconciliable. Sin esta decisión, un fallo de red entre servicio y PSP produciría una transacción procesada pero no registrada.

---

### 1.5. Monto en centavos (BIGINT)

**Decisión:** Montos almacenados como enteros en centavos (`amount_cents BIGINT`), sin punto decimal.

**Razón:** Evita errores de representación de punto flotante en operaciones financieras. Es la práctica estándar de la industria (Stripe, PayU, Adyen) y está alineada con ISO 4217, que define la unidad mínima de cada moneda.

---

### 1.6. UUID generado por el servicio, no por la base de datos

**Decisión:** `Transaction.create()` genera el UUID v4 antes del INSERT.

**Razón:** Conocer el ID antes de persistir permite construir respuestas y eventos sin depender de un round-trip a la BD, lo que facilita arquitecturas event-driven y simplifica la idempotencia.

---

### 1.7. Flyway para migraciones de esquema

**Decisión:** Flyway gestiona el DDL. La aplicación arranca con `ddl-auto: validate`, que aborta si el esquema en BD no coincide con las entidades JPA.

**Razón:** Versionado y reproducibilidad del esquema en todos los ambientes. Elimina la categoría de bugs "funciona en local pero no en producción" causados por divergencias de esquema no controladas.

---

### 1.8. Circuit Breaker encapsulado como puerto secundario

**Decisión:** `CircuitBreakerPort` es una interfaz del dominio. `CircuitBreakerAdapter` (Resilience4j) la implementa en infraestructura con `@CircuitBreaker(fallbackMethod = "fallbackProcess")`. El fallback retorna `FAILED` para habilitar reconciliación posterior.

**Razón:** El dominio y la aplicación no conocen Resilience4j. Sustituir la librería de circuit breaking, o mockearla en tests, requiere solo reemplazar el adaptador sin tocar ningún caso de uso.

---

### 1.9. Auditoría asíncrona post-commit con Transactional Outbox

**Decisión:** Los eventos de auditoría se publican a Kafka en `TransactionSynchronization.afterCommit()`, garantizando que solo se publican si la transacción principal confirmó. Los eventos también persisten en tablas `customer_audit` y `transaction_audit` como respaldo en BD.

**Razón:** Desacopla la auditoría del flujo transaccional principal. Garantiza consistencia: ningún evento de auditoría se publica para una transacción cuyo commit falló, eliminando registros de auditoría huérfanos.

---

### 1.10. Optimistic Locking con @Version

**Decisión:** Campo `version INTEGER NOT NULL DEFAULT 0` en la tabla `transactions`. JPA lanza `OptimisticLockException` si dos procesos intentan actualizar la misma fila concurrentemente, mapeado al código `006` (HTTP 409).

**Razón:** Alternativa liviana al pessimistic locking (`SELECT FOR UPDATE`), que bloquea filas y degrada el throughput bajo carga. El optimistic locking asume que los conflictos son raros y solo paga el costo cuando realmente ocurren.

---

## 2. Suposiciones

1. **Customer como entidad propia:** Un cliente tiene identidad propia y puede tener múltiples transacciones. Se busca o crea por documento (tipo + número) como clave de idempotencia, permitiendo que un mismo cliente acumule historial de transacciones sin duplicación de datos.

2. **Idempotencia por `client_transaction_id`:** Este campo es la clave de idempotencia provista por el sistema cliente. Si ya existe en la BD, la solicitud es rechazada con código `002`. El cliente es responsable de generar IDs únicos por intento de pago.

3. **Autenticación fuera de scope:** No se implementa autenticación/autorización en esta prueba. En producción se agregaría un API Gateway con OAuth2/JWT antes de exponer el servicio al exterior.

4. **Adaptadores de PSP en el mismo proceso:** En esta prueba el `MockPaymentProviderAdapter` corre en el mismo proceso. En producción, cada adaptador de PSP sería un cliente HTTP hacia un proveedor externo real, implementando el mismo `PaymentProviderPort` sin cambios en el dominio.

5. **PostgreSQL como base de datos:** Se eligió PostgreSQL por su soporte nativo de UUID, `TIMESTAMP WITH TIME ZONE` y su robustez demostrada en aplicaciones financieras con alta concurrencia.

6. **Redpanda en desarrollo:** `docker-compose.yml` usa Redpanda (compatible con protocolo Kafka) por su facilidad de configuración local sin ZooKeeper. En producción se usaría MSK (AWS) o Confluent Platform.

---

## 3. Riesgos Identificados

| Riesgo | Impacto | Probabilidad | Mitigación implementada |
|--------|---------|--------------|------------------------|
| Timeout del PSP sin respuesta | Alto | Media | Circuit Breaker (Resilience4j) con fallback automático; backoff exponencial configurable |
| Duplicación de transacción por retry del cliente | Alto | Alta | Constraint UNIQUE en `client_transaction_id`; verificación previa en `TransactionService` |
| Duplicación de cliente (mismo documento) | Medio | Media | Constraint UNIQUE `(document_type, document_number)`; lógica `findOrCreate` |
| Condición de carrera en actualización | Alto | Media | Optimistic locking con campo `version`; mapeo de `OptimisticLockException` a código `006` |
| Fallo de BD entre persistir y enviar al PSP | Alto | Baja | Estado `PENDING` habilita reconciliación; job programado para reintentar transacciones atascadas |
| Pérdida de eventos de auditoría en Kafka | Medio | Media | `acks=all` en producer; persistencia en BD como backup; Transactional Outbox garantiza consistencia |
| Cambio de esquema sin migración | Alto | Baja | Flyway con migraciones versionadas; `ddl-auto: validate` aborta el arranque si el esquema difiere |
| Exposición de datos sensibles en logs | Alto | Media | No se loguean datos del cliente; usar masking en ELK/Splunk en producción |
| Falta de autenticación | Crítico | N/A (fuera de scope) | Agregar API Gateway con OAuth2/JWT antes de ir a producción |
| Carga alta en la BD | Medio | Media | Servicio stateless escalable horizontalmente; índices en `status`, `payment_method_id`, `created_at`; cache Redis para consultas frecuentes |

---

## 4. Cómo Ejecutar

### Prerrequisitos

- Java 17+
- Maven 3.9+
- Docker & Docker Compose

### Opción 1: Docker Compose (recomendado)

Levanta PostgreSQL, Kafka (Redpanda) y la aplicación en un solo comando:

```bash
git clone <repo>
cd transaction-orchestrator
docker-compose up --build -d
```

Verificar que el servicio está listo:

```bash
curl http://localhost:8080/actuator/health
# Esperado: {"status":"UP"}
```

API disponible en `http://localhost:8080`.

### Ejecutar Tests

```bash
mvn test      # Tests unitarios (sin infraestructura externa, sin Docker)
mvn verify    # Tests unitarios + integración (requiere Docker para TestContainers) + cobertura JaCoCo
```

### Documentación API interactiva

```
http://localhost:8080/swagger-ui.html
```

---

## 5. Contrato API

**Base URL:** `http://localhost:8080/v1/transactions`

Documentación interactiva completa con esquemas y validaciones: `http://localhost:8080/swagger-ui.html`

---

### POST /v1/transactions — Crear transacción

**Descripción de campos del request:**

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `client_transaction_id` | string | ✓ | ID único generado por el cliente; clave de idempotencia |
| `amount_cents` | long | ✓ | Monto en centavos (ej. `150000` = $1.500,00 COP) |
| `currency_code` | string | ✓ | Código ISO 4217 (ej. `COP`, `USD`) |
| `country_code` | string | ✓ | Código ISO 3166-1 alpha-2 (ej. `CO`, `US`) |
| `payment_method_id` | string | ✓ | Identificador del proveedor de pago (ej. `MOCK_PSP`) |
| `webhook_url` | string | ✓ | URL donde se notificará el resultado de la transacción |
| `redirect_url` | string | ✓ | URL de redirección al usuario tras completar el pago |
| `description` | string | — | Descripción libre de la operación |
| `expiration_seconds` | long | — | Segundos hasta expiración; `null` = sin expiración |
| `customer.document_type` | string | ✓ | Tipo de documento: `CC`, `NIT`, `PASSPORT`, `CE` |
| `customer.document_number` | string | ✓ | Número de documento sin puntos ni guiones |
| `customer.country_calling_code` | string | ✓ | Código de país con `+` (ej. `+57`) |
| `customer.phone_number` | string | ✓ | Número local sin código de país |
| `customer.email` | string | ✓ | Correo electrónico válido |
| `customer.first_name` | string | ✓ | Primer nombre |
| `customer.middle_name` | string | — | Segundo nombre |
| `customer.last_name` | string | ✓ | Primer apellido |
| `customer.second_last_name` | string | — | Segundo apellido |

**Request:**
```json
POST /v1/transactions
Content-Type: application/json

{
  "client_transaction_id": "EXT-REF-001",
  "amount_cents": 150000,
  "currency_code": "COP",
  "country_code": "CO",
  "payment_method_id": "MOCK_PSP",
  "webhook_url": "https://mi-app.co/webhook",
  "redirect_url": "https://mi-app.co/return",
  "description": "Compra en linea",
  "expiration_seconds": 1800,
  "customer": {
    "document_type": "CC",
    "document_number": "1234567890",
    "country_calling_code": "+57",
    "phone_number": "3001234567",
    "email": "cliente@example.com",
    "first_name": "Juan",
    "middle_name": "Carlos",
    "last_name": "Perez",
    "second_last_name": "Lopez"
  }
}
```

**Response 201 Created:**
```json
{
  "response_code": "000",
  "response_message": "Successful operation",
  "data": {
    "transaction_id": "550e8400-e29b-41d4-a716-446655440000",
    "client_transaction_id": "EXT-REF-001",
    "payment_method_id": "MOCK_PSP",
    "currency_code": "COP",
    "country_code": "CO",
    "description": "Compra en linea",
    "status": "APPROVED",
    "processed_at": "2026-04-27T10:30:00"
  }
}
```

---

### GET /v1/transactions/{transaction_id} — Consultar transacción

```
GET /v1/transactions/550e8400-e29b-41d4-a716-446655440000
```

**Response 200 OK:** misma estructura `data` del POST.

---

### Respuestas de error

**422 Unprocessable Entity — Error de validación (`001`)**
```json
{
  "response_code": "001",
  "response_message": "amount_cents debe ser un entero positivo; customer.email debe ser una dirección de correo válida"
}
```

**409 Conflict — Transacción duplicada (`002`)**
```json
{
  "response_code": "002",
  "response_message": "Ya existe una transacción con clientTransactionId: EXT-REF-001"
}
```

**404 Not Found — Transacción no encontrada (`003`)**
```json
{
  "response_code": "003",
  "response_message": "Transacción no encontrada con id: 550e8400-e29b-41d4-a716-446655440000"
}
```

**400 Bad Request — Proveedor de pago no disponible (`004`)**
```json
{
  "response_code": "004",
  "response_message": "No hay un adaptador de proveedor de pago registrado para paymentMethodId: UNKNOWN_PSP"
}
```

**409 Conflict — Conflicto de concurrencia (`006`)**
```json
{
  "response_code": "006",
  "response_message": "La transacción 550e8400-e29b-41d4-a716-446655440000 fue modificada por otro proceso. Por favor, reintente la operación."
}
```

**500 Internal Server Error (`005`)**
```json
{
  "response_code": "005",
  "response_message": "Error interno del servidor"
}
```