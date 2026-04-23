package com.example.test.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.LockTimeoutException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.ExhaustedRetryException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AccountNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleRouteNotFound(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler({InsufficientFundsException.class, InvalidTransferException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler({DuplicateUserException.class, AccountNumberGenerationException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Concurrent wallet update detected. Please retry.", request);
    }

    @ExceptionHandler({
            CannotAcquireLockException.class,
            PessimisticLockingFailureException.class,
            DeadlockLoserDataAccessException.class,
            LockTimeoutException.class
    })
    public ResponseEntity<ErrorResponse> handleLockContention(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Transfer is busy due to concurrent updates. Please retry.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        FieldError firstError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = firstError == null ? "Validation error" : firstError.getField() + ": " + firstError.getDefaultMessage();
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler({ExhaustedRetryException.class, java.lang.reflect.UndeclaredThrowableException.class})
    public ResponseEntity<ErrorResponse> handleRetryWrapped(Exception ex, HttpServletRequest request) {
        Throwable root = rootCause(ex);
        return mapRootCause(root, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        Throwable root = rootCause(ex);
        if (root != ex) {
            return mapRootCause(root, request);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> mapRootCause(Throwable root, HttpServletRequest request) {
        if (root instanceof AccountNotFoundException) {
            return build(HttpStatus.NOT_FOUND, root.getMessage(), request);
        }
        if (root instanceof InsufficientFundsException
                || root instanceof InvalidTransferException
                || root instanceof ConstraintViolationException) {
            return build(HttpStatus.BAD_REQUEST, root.getMessage(), request);
        }
        if (root instanceof DuplicateUserException || root instanceof AccountNumberGenerationException) {
            return build(HttpStatus.CONFLICT, root.getMessage(), request);
        }
        if (root instanceof ObjectOptimisticLockingFailureException) {
            return build(HttpStatus.CONFLICT, "Concurrent wallet update detected. Please retry.", request);
        }
        if (root instanceof CannotAcquireLockException
                || root instanceof PessimisticLockingFailureException
                || root instanceof DeadlockLoserDataAccessException
                || root instanceof LockTimeoutException) {
            return build(HttpStatus.CONFLICT, "Transfer is busy due to concurrent updates. Please retry.", request);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred", request);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(body);
    }
}
