package com.tumipay.orchestrator.application.port;

import com.tumipay.orchestrator.domain.model.Transaction;
import com.tumipay.orchestrator.domain.model.TransactionStatus;

/**
 * Contrato interno de la capa de aplicación para la auditoría.
 *
 * Se ubica en application/port (no en domain/port/in) porque es una
 * dependencia entre servicios de aplicación: TransactionService lo llama,
 * AuditService lo implementa. No es un puerto primario expuesto al exterior
 * del hexágono, sino una colaboración interna dentro de la capa de aplicación.
 */
public interface AuditUseCase {

    /**
     * Registra auditoría para la creación de una transacción.
     * Incluye auditoría del cliente asociado solo si es un cliente nuevo.
     *
     * @param transaction   la transacción creada
     * @param isNewCustomer true si el cliente fue creado nuevo, false si ya existía
     */
    void auditTransactionCreation(Transaction transaction, boolean isNewCustomer);

    /**
     * Registra auditoría para la actualización de estado de una transacción.
     *
     * @param transaction la transacción actualizada
     * @param oldStatus   el estado anterior
     */
    void auditTransactionUpdate(Transaction transaction, TransactionStatus oldStatus);
}
