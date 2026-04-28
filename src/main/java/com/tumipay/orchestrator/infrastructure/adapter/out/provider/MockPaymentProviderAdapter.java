package com.tumipay.orchestrator.infrastructure.adapter.out.provider;

import com.tumipay.orchestrator.domain.model.Transaction;
import com.tumipay.orchestrator.domain.model.TransactionStatus;
import com.tumipay.orchestrator.domain.port.out.PaymentProviderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Adaptador de proveedor de pago de ejemplo — simula una integración PSP.
 *
 * Para agregar un nuevo proveedor de pago:
 *   1. Crear una nueva clase implementando PaymentProviderPort.
 *   2. Anotarla con @Component.
 *   3. Retornar el paymentMethodId correcto desde getSupportedPaymentMethodId().
 *   No se necesitan otros cambios (principio abierto/cerrado).
 */
@Slf4j
@Component
public class MockPaymentProviderAdapter implements PaymentProviderPort {

    private static final String PAYMENT_METHOD_ID = "MOCK_PSP";

    @Override
    public String getSupportedPaymentMethodId() {
        return PAYMENT_METHOD_ID;
    }

    /**
     * Simula el procesamiento de una transacción por un PSP externo.
     * Siempre retorna {@link TransactionStatus#APPROVED}; en producción esto sería una llamada HTTP al PSP.
     *
     * @param transaction transacción a procesar
     * @return {@link TransactionStatus#APPROVED} de forma incondicional
     */
    @Override
    public TransactionStatus process(Transaction transaction) {
        log.info("MockPaymentProvider processing transaction id={}, amount={} {}",
                transaction.getId(), transaction.getAmountCents(), transaction.getCurrencyCode());
        return TransactionStatus.APPROVED;
    }
}
