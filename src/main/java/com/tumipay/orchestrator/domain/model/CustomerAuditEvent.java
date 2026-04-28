package com.tumipay.orchestrator.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Evento de dominio que representa una operación de auditoría sobre un cliente.
 * Este evento se publica en Kafka para ser procesado asíncronamente.
 */
@Getter
@Builder
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class CustomerAuditEvent {

    private final UUID customerId;
    private final AuditAction action;
    private final String documentType;
    private final String documentNumber;
    private final String countryCallingCode;
    private final String phoneNumber;
    private final String email;
    private final String firstName;
    private final String middleName;
    private final String lastName;
    private final String secondLastName;
    private final String changedBy;
    private final LocalDateTime changedAt;
    private final UUID transactionId;

    /**
     * Construye un evento de auditoría a partir de un cliente del dominio.
     * La marca de tiempo {@code changedAt} se establece al momento de la llamada.
     *
     * @param customer      datos del cliente que originó el evento
     * @param action        tipo de operación realizada (INSERT, UPDATE, DELETE)
     * @param customerId    ID del cliente afectado
     * @param changedBy     nombre del servicio o usuario que realizó el cambio
     * @param transactionId ID de la transacción que desencadenó el evento
     * @return evento de auditoría listo para publicar
     */
    public static CustomerAuditEvent fromCustomer(Customer customer, AuditAction action, UUID customerId, String changedBy, UUID transactionId) {
        return CustomerAuditEvent.builder()
                .customerId(customerId)
                .action(action)
                .documentType(customer.getDocumentType())
                .documentNumber(customer.getDocumentNumber())
                .countryCallingCode(customer.getCountryCallingCode())
                .phoneNumber(customer.getPhoneNumber())
                .email(customer.getEmail())
                .firstName(customer.getFirstName())
                .middleName(customer.getMiddleName())
                .lastName(customer.getLastName())
                .secondLastName(customer.getSecondLastName())
                .changedBy(changedBy)
                .changedAt(LocalDateTime.now())
                .transactionId(transactionId)
                .build();
    }
}
