package com.tumipay.orchestrator.application.service;

import com.tumipay.orchestrator.domain.exception.DuplicateTransactionException;
import com.tumipay.orchestrator.domain.exception.PaymentProviderNotFoundException;
import com.tumipay.orchestrator.domain.exception.TransactionNotFoundException;
import com.tumipay.orchestrator.domain.model.Customer;
import com.tumipay.orchestrator.domain.model.CustomerResult;
import com.tumipay.orchestrator.domain.model.Transaction;
import com.tumipay.orchestrator.domain.model.TransactionStatus;
import com.tumipay.orchestrator.application.port.AuditUseCase;
import com.tumipay.orchestrator.application.port.in.CreateTransactionCommand;
import com.tumipay.orchestrator.application.port.in.TransactionUseCase;
import com.tumipay.orchestrator.domain.port.out.CircuitBreakerPort;
import com.tumipay.orchestrator.domain.port.out.CustomerRepositoryPort;
import com.tumipay.orchestrator.domain.port.out.PaymentProviderPort;
import com.tumipay.orchestrator.domain.port.out.TransactionRepositoryPort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Servicio de Aplicación — orquesta el flujo de transacciones.
 *
 * Decisiones de diseño:
 * - Implementa el puerto de entrada (TransactionUseCase).
 * - Depende exclusivamente de puertos de salida (interfaces), nunca de adaptadores concretos.
 * - Los proveedores de pago se resuelven en tiempo de ejecución mediante un Mapa construido
 *   desde todos los adaptadores registrados (patrón Strategy + Registry), permitiendo
 *   extensión sin modificar código para nuevos PSPs.
 * - La transacción se persiste ANTES de enviarla al proveedor, garantizando
 *   semántica de entrega al-menos-una-vez y trazabilidad completa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService implements TransactionUseCase {

    private final TransactionRepositoryPort transactionRepositoryPort;
    private final CustomerRepositoryPort customerRepositoryPort;
    private final AuditUseCase auditUseCase;
    private final List<PaymentProviderPort> paymentProviders;
    private final CircuitBreakerPort circuitBreaker;

    private Map<String, PaymentProviderPort> providerRegistry;

    /** Construye el mapa de proveedores indexado por paymentMethodId tras la inyección de dependencias. */
    @PostConstruct
    void initializeProviderRegistry() {
        this.providerRegistry = paymentProviders.stream()
                .collect(Collectors.toMap(
                        PaymentProviderPort::getSupportedPaymentMethodId,
                        Function.identity()
                ));
    }

    /**
     * Crea y procesa una nueva transacción de pago.
     *
     * Flujo:
     * 1. Valida idempotencia por {@code clientTransactionId}.
     * 2. Resuelve o crea el cliente.
     * 3. Construye y persiste la transacción en estado PENDING.
     * 4. Publica evento de auditoría de creación.
     * 5. Envía al proveedor de pago con protección de Circuit Breaker.
     * 6. Actualiza el estado final y publica auditoría de actualización si cambió.
     *
     * @param command datos del comando de creación
     * @return la transacción con su estado final
     * @throws com.tumipay.orchestrator.domain.exception.DuplicateTransactionException si el {@code clientTransactionId} ya existe
     * @throws com.tumipay.orchestrator.domain.exception.PaymentProviderNotFoundException si el proveedor no está registrado
     */
    @Override
    @Transactional
    public Transaction createTransaction(CreateTransactionCommand command) {
        log.info("Creando transacción para clientTransactionId={}", command.getClientTransactionId());

        validateIdempotency(command.getClientTransactionId());

        var customerResult = resolveCustomer(command.getCustomer());
        Transaction transaction = buildTransaction(command, customerResult.customer());

        Transaction saved = transactionRepositoryPort.save(transaction);
        log.info("Transacción persistida con id={}", saved.getId());

        auditUseCase.auditTransactionCreation(saved, customerResult.isNew());

        Transaction finalTransaction = processWithProvider(saved, command.getPaymentMethodId());

        log.info("Transacción id={} procesada con status={}", finalTransaction.getId(), finalTransaction.getStatus());
        return finalTransaction;
    }

    /**
     * Delega en el repositorio de clientes para buscar o crear el cliente de la transacción.
     *
     * @param customer datos del cliente provenientes del comando
     * @return resultado con el cliente resuelto y flag indicando si fue creado nuevo
     */
    private CustomerResult resolveCustomer(Customer customer) {
        var result = customerRepositoryPort.findOrCreate(customer);
        log.debug("Cliente resuelto: id={}, isNew={}, document={}/{}",
                result.customer().getId(),
                result.isNew(),
                result.customer().getDocumentType(),
                result.customer().getDocumentNumber());
        return result;
    }

    /**
     * Verifica que no exista ya una transacción con el mismo {@code clientTransactionId}.
     *
     * @param clientTransactionId identificador proporcionado por el cliente
     * @throws com.tumipay.orchestrator.domain.exception.DuplicateTransactionException si ya existe
     */
    private void validateIdempotency(String clientTransactionId) {
        if (transactionRepositoryPort.existsByClientTransactionId(clientTransactionId)) {
            throw new DuplicateTransactionException(clientTransactionId);
        }
    }

    /**
     * Construye el modelo de dominio {@link Transaction} a partir del comando y el cliente resuelto.
     *
     * @param command  comando con los datos de la transacción
     * @param customer cliente ya persistido con ID asignado
     * @return nueva instancia de {@link Transaction} en estado PENDING
     */
    private Transaction buildTransaction(CreateTransactionCommand command, Customer customer) {
        return Transaction.create(
                command.getClientTransactionId(),
                command.getAmountCents(),
                command.getCurrencyCode(),
                command.getCountryCode(),
                command.getPaymentMethodId(),
                command.getWebhookUrl(),
                command.getRedirectUrl(),
                command.getDescription(),
                command.getExpirationSeconds(),
                customer
        );
    }

    /**
     * Envía la transacción al proveedor de pago y persiste el estado resultante.
     * Si el estado cambió, publica un evento de auditoría de actualización.
     *
     * @param saved           transacción ya persistida en estado PENDING
     * @param paymentMethodId identificador del proveedor de pago
     * @return transacción con el estado final actualizado
     */
    private Transaction processWithProvider(Transaction saved, String paymentMethodId) {
        PaymentProviderPort provider = resolveProvider(paymentMethodId);
        TransactionStatus resultStatus = circuitBreaker.executeWithCircuitBreaker(provider, saved);

        Transaction updated = saved.withStatus(resultStatus);
        Transaction finalTransaction = transactionRepositoryPort.save(updated);

        if (resultStatus != saved.getStatus()) {
            auditUseCase.auditTransactionUpdate(finalTransaction, saved.getStatus());
        }

        return finalTransaction;
    }

    /**
     * Consulta una transacción por su ID interno.
     *
     * @param transactionId UUID interno de la transacción
     * @return la transacción encontrada
     * @throws com.tumipay.orchestrator.domain.exception.TransactionNotFoundException si no existe
     */
    @Override
    @Transactional(readOnly = true)
    public Transaction getTransaction(UUID transactionId) {
        return transactionRepositoryPort.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId.toString()));
    }

    /**
     * Obtiene el proveedor de pago registrado para el {@code paymentMethodId} dado.
     *
     * @param paymentMethodId identificador del método de pago
     * @return el adaptador de proveedor correspondiente
     * @throws com.tumipay.orchestrator.domain.exception.PaymentProviderNotFoundException si no hay proveedor registrado
     */
    private PaymentProviderPort resolveProvider(String paymentMethodId) {
        PaymentProviderPort provider = providerRegistry.get(paymentMethodId);
        if (provider == null) {
            throw new PaymentProviderNotFoundException(paymentMethodId);
        }
        return provider;
    }

}
