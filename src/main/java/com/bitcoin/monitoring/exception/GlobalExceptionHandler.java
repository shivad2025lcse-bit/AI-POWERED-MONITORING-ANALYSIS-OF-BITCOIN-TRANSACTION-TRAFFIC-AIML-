package com.bitcoin.monitoring.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> statusError(ResponseStatusException exception, HttpServletRequest request) {
        return error(exception.getStatusCode().value(), exception.getStatusCode().toString(), exception.getReason(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API error on {}", request.getRequestURI(), exception);
        return error(500, "INTERNAL_SERVER_ERROR", "The request could not be completed", request.getRequestURI());
    }

    private ResponseEntity<Map<String, Object>> error(int status, String name, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status);
        body.put("error", name);
        body.put("message", message == null ? "Request failed" : message);
        body.put("path", path);
        return ResponseEntity.status(status).body(body);
    }
}