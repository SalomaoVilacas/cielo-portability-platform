package com.example.portability.api;

import com.example.portability.integration.IntegrationFailure;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> domain(ResponseStatusException e) {
        return problem(e.getStatusCode(), e.getReason());
    }

    @ExceptionHandler(IntegrationFailure.class)
    ResponseEntity<ProblemDetail> integration(IntegrationFailure e) {
        return problem(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,
    })
    ResponseEntity<ProblemDetail> validation(Exception e) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "Confira os campos obrigatórios e o formato dos dados enviados"
        );
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatusCode s, String d) {
        return ResponseEntity.status(s).body(ProblemDetail.forStatusAndDetail(s, d));
    }
}
