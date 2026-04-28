package com.tumipay.orchestrator.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Configuración adicional para consumidores Kafka.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    /**
     * Crea el factory de contenedores de listeners Kafka con concurrencia limitada a 1 hilo.
     * La concurrencia de 1 garantiza orden de procesamiento por partición para los eventos de auditoría.
     *
     * @param consumerFactory factory de consumidores configurada por Spring Boot auto-config
     * @return factory de contenedores lista para usar en {@code @KafkaListener}
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        return factory;
    }
}
