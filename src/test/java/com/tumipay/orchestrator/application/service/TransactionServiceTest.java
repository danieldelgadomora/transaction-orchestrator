package com.tumipay.orchestrator.application.service;

import com.tumipay.orchestrator.application.port.in.CreateTransactionCommand;
import com.tumipay.orchestrator.domain.exception.DuplicateTransactionException;
import com.tumipay.orchestrator.domain.exception.PaymentProviderNotFoundException;
import com.tumipay.orchestrator.domain.exception.TransactionNotFoundException;
import com.tumipay.orchestrator.domain.model.Customer;
import com.tumipay.orchestrator.domain.model.CustomerResult;
import com.tumipay.orchestrator.domain.model.Transaction;
import com.tumipay.orchestrator.domain.model.TransactionStatus;
import com.tumipay.orchestrator.application.port.AuditUseCase;
import com.tumipay.orchestrator.domain.port.out.CircuitBreakerPort;
import com.tumipay.orchestrator.domain.port.out.CustomerRepositoryPort;
import com.tumipay.orchestrator.domain.port.out.PaymentProviderPort;
import com.tumipay.orchestrator.domain.port.out.TransactionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepositoryPort transactionRepositoryPort;

    @Mock
    private CustomerRepositoryPort customerRepositoryPort;

    @Mock
    private AuditUseCase auditUseCase;

    @Mock
    private CircuitBreakerPort circuitBreaker;

    private PaymentProviderPort mockProvider;

    private TransactionService transactionService;

    private CreateTransactionCommand validCommand;

    @BeforeEach
    void setUp() {
        mockProvider = new PaymentProviderPort() {
            @Override public String getSupportedPaymentMethodId() { return "MOCK_PSP"; }
            @Override public TransactionStatus process(Transaction t) { return TransactionStatus.APPROVED; }
        };

        transactionService = new TransactionService(transactionRepositoryPort, customerRepositoryPort, auditUseCase, List.of(mockProvider), circuitBreaker);
        transactionService.initializeProviderRegistry();

        validCommand = CreateTransactionCommand.builder()
                .clientTransactionId("CLIENT-001")
                .amountCents(100000L)
                .currencyCode("COP")
                .countryCode("CO")
                .paymentMethodId("MOCK_PSP")
                .webhookUrl("https://example.com/webhook")
                .redirectUrl("https://example.com/return")
                .description("Test transaction")
                .customer(Customer.builder()
                        .id(UUID.randomUUID())
                        .documentType("CC")
                        .documentNumber("12345")
                        .countryCallingCode("+57")
                        .phoneNumber("3001234567")
                        .email("test@example.com")
                        .firstName("Juan")
                        .lastName("Perez")
                        .build())
                .build();
    }

    @Test
    @DisplayName("Debe crear y procesar una transacción exitosamente")
    void createTransaction_success() {
        when(transactionRepositoryPort.existsByClientTransactionId("CLIENT-001")).thenReturn(false);
        when(circuitBreaker.executeWithCircuitBreaker(any(), any())).thenReturn(TransactionStatus.APPROVED);
        when(customerRepositoryPort.findOrCreate(any())).thenAnswer(inv -> {
            Customer input = inv.getArgument(0);
            Customer result;
            boolean isNew = input.getId() == null;
            if (isNew) {
                result = Customer.builder()
                        .id(UUID.randomUUID())
                        .documentType(input.getDocumentType())
                        .documentNumber(input.getDocumentNumber())
                        .countryCallingCode(input.getCountryCallingCode())
                        .phoneNumber(input.getPhoneNumber())
                        .email(input.getEmail())
                        .firstName(input.getFirstName())
                        .lastName(input.getLastName())
                        .build();
            } else {
                result = input;
            }
            return new CustomerResult(result, isNew);
        });
        when(transactionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transactionService.createTransaction(validCommand);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(result.getClientTransactionId()).isEqualTo("CLIENT-001");
        verify(transactionRepositoryPort, times(2)).save(any());
        verify(customerRepositoryPort, times(1)).findOrCreate(any());
    }

    @Test
    @DisplayName("Debe lanzar DuplicateTransactionException cuando clientTransactionId ya existe")
    void createTransaction_duplicate() {
        when(transactionRepositoryPort.existsByClientTransactionId("CLIENT-001")).thenReturn(true);

        assertThatThrownBy(() -> transactionService.createTransaction(validCommand))
                .isInstanceOf(DuplicateTransactionException.class);

        verify(transactionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Debe lanzar PaymentProviderNotFoundException para método de pago desconocido")
    void createTransaction_unknownProvider() {
        CreateTransactionCommand cmd = CreateTransactionCommand.builder()
                .clientTransactionId("CLIENT-002")
                .amountCents(1000L)
                .currencyCode("COP")
                .countryCode("CO")
                .paymentMethodId("UNKNOWN_PSP")
                .webhookUrl("https://example.com/wh")
                .redirectUrl("https://example.com/r")
                .customer(validCommand.getCustomer())
                .build();

        when(transactionRepositoryPort.existsByClientTransactionId(any())).thenReturn(false);
        when(customerRepositoryPort.findOrCreate(any())).thenReturn(
                new CustomerResult(validCommand.getCustomer(), false));
        when(transactionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> transactionService.createTransaction(cmd))
                .isInstanceOf(PaymentProviderNotFoundException.class);
    }

    @Test
    @DisplayName("Debe recuperar transacción por id")
    void getTransaction_found() {
        UUID id = UUID.randomUUID();
        Transaction tx = Transaction.create("C1", 1000L, "COP", "CO", "MOCK_PSP",
                "https://wh.com", "https://r.com", null, null, validCommand.getCustomer());

        when(transactionRepositoryPort.findById(id)).thenReturn(Optional.of(tx));

        Transaction result = transactionService.getTransaction(id);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Debe lanzar TransactionNotFoundException cuando no se encuentra")
    void getTransaction_notFound() {
        UUID id = UUID.randomUUID();
        when(transactionRepositoryPort.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransaction(id))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    @DisplayName("Debe manejar OptimisticLockException cuando ocurre actualización concurrente")
    void createTransaction_optimisticLockException_shouldPropagate() {
        // Simula escenario de concurrencia donde otra transacción modifica la misma entidad
        // entre el primer save (creación) y el segundo save (actualización de status)

        when(transactionRepositoryPort.existsByClientTransactionId("CLIENT-001")).thenReturn(false);
        when(circuitBreaker.executeWithCircuitBreaker(any(), any())).thenReturn(TransactionStatus.APPROVED);
        when(customerRepositoryPort.findOrCreate(any())).thenReturn(
                new CustomerResult(validCommand.getCustomer(), false));

        // Primera llamada a save (creación) - éxito
        // Segunda llamada a save (update con status del PSP) - lanza OptimisticLockException
        when(transactionRepositoryPort.save(any()))
                .thenAnswer(inv -> inv.getArgument(0)) // primera llamada: creación exitosa
                .thenThrow(new jakarta.persistence.OptimisticLockException(
                        "Row was updated or deleted by another transaction")); // segunda: conflicto

        assertThatThrownBy(() -> transactionService.createTransaction(validCommand))
                .isInstanceOf(jakarta.persistence.OptimisticLockException.class)
                .hasMessageContaining("Row was updated or deleted by another transaction");

        // Verificar que se intentó guardar dos veces (creación + update)
        verify(transactionRepositoryPort, times(2)).save(any());
        // El audit de creación se llamó, pero el de update no porque falló antes
        verify(auditUseCase).auditTransactionCreation(any(), anyBoolean());
    }
}
