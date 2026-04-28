package com.tumipay.orchestrator.domain.port.out;

import com.tumipay.orchestrator.domain.model.Transaction;

import java.util.Optional;
import java.util.UUID;

/**
 * Puerto secundario (salida) — contrato de persistencia.
 * El dominio define QUÉ necesita; la infraestructura provee CÓMO.
 */
public interface TransactionRepositoryPort {

    /**
     * Persiste una transacción nueva o actualiza una existente.
     *
     * @param transaction transacción a guardar
     * @return la transacción guardada con datos actualizados por la infraestructura
     */
    Transaction save(Transaction transaction);

    /**
     * Busca una transacción por su ID interno.
     *
     * @param id UUID interno de la transacción
     * @return {@link Optional} con la transacción si existe, vacío en caso contrario
     */
    Optional<Transaction> findById(UUID id);

    /**
     * Verifica si ya existe una transacción con el {@code clientTransactionId} dado.
     * Usado para garantizar idempotencia en la creación de transacciones.
     *
     * @param clientTransactionId identificador externo proporcionado por el cliente
     * @return {@code true} si ya existe una transacción con ese ID
     */
    boolean existsByClientTransactionId(String clientTransactionId);
}
