package com.tumipay.orchestrator.infrastructure.adapter.in.rest.exception;

import com.tumipay.orchestrator.domain.exception.ConcurrentModificationException;
import com.tumipay.orchestrator.domain.exception.DuplicateTransactionException;
import com.tumipay.orchestrator.domain.exception.PaymentProviderNotFoundException;
import com.tumipay.orchestrator.domain.exception.TransactionNotFoundException;
import com.tumipay.orchestrator.infrastructure.adapter.in.rest.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Manejo centralizado de errores — traduce excepciones de dominio y validación
 * en respuestas de error de API estandarizadas.
 *
 * Diccionario de Códigos de Error:
 *   000 - Operación exitosa
 *   001 - Error de validación
 *   002 - Transacción duplicada
 *   003 - Transacción no encontrada
 *   004 - Proveedor de pago no encontrado
 *   005 - Error interno del servidor
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maneja errores de validación de Bean Validation (campos inválidos o faltantes).
     * Consolida todos los mensajes de error de campo en una sola cadena separada por punto y coma.
     *
     * @param ex excepción con los resultados de validación
     * @return respuesta HTTP 422 con código de error {@code "001"}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error("001", message));
    }

    /**
     * Maneja intentos de crear una transacción con un {@code clientTransactionId} ya registrado.
     *
     * @param ex excepción de transacción duplicada
     * @return respuesta HTTP 409 con código de error {@code "002"}
     */
    @ExceptionHandler(DuplicateTransactionException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateTransactionException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("002", ex.getMessage()));
    }

    /**
     * Maneja consultas de transacciones que no existen en el sistema.
     *
     * @param ex excepción de transacción no encontrada
     * @return respuesta HTTP 404 con código de error {@code "003"}
     */
    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(TransactionNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("003", ex.getMessage()));
    }

    /**
     * Maneja solicitudes con un {@code paymentMethodId} para el que no hay proveedor registrado.
     *
     * @param ex excepción de proveedor no encontrado
     * @return respuesta HTTP 400 con código de error {@code "004"}
     */
    @ExceptionHandler(PaymentProviderNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleProviderNotFound(PaymentProviderNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("004", ex.getMessage()));
    }

    /**
     * Maneja conflictos de concurrencia detectados por optimistic locking.
     *
     * @param ex excepción de modificación concurrente
     * @return respuesta HTTP 409 con código de error {@code "006"}
     */
    @ExceptionHandler(ConcurrentModificationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrentModification(ConcurrentModificationException ex) {
        log.warn("Conflicto de concurrencia detectado: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("006", ex.getMessage()));
    }

    /**
     * Maneja parámetros de ruta o query con formato incorrecto (p.ej. UUID mal formado).
     *
     * @param ex excepción de tipo incorrecto en el parámetro
     * @return respuesta HTTP 400 con código de error {@code "001"}
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("001", "Formato de parámetro inválido: " + ex.getName()));
    }

    /**
     * Captura cualquier excepción no manejada explícitamente por los otros handlers.
     * Registra el stack trace completo para análisis posterior.
     *
     * @param ex excepción inesperada
     * @return respuesta HTTP 500 con código de error {@code "005"}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Excepción no manejada", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("005", "Error interno del servidor"));
    }
}