package com.tumipay.orchestrator.infrastructure.adapter.out.messaging.kafka;

import com.tumipay.orchestrator.domain.model.CustomerAuditEvent;
import com.tumipay.orchestrator.domain.model.TransactionAuditEvent;
import com.tumipay.orchestrator.domain.port.out.AuditPublisherPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Adaptador de salida que implementa la publicación de eventos de auditoría usando Kafka.
 * Desacopla el dominio de la infraestructura de mensajería.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaAuditPublisherAdapter implements AuditPublisherPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String LOG_PUBLISH_ERROR = "Error al publicar evento de auditoría";

    @Value("${kafka.topics.customer-audit}")
    private String customerAuditTopic;

    @Value("${kafka.topics.transaction-audit}")
    private String transactionAuditTopic;

    /**
     * Publica un evento de auditoría de cliente en el topic Kafka configurado.
     *
     * @param event evento de auditoría de cliente; no puede ser {@code null}
     */
    @Override
    public void publishCustomerAudit(CustomerAuditEvent event) {
        Objects.requireNonNull(event, "CustomerAuditEvent no puede ser null");
        log.info("Publicando evento de auditoría de cliente: customerId={}, action={}",
                event.getCustomerId(), event.getAction());
        publishEvent(customerAuditTopic, event.getCustomerId().toString(), event, "cliente");
    }

    /**
     * Publica un evento de auditoría de transacción en el topic Kafka configurado.
     *
     * @param event evento de auditoría de transacción; no puede ser {@code null}
     */
    @Override
    public void publishTransactionAudit(TransactionAuditEvent event) {
        Objects.requireNonNull(event, "TransactionAuditEvent no puede ser null");
        log.info("Publicando evento de auditoría de transacción: transactionId={}, action={}, status={}",
                event.getTransactionId(), event.getAction(), event.getStatus());
        publishEvent(transactionAuditTopic, event.getTransactionId().toString(), event, "transacción");
    }

    /**
     * Envía el evento al topic Kafka especificado de forma asíncrona.
     * Los errores de envío se registran en log sin relanzar la excepción para no bloquear el flujo principal.
     *
     * @param topic      nombre del topic Kafka destino
     * @param key        clave del mensaje (se usa para particionado)
     * @param event      objeto a serializar como valor del mensaje
     * @param entityType descripción del tipo de entidad (para logs)
     */
    private void publishEvent(String topic, String key, Object event, String entityType) {
        try {
            kafkaTemplate.send(topic, key, event)
                    .whenComplete((result, ex) -> handlePublishResult(result, ex, entityType));
        } catch (KafkaException e) {
            log.error("{} de {}: {}", LOG_PUBLISH_ERROR, entityType, e.getMessage(), e);
        }
    }

    /**
     * Callback invocado por Kafka tras completar el envío asíncrono.
     * Registra la partición/offset en caso de éxito, o el error en caso de fallo.
     *
     * @param result     metadatos del mensaje enviado; {@code null} si hubo error
     * @param ex         excepción en caso de fallo; {@code null} si fue exitoso
     * @param entityType descripción del tipo de entidad (para logs)
     */
    private void handlePublishResult(org.springframework.kafka.support.SendResult<String, Object> result,
                                     Throwable ex, String entityType) {
        if (ex != null) {
            log.error("{} de {}: {}", LOG_PUBLISH_ERROR, entityType, ex.getMessage(), ex);
        } else {
            log.debug("Evento de auditoría de {} publicado: partition={}, offset={}",
                    entityType,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        }
    }
}
