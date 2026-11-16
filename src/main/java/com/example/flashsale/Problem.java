package com.example.flashsale;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.*;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
public class Problem {
    static ResponseEntity<ProblemDetail> response(int code, String detail) {
        return ResponseEntity.status(code).body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code), detail));
    }

    @ExceptionHandler(Rejected.class)
    ResponseEntity<ProblemDetail> rejected(Rejected e) {
        return response(e.status, e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e) {
        return response(400, "Invalid request");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException e) {
        return response(409, "Resource conflict or inventory constraint violation");
    }

    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
    ResponseEntity<ProblemDetail> database(Exception e) {
        return response(503, "Database operation unavailable; retry the same idempotency key with bounded backoff");
    }

    public static class Rejected extends RuntimeException {
        final int status;

        public Rejected(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
