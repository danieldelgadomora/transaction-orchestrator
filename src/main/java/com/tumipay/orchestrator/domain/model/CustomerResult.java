package com.tumipay.orchestrator.domain.model;

/**
 * Resultado de la operación findOrCreate en CustomerRepository.
 * Contiene el cliente resuelto y un flag que indica si fue creado nuevo
 * en esta operación (true) o si ya existía (false).
 */
public record CustomerResult(Customer customer, boolean isNew) {}
