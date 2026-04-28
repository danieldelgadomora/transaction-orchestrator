# Arquitectura Implementada

## Tabla de Contenidos

1. [Arquitectura Implementada](#1-arquitectura-implementada)
2. [Patrones de Diseño Aplicados](#2-patrones-de-diseño-aplicados)
3. [Modelo de Integración Continua](#3-modelo-de-integración-continua)
4. [Calidad del Código](#4-calidad-del-código)

---

## 1. Arquitectura Implementada

El microservicio implementa Arquitectura Hexagonal (Ports & Adapters), también conocida como Clean Architecture. El principio central es que la lógica de negocio (dominio) es completamente independiente de los detalles de infraestructura: framework HTTP, base de datos, proveedores de pago o sistemas de mensajería.

### Capas de la Arquitectura

| Capa | Responsabilidad | Clases clave |
|---|---|---|
| Domain | Entidades, enumeraciones, lógica de negocio pura, interfaces de puertos | `Transaction`, `Customer`, `TransactionUseCase`, `PaymentProviderPort` |
| Application | Casos de uso, orquestación del flujo. Sin dependencias de frameworks externos | `TransactionService`, `AuditService` |
| Infrastructure | Adaptadores HTTP, JPA, PSP, Kafka, manejo de excepciones | `TransactionController`, `TransactionPersistenceAdapter`, `KafkaAuditPublisherAdapter` |

### Estructura de Paquetes

La siguiente estructura refleja directamente la separación de capas en el código:

```
com.tumipay.orchestrator/
│
├── domain/                          ← Lógica de negocio pura (sin frameworks)
│   ├── model/
│   │   ├── Transaction.java         ← Agregado raíz (inmutable, @Builder)
│   │   ├── Customer.java
│   │   ├── TransactionStatus.java   ← PENDING, APPROVED, REJECTED, FAILED…
│   │   ├── TransactionAuditEvent.java
│   │   └── CustomerAuditEvent.java
│   ├── port/out/                    ← Puertos secundarios: contratos de salida
│   │   ├── TransactionRepositoryPort.java
│   │   ├── CustomerRepositoryPort.java
│   │   ├── PaymentProviderPort.java
│   │   ├── CircuitBreakerPort.java
│   │   └── AuditPublisherPort.java
│   └── exception/
│       ├── TransactionNotFoundException.java
│       ├── DuplicateTransactionException.java
│       ├── PaymentProviderNotFoundException.java
│       └── ConcurrentModificationException.java
│
├── application/                     ← Casos de uso y orquestación
│   ├── port/in/                     ← Puertos primarios: contratos de entrada
│   │   ├── TransactionUseCase.java
│   │   └── CreateTransactionCommand.java
│   ├── port/
│   │   └── AuditUseCase.java
│   └── service/
│       ├── TransactionService.java  ← Implementa TransactionUseCase
│       └── AuditService.java        ← Implementa AuditUseCase
│
└── infrastructure/                  ← Adaptadores concretos (Spring, JPA, Kafka…)
    ├── adapter/in/rest/
    │   ├── controller/TransactionController.java
    │   ├── dto/request/             ← CreateTransactionRequest, CustomerRequest
    │   ├── dto/response/            ← ApiResponse, TransactionResponse
    │   ├── mapper/TransactionRestMapper.java
    │   └── exception/GlobalExceptionHandler.java
    └── adapter/out/
        ├── persistence/             ← TransactionPersistenceAdapter, CustomerPersistenceAdapter
        ├── provider/                ← MockPaymentProviderAdapter (PSP)
        ├── resilience/              ← CircuitBreakerAdapter (Resilience4j)
        └── messaging/kafka/         ← KafkaAuditPublisherAdapter, KafkaAuditConsumer
```

### Regla de Dependencia

Las dependencias apuntan únicamente hacia adentro: Infrastructure → Application → Domain. El dominio no conoce nada de Spring, JPA ni Kafka. Los puertos (interfaces) son definidos en el dominio; los adaptadores (implementaciones) viven en la infraestructura.

Es posible cambiar el framework HTTP, la base de datos o los proveedores de pago sin modificar una sola línea de la lógica de negocio. Los puertos actúan como contratos.

---

## 2. Patrones de Diseño Aplicados

| Patrón | Donde se aplica | Por qué / Beneficio |
|---|---|---|
| Ports & Adapters | Toda la arquitectura | Desacopla dominio de infraestructura. Permite cambiar BD, PSP o framework sin tocar lógica de negocio. |
| Strategy + Registry | `TransactionService` + `PaymentProviderPort` | Resolver el proveedor de pago en runtime. Agregar un PSP nuevo = crear una clase `@Component`, sin modificar código existente (Open/Closed). |
| Factory Method | `Transaction.create()` | Encapsula la construcción del agregado: UUID, normalización de códigos, estado inicial PENDING. |
| Command | `CreateTransactionCommand` | Transporta datos de la petición HTTP hacia el caso de uso, desacoplado del DTO HTTP. |
| Repository | `TransactionRepository`, `CustomerRepository` | Abstrae el acceso a datos. El dominio trabaja con interfaces; JPA es un detalle de implementación. |
| Mapper / ACL | `TransactionRestMapper`, `TransactionPersistenceMapper` (MapStruct) | Convierte entre modelos de capas distintas. Protege el modelo de dominio de modelos externos. |
| Circuit Breaker | `CircuitBreakerPort` + `CircuitBreakerAdapter` | Tolerancia a fallos con proveedores externos. Fallback automático, evita cascadas de fallos. |
| Transactional Outbox | `AuditService` (`afterCommit`) | Garantiza que los eventos de auditoría se publiquen a Kafka después del commit de BD, asegurando consistencia eventual. |
| Event-Driven | `AuditService` + `KafkaAuditPublisherAdapter` | Auditoría desacoplada del flujo transaccional principal mediante eventos de dominio. |
| Builder | `Transaction`, `Customer`, `ApiResponse` (`@Builder`) | Construcción fluida e inmutable de objetos de dominio y DTOs. |
| Template Method | `GlobalExceptionHandler` | Centraliza el manejo de familias de excepciones con patrón uniforme de respuesta. |
| Facade | `TransactionController` | Simplifica la interfaz HTTP delegando completamente al caso de uso, sin lógica propia en el controller. |

### Strategy + Registry — Implementación

El patrón más relevante de la prueba. Cada PSP es un `@Component` que implementa `PaymentProviderPort`. `TransactionService` construye el registro en `@PostConstruct`:

```java
// PaymentProviderPort.java — contrato definido en el dominio
public interface PaymentProviderPort {
    String getSupportedPaymentMethodId();
    TransactionStatus process(Transaction transaction);
}

// MockPaymentProviderAdapter.java — implementación en infraestructura
@Component
public class MockPaymentProviderAdapter implements PaymentProviderPort {
    @Override
    public String getSupportedPaymentMethodId() { return "MOCK_PSP"; }

    @Override
    public TransactionStatus process(Transaction transaction) {
        return TransactionStatus.APPROVED; // en producción: llamada HTTP al PSP
    }
}

// TransactionService.java — registro automático en @PostConstruct
@PostConstruct
void initializeProviderRegistry() {
    this.providerRegistry = paymentProviders.stream()
        .collect(Collectors.toMap(
            PaymentProviderPort::getSupportedPaymentMethodId,
            Function.identity()
        ));
}
```

Agregar un nuevo PSP requiere únicamente crear una clase nueva anotada con `@Component`. No se toca `TransactionService` ni ningún `if/switch` — principio Open/Closed en acción.

### Transactional Outbox — Implementación

Los eventos de auditoría se publican únicamente después del commit exitoso de la transacción de BD, eliminando registros huérfanos:

```java
// AuditService.java
private void registerAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() { action.run(); }
            }
        );
    } else {
        action.run(); // fallback si no hay transacción activa
    }
}
```

---

## 3. Modelo de Integración Continua

**Herramienta: GitHub Actions**

El pipeline CI/CD está implementado en `.github/workflows/ci-cd.yml`. Las etapas 1-3 están activas; las etapas 4-5 están definidas en el archivo pero deshabilitadas (`if: false`) a la espera de que el entorno de infraestructura de destino esté disponible.

| Etapa | Nombre | Descripción | Estado |
|---|---|---|---|
| 1 | Build, Test & Coverage | `mvn clean verify` con PostgreSQL 15 y Kafka (Redpanda) como servicios Docker. JUnit 5 + Mockito + Testcontainers. JaCoCo con umbral mínimo 80% líneas / 70% ramas. | ✅ Activa |
| 2 | Análisis Estático | SonarCloud: bugs, code smells, vulnerabilidades, duplicaciones. Integra reporte JaCoCo. `-Dsonar.qualitygate.wait=true` bloquea el pipeline si el Quality Gate falla. | ✅ Activa |
| 3 | Docker Build & Push | Imagen multi-stage publicada en GitHub Container Registry (GHCR). Etiquetas automáticas por rama y SHA. Se ejecuta en cualquier push y requiere que pasen las etapas 1 y 2. | ✅ Activa |
| 4 | Deploy Staging | Despliegue vía SSH + Docker Compose al ambiente de staging. Variables en GitHub Environments protegidos. | ⏸ Definida, deshabilitada |
| 5 | Deploy Production | Despliegue a producción con aprobación manual requerida mediante GitHub Environments. | ⏸ Definida, deshabilitada |

**Orden de ejecución y bloqueos:**

```
pull_request → main / develop
        │
        ▼
 [1] build-and-test
        │
        ▼
 [2] code-quality  ← Quality Gate rojo = pipeline se detiene aquí
        │
        ▼
 [3] docker        ← build de imagen; requiere [1] y [2] verdes
```

---

## 4. Calidad del Código

### Estrategia de Testing

| Tipo | Herramienta | Objetivo |
|---|---|---|
| Unitarios | JUnit 5 + Mockito | Probar lógica de negocio aislada de infraestructura. Cubrir happy path y casos de error. Sin BD ni red. |
| Integración | Spring Boot Test + Testcontainers | Verificar flujos completos con PostgreSQL y Kafka reales. Detectar problemas de mapping JPA y migraciones Flyway. |
| Contrato REST | `@WebMvcTest` + MockMvc | Validar que el contrato HTTP (códigos, estructura JSON, validaciones) no se rompe entre commits. |
| Cobertura | JaCoCo | Umbral mínimo **80% de líneas** y **70% de ramas** en clases de negocio, configurado en `pom.xml`. Reportes en `target/site/jacoco/`. |

### Tests Implementados

- `TransactionServiceTest`: happy path, duplicados, proveedor desconocido, optimistic lock, transacción no encontrada.
- `AuditServiceTest`: publicación de auditoría con verificación de `TransactionSynchronization` (`afterCommit`).
- `TransactionControllerTest`: tests de contrato HTTP con `@WebMvcTest`.
- `TransactionPersistenceAdapterTest`: mapping entre entidades JPA y objetos de dominio.
- `MockPaymentProviderAdapterTest`: comportamiento del adaptador mock.
- `GlobalExceptionHandlerTest`: verificación del diccionario de errores (códigos 001-006).
- `TransactionIntegrationTest`: flujo end-to-end con Testcontainers: crear, consultar, duplicar, validación fallida.

### Herramientas de Calidad

| Herramienta | Propósito | Integración |
|---|---|---|
| JaCoCo | Reporte de cobertura de código. Umbral mínimo 80% líneas / 70% ramas. | `mvn verify` + GitHub Actions. Falla el pipeline si no se cumple el umbral. |
| SonarCloud | Análisis estático: bugs, vulnerabilidades, code smells, deuda técnica, duplicaciones. | GitHub Actions. Quality Gate bloquea el Docker Build si no se cumplen umbrales. |
| Lombok | Reduce código repetitivo (`@Builder`, `@Getter`, `@Slf4j`, etc.). | Compilación. Menos código = menos superficie de error. |
| MapStruct | Generación de mappers type-safe en tiempo de compilación. | Maven annotation processor. Elimina mappers manuales propensos a errores. |

### Buenas Prácticas Aplicadas

- **Inmutabilidad en entidades de dominio:** `@Builder` sin setters expuestos. Los objetos no mutan después de crearse.
- **Validaciones declarativas en capa HTTP:** `@NotBlank`, `@Pattern`, `@Positive`, `@Valid`. El framework valida antes de llegar al dominio.
- **Manejo centralizado de excepciones:** `@RestControllerAdvice` con diccionario de errores estandarizado (000-006).
- **Logging estructurado:** niveles INFO/DEBUG/WARN apropiados. Nunca se loguean datos sensibles del cliente.
- **Transaccionalidad declarativa:** `@Transactional` en escrituras, `@Transactional(readOnly=true)` en lecturas.
- **Publicación de eventos post-commit:** `TransactionSynchronization.afterCommit()` evita inconsistencias entre BD y Kafka.
- **Principios SOLID:** SRP (una clase un motivo de cambio), OCP (Strategy+Registry), DIP (depender de interfaces).
- **Clean Code:** nombres descriptivos, métodos cortos con una responsabilidad, comentarios donde agregan valor.
