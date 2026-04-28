package com.tumipay.orchestrator.application.service;

import com.tumipay.orchestrator.domain.model.AuditAction;
import com.tumipay.orchestrator.domain.model.Customer;
import com.tumipay.orchestrator.domain.model.CustomerAuditEvent;
import com.tumipay.orchestrator.domain.model.Transaction;
import com.tumipay.orchestrator.domain.model.TransactionAuditEvent;
import com.tumipay.orchestrator.domain.model.TransactionStatus;
import com.tumipay.orchestrator.application.port.AuditUseCase;
import com.tumipay.orchestrator.domain.port.out.AuditPublisherPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Servicio de Aplicación que implementa el puerto de entrada AuditUseCase.
 * Orquesta la publicación de eventos de auditoría usando el puerto de salida AuditPublisherPort.
 * Sigue los principios de Arquitectura Hexagonal:
 * - Implementa un puerto de entrada (AuditUseCase)
 * - Usa un puerto de salida (AuditPublisherPort)
 * - No tiene dependencias directas de infraestructura
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService implements AuditUseCase {

    private final AuditPublisherPort auditPublisher;

    private static final String SERVICE_NAME = "transaction-orchestrator";

    /**
     * Publica eventos de auditoría para una nueva transacción.
     * Solo audita el cliente si es nuevo (no si ya existía).
     *
     * @param transaction la transacción creada
     * @param isNewCustomer true si el cliente fue creado nuevo, false si ya existía
     */
    public void auditTransactionCreation(Transaction transaction, boolean isNewCustomer) {
        registerAfterCommit(() -> {
            if (isNewCustomer) {
                auditCustomerCreation(transaction.getCustomer(), transaction.getId());
            }
            auditTransactionInsert(transaction);
        });
    }

    /**
     * Registra la acción para ejecutarse después del commit de la transacción activa.
     * Si no hay transacción activa, la ejecuta inmediatamente.
     *
     * @param action lógica de auditoría a ejecutar post-commit
     */
    private void registerAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Publica evento de auditoría para actualización de transacción.
     *
     * @param transaction la transacción actualizada
     * @param oldStatus   el estado anterior
     */
    public void auditTransactionUpdate(Transaction transaction, TransactionStatus oldStatus) {
        registerAfterCommit(() -> {
            try {
                TransactionAuditEvent event = TransactionAuditEvent.fromTransaction(
                        transaction,
                        AuditAction.UPDATE,
                        SERVICE_NAME,
                        oldStatus
                );
                auditPublisher.publishTransactionAudit(event);
            } catch (RuntimeException e) {
                log.warn("Error al publicar auditoría de actualización", e);
            }
        });
    }

    /**
     * Publica el evento de auditoría de inserción de un cliente nuevo.
     * Se omite si el cliente o su ID son nulos.
     *
     * @param customer      cliente recién creado
     * @param transactionId ID de la transacción que originó la creación
     */
    private void auditCustomerCreation(Customer customer, UUID transactionId) {
        if (customer == null || customer.getId() == null) {
            return;
        }
        try {
            CustomerAuditEvent customerEvent = CustomerAuditEvent.fromCustomer(
                    customer,
                    AuditAction.INSERT,
                    customer.getId(),
                    SERVICE_NAME,
                    transactionId
            );
            auditPublisher.publishCustomerAudit(customerEvent);
        } catch (RuntimeException e) {
            log.warn("Error al publicar auditoría de cliente", e);
        }
    }

    /**
     * Publica el evento de auditoría de inserción de una nueva transacción.
     *
     * @param transaction transacción recién creada
     */
    private void auditTransactionInsert(Transaction transaction) {
        try {
            TransactionAuditEvent transactionEvent = TransactionAuditEvent.fromTransaction(
                    transaction,
                    AuditAction.INSERT,
                    SERVICE_NAME
            );
            auditPublisher.publishTransactionAudit(transactionEvent);
        } catch (RuntimeException e) {
            log.warn("Error al publicar auditoría de transacción", e);
        }
    }
}
