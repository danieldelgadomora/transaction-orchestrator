-- =============================================================================
-- transaction_orchestrator_schema.sql
-- Prueba Técnica — TumiPay
-- =============================================================================

-- Crear la base de datos
CREATE DATABASE orchestrator_db;

-- Habilitar extensión para gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- -----------------------------------------------------------------------------
-- TABLA: customers
-- Entidad cliente con identidad propia. Puede tener múltiples transacciones.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customers (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    document_type        VARCHAR(20)  NOT NULL,       -- CC, NIT, PASSPORT, etc.
    document_number      VARCHAR(50)  NOT NULL,
    country_calling_code VARCHAR(6)   NOT NULL,       -- +57, +1, etc.
    phone_number         VARCHAR(20)  NOT NULL,       -- sin código de país
    email                VARCHAR(254) NOT NULL,
    first_name           VARCHAR(100) NOT NULL,
    middle_name          VARCHAR(100),
    last_name            VARCHAR(100) NOT NULL,
    second_last_name     VARCHAR(100),

    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uq_customers_document UNIQUE (document_type, document_number)
);

COMMENT ON TABLE  customers                        IS 'Entidad cliente con identidad propia — un cliente puede tener múltiples transacciones. Clave de idempotencia: (document_type, document_number)';
COMMENT ON COLUMN customers.id                     IS 'Identificador único del cliente generado por la BD (UUID v4) — usado como FK en transactions';
COMMENT ON COLUMN customers.document_type          IS 'Tipo de documento de identidad legal: CC (cédula), NIT, PASSPORT, CE (cédula extranjería), etc.';
COMMENT ON COLUMN customers.document_number        IS 'Número del documento sin puntos ni guiones — junto con document_type forma la clave única de negocio';
COMMENT ON COLUMN customers.country_calling_code   IS 'Código telefónico del país con símbolo +: +57 (Colombia), +1 (EEUU/CA), +34 (España), etc.';
COMMENT ON COLUMN customers.phone_number           IS 'Número de teléfono local sin código de país ni caracteres especiales';
COMMENT ON COLUMN customers.email                  IS 'Correo electrónico del cliente — máx 254 caracteres según RFC 5321';
COMMENT ON COLUMN customers.first_name             IS 'Primer nombre del cliente (obligatorio)';
COMMENT ON COLUMN customers.middle_name            IS 'Segundo nombre del cliente (opcional)';
COMMENT ON COLUMN customers.last_name              IS 'Primer apellido del cliente (obligatorio)';
COMMENT ON COLUMN customers.second_last_name       IS 'Segundo apellido del cliente (opcional)';

-- -----------------------------------------------------------------------------
-- TABLA: transactions
-- Registro principal de transacciones de pago.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transactions (
    id                    UUID         NOT NULL,
    client_transaction_id VARCHAR(100) NOT NULL,
    amount_cents          BIGINT       NOT NULL CHECK (amount_cents > 0),
    currency_code         VARCHAR(3)   NOT NULL,
    country_code          VARCHAR(2)   NOT NULL,
    payment_method_id     VARCHAR(50)  NOT NULL,
    webhook_url           VARCHAR(500) NOT NULL,
    redirect_url          VARCHAR(500) NOT NULL,
    description           VARCHAR(255),
    expiration_seconds    BIGINT       CHECK (expiration_seconds > 0),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    processed_at          TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    customer_id           UUID         NOT NULL,
    version               INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT pk_transactions              PRIMARY KEY (id),
    CONSTRAINT uq_client_transaction_id     UNIQUE (client_transaction_id),
    CONSTRAINT fk_transactions_customer     FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT chk_transaction_status       CHECK (status IN (
        'PENDING', 'PROCESSING', 'APPROVED', 'REJECTED', 'EXPIRED', 'FAILED', 'REVERSED'
    ))
);

COMMENT ON TABLE  transactions                        IS 'Registro principal de transacciones de pago — persiste antes de enviar al PSP para garantizar trazabilidad completa';
COMMENT ON COLUMN transactions.id                     IS 'Identificador único generado por el microservicio (UUID v4) — se asigna antes de persistir, habilitando idempotencia en arquitecturas event-driven';
COMMENT ON COLUMN transactions.client_transaction_id  IS 'ID externo provisto por el sistema cliente — clave de idempotencia; constraint UNIQUE previene registros duplicados por retries';
COMMENT ON COLUMN transactions.amount_cents           IS 'Monto de la transacción expresado en centavos (entero) — evita errores de punto flotante; práctica estándar ISO 4217';
COMMENT ON COLUMN transactions.currency_code          IS 'Código de moneda ISO 4217 de 3 letras: COP, USD, EUR, etc.';
COMMENT ON COLUMN transactions.country_code           IS 'Código de país ISO 3166-1 alpha-2 de 2 letras: CO, US, MX, etc.';
COMMENT ON COLUMN transactions.payment_method_id      IS 'Identificador del proveedor de pago usado para enrutar al adaptador correcto via Strategy Registry';
COMMENT ON COLUMN transactions.webhook_url            IS 'URL del sistema cliente a la que se notifica el resultado de la transacción de forma asíncrona';
COMMENT ON COLUMN transactions.redirect_url           IS 'URL a la que se redirige al usuario final tras completar el flujo de pago';
COMMENT ON COLUMN transactions.description            IS 'Descripción libre de la transacción provista por el sistema cliente (opcional)';
COMMENT ON COLUMN transactions.expiration_seconds     IS 'Tiempo en segundos antes de que la transacción expire — NULL indica sin expiración explícita';
COMMENT ON COLUMN transactions.status                 IS 'Estado actual de la transacción: PENDING (inicial) → PROCESSING → APPROVED | REJECTED | EXPIRED | FAILED | REVERSED';
COMMENT ON COLUMN transactions.processed_at           IS 'Timestamp con zona horaria en que el PSP procesó la transacción — NULL mientras está en PENDING/PROCESSING';
COMMENT ON COLUMN transactions.created_at             IS 'Timestamp con zona horaria de creación del registro — gestionado por la BD';
COMMENT ON COLUMN transactions.updated_at             IS 'Timestamp con zona horaria de la última actualización del registro — actualizar manualmente o via trigger';
COMMENT ON COLUMN transactions.customer_id            IS 'FK al cliente asociado — un cliente puede tener múltiples transacciones';
COMMENT ON COLUMN transactions.version                IS 'Campo de control de concurrencia optimista (JPA @Version) — se incrementa en cada UPDATE; lanza OptimisticLockException si hay conflicto';

-- -----------------------------------------------------------------------------
-- ÍNDICES
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_transactions_status              ON transactions (status);
CREATE INDEX IF NOT EXISTS idx_transactions_payment_method      ON transactions (payment_method_id);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at          ON transactions (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_customers_document               ON customers (document_type, document_number);

-- =============================================================================
-- TABLAS DE AUDITORIA
-- Rastrean todos los cambios a clientes y transacciones
-- =============================================================================

-- -----------------------------------------------------------------------------
-- CUSTOMER_AUDIT
-- Rastrea todos los cambios a registros de cliente (INSERT, UPDATE, DELETE)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_audit (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    customer_id          UUID         NOT NULL,
    action               VARCHAR(10)  NOT NULL,  -- INSERT, UPDATE, DELETE
    document_type        VARCHAR(20),
    document_number      VARCHAR(50),
    country_calling_code VARCHAR(6),
    phone_number         VARCHAR(20),
    email                VARCHAR(254),
    first_name           VARCHAR(100),
    middle_name          VARCHAR(100),
    last_name            VARCHAR(100),
    second_last_name     VARCHAR(100),
    changed_by           VARCHAR(100),
    changed_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    transaction_id       UUID,

    CONSTRAINT pk_customer_audit PRIMARY KEY (id),
    CONSTRAINT fk_customer_audit_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT chk_customer_audit_action CHECK (action IN ('INSERT', 'UPDATE', 'DELETE'))
);

COMMENT ON TABLE  customer_audit                        IS 'Registro de auditoría de clientes — cada fila representa un cambio puntual a un registro de customers';
COMMENT ON COLUMN customer_audit.id                     IS 'Identificador único del evento de auditoría (UUID v4 generado por la BD)';
COMMENT ON COLUMN customer_audit.customer_id            IS 'FK al cliente auditado — permite reconstruir el historial completo de un cliente';
COMMENT ON COLUMN customer_audit.action                 IS 'Tipo de operación que originó el evento: INSERT (alta), UPDATE (modificación), DELETE (baja)';
COMMENT ON COLUMN customer_audit.document_type          IS 'Snapshot del tipo de documento en el momento del evento';
COMMENT ON COLUMN customer_audit.document_number        IS 'Snapshot del número de documento en el momento del evento';
COMMENT ON COLUMN customer_audit.country_calling_code   IS 'Snapshot del código telefónico país en el momento del evento';
COMMENT ON COLUMN customer_audit.phone_number           IS 'Snapshot del teléfono en el momento del evento';
COMMENT ON COLUMN customer_audit.email                  IS 'Snapshot del correo electrónico en el momento del evento';
COMMENT ON COLUMN customer_audit.first_name             IS 'Snapshot del primer nombre en el momento del evento';
COMMENT ON COLUMN customer_audit.middle_name            IS 'Snapshot del segundo nombre en el momento del evento (nullable)';
COMMENT ON COLUMN customer_audit.last_name              IS 'Snapshot del primer apellido en el momento del evento';
COMMENT ON COLUMN customer_audit.second_last_name       IS 'Snapshot del segundo apellido en el momento del evento (nullable)';
COMMENT ON COLUMN customer_audit.changed_by             IS 'Identificador del sistema o usuario que originó el cambio (ej: transaction-orchestrator)';
COMMENT ON COLUMN customer_audit.changed_at             IS 'Timestamp con zona horaria del momento exacto en que se registró el evento';
COMMENT ON COLUMN customer_audit.transaction_id         IS 'UUID de la transacción que desencadenó la creación del cliente (trazabilidad cruzada)';

-- -----------------------------------------------------------------------------
-- TRANSACTION_AUDIT
-- Rastrea todos los cambios a registros de transacción (INSERT, UPDATE, DELETE)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_audit (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    transaction_id        UUID         NOT NULL,
    action                VARCHAR(10)  NOT NULL,  -- INSERT, UPDATE, DELETE
    client_transaction_id VARCHAR(100),
    amount_cents          BIGINT,
    currency_code         VARCHAR(3),
    country_code          VARCHAR(2),
    payment_method_id     VARCHAR(50),
    webhook_url           VARCHAR(500),
    redirect_url          VARCHAR(500),
    description           VARCHAR(255),
    expiration_seconds    BIGINT,
    status                VARCHAR(20),
    old_status            VARCHAR(20),
    processed_at          TIMESTAMP WITH TIME ZONE,
    customer_id           UUID,
    changed_by            VARCHAR(100),
    changed_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT pk_transaction_audit PRIMARY KEY (id),
    CONSTRAINT fk_transaction_audit_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT chk_transaction_audit_action CHECK (action IN ('INSERT', 'UPDATE', 'DELETE'))
);

COMMENT ON TABLE  transaction_audit                        IS 'Registro de auditoría de transacciones — cada fila representa un cambio puntual a un registro de transactions';
COMMENT ON COLUMN transaction_audit.id                     IS 'Identificador único del evento de auditoría (UUID v4 generado por la BD)';
COMMENT ON COLUMN transaction_audit.transaction_id         IS 'FK a la transacción auditada — permite reconstruir el historial completo de una transacción';
COMMENT ON COLUMN transaction_audit.action                 IS 'Tipo de operación que originó el evento: INSERT (creación), UPDATE (cambio de estado), DELETE (baja)';
COMMENT ON COLUMN transaction_audit.client_transaction_id  IS 'Snapshot del ID externo de la transacción en el momento del evento';
COMMENT ON COLUMN transaction_audit.amount_cents           IS 'Snapshot del monto en centavos en el momento del evento';
COMMENT ON COLUMN transaction_audit.currency_code          IS 'Snapshot del código de moneda ISO 4217 en el momento del evento';
COMMENT ON COLUMN transaction_audit.country_code           IS 'Snapshot del código de país ISO 3166-1 en el momento del evento';
COMMENT ON COLUMN transaction_audit.payment_method_id      IS 'Snapshot del método de pago en el momento del evento';
COMMENT ON COLUMN transaction_audit.webhook_url            IS 'Snapshot de la URL de webhook configurada en el momento del evento';
COMMENT ON COLUMN transaction_audit.redirect_url           IS 'Snapshot de la URL de redirección configurada en el momento del evento';
COMMENT ON COLUMN transaction_audit.description            IS 'Snapshot de la descripción de la transacción en el momento del evento';
COMMENT ON COLUMN transaction_audit.expiration_seconds     IS 'Snapshot del tiempo de expiración configurado en el momento del evento';
COMMENT ON COLUMN transaction_audit.status                 IS 'Snapshot del estado de la transacción DESPUÉS del cambio (valor nuevo)';
COMMENT ON COLUMN transaction_audit.old_status             IS 'Estado de la transacción ANTES del cambio — solo aplica para acción UPDATE; NULL en INSERT';
COMMENT ON COLUMN transaction_audit.processed_at           IS 'Snapshot del timestamp de procesamiento del PSP en el momento del evento';
COMMENT ON COLUMN transaction_audit.customer_id            IS 'Snapshot del FK al cliente asociado — permite trazabilidad cruzada cliente-transacción';
COMMENT ON COLUMN transaction_audit.changed_by             IS 'Identificador del sistema o usuario que originó el cambio (ej: transaction-orchestrator)';
COMMENT ON COLUMN transaction_audit.changed_at             IS 'Timestamp con zona horaria del momento exacto en que se registró el evento';

-- -----------------------------------------------------------------------------
-- INDICES para consultas eficientes en tablas de auditoría
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_customer_audit_customer_id ON customer_audit (customer_id);
CREATE INDEX IF NOT EXISTS idx_customer_audit_changed_at   ON customer_audit (changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_customer_audit_action       ON customer_audit (action);

CREATE INDEX IF NOT EXISTS idx_transaction_audit_transaction_id ON transaction_audit (transaction_id);
CREATE INDEX IF NOT EXISTS idx_transaction_audit_changed_at     ON transaction_audit (changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_transaction_audit_action         ON transaction_audit (action);
CREATE INDEX IF NOT EXISTS idx_transaction_audit_status         ON transaction_audit (status);

-- =============================================================================
-- DATOS DE EJEMPLO (opcional, para pruebas)
-- Customer es una tabla separada - se inserta primero, luego se referencia
-- =============================================================================
/*
-- Insertar cliente primero
INSERT INTO customers (document_type, document_number, country_calling_code,
                       phone_number, email, first_name, last_name)
VALUES ('CC', '1234567890', '+57', '3001234567', 'juan.perez@example.com', 'Juan', 'Perez')
RETURNING id;

-- Insertar transacción referenciando al cliente
INSERT INTO transactions (id, client_transaction_id, amount_cents, currency_code, country_code,
                          payment_method_id, webhook_url, redirect_url, status, customer_id)
SELECT gen_random_uuid(), 'EXT-001', 150000, 'COP', 'CO',
       'MOCK_PSP', 'https://mi-app.co/webhook', 'https://mi-app.co/return',
       'APPROVED', id
FROM customers WHERE document_number = '1234567890';
*/
