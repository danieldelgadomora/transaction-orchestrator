package com.tumipay.orchestrator.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Configuración de topics Kafka para auditoría.
 * Crea automáticamente los topics necesarios si no existen.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${kafka.topics.customer-audit}")
    private String customerAuditTopic;

    @Value("${kafka.topics.transaction-audit}")
    private String transactionAuditTopic;

    /**
     * Crea (o valida la existencia de) el topic Kafka para eventos de auditoría de clientes.
     * Configurado con 3 particiones para paralelismo y réplica de 1 (entorno de desarrollo).
     *
     * @return definición del topic Kafka para auditoría de clientes
     */
    @Bean
    public NewTopic customerAuditTopic() {
        return TopicBuilder.name(customerAuditTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Crea (o valida la existencia de) el topic Kafka para eventos de auditoría de transacciones.
     * Configurado con 3 particiones para paralelismo y réplica de 1 (entorno de desarrollo).
     *
     * @return definición del topic Kafka para auditoría de transacciones
     */
    @Bean
    public NewTopic transactionAuditTopic() {
        return TopicBuilder.name(transactionAuditTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
