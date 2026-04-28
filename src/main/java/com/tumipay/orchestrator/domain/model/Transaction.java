package com.tumipay.orchestrator.domain.model;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Agregado raíz del dominio — representa una transacción de pago.
 *
 * Es inmutable: cualquier cambio de estado produce una nueva instancia mediante {@link #withStatus}.
 * El ID se genera en el dominio (UUID aleatorio) para garantizar independencia de la infraestructura.
 *
 * Ciclo de vida de {@code processedAt}:
 *   - {@code null} mientras la transacción está en PENDING (no ha pasado por el PSP aún).
 *   - Se asigna a {@code LocalDateTime.now()} en {@link #withStatus} al transicionar a un estado
 *     terminal (APPROVED, REJECTED, FAILED, EXPIRED, REVERSED).
 */
@Getter
@Builder(toBuilder = true)
public class Transaction {

    private final UUID id;
    private final String clientTransactionId;
    private final Long amountCents;
    private final String currencyCode;
    private final String countryCode;
    private final String paymentMethodId;
    private final String webhookUrl;
    private final String redirectUrl;
    private final String description;
    private final Long expirationSeconds;
    private final TransactionStatus status;
    private final Customer customer;
    private final LocalDateTime processedAt;
    private final LocalDateTime createdAt;

    /**
     * Factory method — crea una nueva transacción en estado {@link TransactionStatus#PENDING}.
     * Normaliza los códigos de moneda y país a mayúsculas y genera un UUID aleatorio.
     *
     * @param clientTransactionId identificador idempotente del cliente
     * @param amountCents         monto en centavos
     * @param currencyCode        código ISO 4217 de la moneda
     * @param countryCode         código ISO 3166-1 Alpha-2 del país
     * @param paymentMethodId     identificador del proveedor de pago
     * @param webhookUrl          URL de notificación de resultado
     * @param redirectUrl         URL de redirección tras el pago
     * @param description         descripción opcional de la operación
     * @param expirationSeconds   tiempo en segundos hasta expiración
     * @param customer            cliente que realiza la transacción
     * @return nueva instancia de {@link Transaction} con estado PENDING
     */
    public static Transaction create(
            String clientTransactionId,
            Long amountCents,
            String currencyCode,
            String countryCode,
            String paymentMethodId,
            String webhookUrl,
            String redirectUrl,
            String description,
            Long expirationSeconds,
            Customer customer) {

        return Transaction.builder()
                .id(UUID.randomUUID())
                .clientTransactionId(clientTransactionId)
                .amountCents(amountCents)
                .currencyCode(normalizeCode(currencyCode))
                .countryCode(normalizeCode(countryCode))
                .paymentMethodId(paymentMethodId)
                .webhookUrl(webhookUrl)
                .redirectUrl(redirectUrl)
                .description(description)
                .expirationSeconds(expirationSeconds)
                .status(TransactionStatus.PENDING)
                .customer(customer)
                .processedAt(null)             // null: aún no ha pasado por el PSP
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** Recorta espacios y convierte a mayúsculas; devuelve {@code null} si la entrada es {@code null}. */
    private static String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    /** Estados que indican que el PSP ya respondió y la transacción fue procesada. */
    private static final Set<TransactionStatus> TERMINAL_STATUSES = Set.of(
            TransactionStatus.APPROVED,
            TransactionStatus.REJECTED,
            TransactionStatus.FAILED,
            TransactionStatus.EXPIRED,
            TransactionStatus.REVERSED
    );

    /**
     * Produce una copia de esta transacción con el estado actualizado.
     * Preserva todos los demás campos sin mutación (inmutabilidad del agregado).
     *
     * Si el nuevo estado es terminal (APPROVED, REJECTED, FAILED, EXPIRED, REVERSED),
     * asigna {@code processedAt} al instante actual, registrando cuándo respondió el PSP.
     *
     * @param newStatus nuevo estado a asignar
     * @return nueva instancia de {@link Transaction} con el estado actualizado
     */
    public Transaction withStatus(TransactionStatus newStatus) {
        LocalDateTime resolvedProcessedAt = TERMINAL_STATUSES.contains(newStatus)
                ? LocalDateTime.now()
                : this.processedAt;

        return Transaction.builder()
                .id(this.id)
                .clientTransactionId(this.clientTransactionId)
                .amountCents(this.amountCents)
                .currencyCode(this.currencyCode)
                .countryCode(this.countryCode)
                .paymentMethodId(this.paymentMethodId)
                .webhookUrl(this.webhookUrl)
                .redirectUrl(this.redirectUrl)
                .description(this.description)
                .expirationSeconds(this.expirationSeconds)
                .status(newStatus)
                .customer(this.customer)
                .processedAt(resolvedProcessedAt)
                .createdAt(this.createdAt)
                .build();
    }
}
