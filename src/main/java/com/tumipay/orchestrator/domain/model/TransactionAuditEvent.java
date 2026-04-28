package com.tumipay.orchestrator.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Evento de dominio que representa una operación de auditoría sobre una transacción.
 * Este evento se publica en Kafka para ser procesado asíncronamente.
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class TransactionAuditEvent {

    private final UUID transactionId;
    private final AuditAction action;
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
    private final TransactionStatus oldStatus;
    private final LocalDateTime processedAt;
    private final UUID customerId;
    private final String changedBy;
    private final LocalDateTime changedAt;

    /**
     * Construye un evento de auditoría para una operación de inserción o sin cambio de estado previo.
     *
     * @param transaction transacción que originó el evento
     * @param action      tipo de operación realizada (INSERT, UPDATE, DELETE)
     * @param changedBy   nombre del servicio o usuario que realizó el cambio
     * @return evento de auditoría listo para publicar
     */
    public static TransactionAuditEvent fromTransaction(Transaction transaction, AuditAction action, String changedBy) {
        return TransactionAuditEvent.builder()
                .transactionId(transaction.getId())
                .action(action)
                .clientTransactionId(transaction.getClientTransactionId())
                .amountCents(transaction.getAmountCents())
                .currencyCode(transaction.getCurrencyCode())
                .countryCode(transaction.getCountryCode())
                .paymentMethodId(transaction.getPaymentMethodId())
                .webhookUrl(transaction.getWebhookUrl())
                .redirectUrl(transaction.getRedirectUrl())
                .description(transaction.getDescription())
                .expirationSeconds(transaction.getExpirationSeconds())
                .status(transaction.getStatus())
                .processedAt(transaction.getProcessedAt())
                .customerId(transaction.getCustomer() != null ? transaction.getCustomer().getId() : null)
                .changedBy(changedBy)
                .changedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Construye un evento de auditoría para una operación de actualización, incluyendo el estado anterior.
     *
     * @param transaction transacción con el estado nuevo
     * @param action      tipo de operación (normalmente UPDATE)
     * @param changedBy   nombre del servicio o usuario que realizó el cambio
     * @param oldStatus   estado anterior de la transacción antes del cambio
     * @return evento de auditoría con {@code oldStatus} poblado
     */
    public static TransactionAuditEvent fromTransaction(Transaction transaction, AuditAction action, String changedBy, TransactionStatus oldStatus) {
        TransactionAuditEvent event = fromTransaction(transaction, action, changedBy);
        return event.toBuilder()
                .oldStatus(oldStatus)
                .build();
    }
}
