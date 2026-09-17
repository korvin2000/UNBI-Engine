package com.unbi.engine.transport;

import com.unbi.engine.llm.auth.CredentialSessionsController;
import com.unbi.engine.llm.spec.LlmFailure;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Only credential-management errors; never changes application authentication or graph APIs. */
@RestControllerAdvice(assignableTypes = {CredentialController.class, CredentialSessionsController.class})
public class CredentialErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalid(IllegalArgumentException failure) {
        return problem(HttpStatus.BAD_REQUEST, failure.getMessage() == null ? "Invalid credential configuration" : failure.getMessage());
    }

    @ExceptionHandler(LlmFailure.class)
    public ResponseEntity<ProblemDetail> authentication(LlmFailure failure) {
        return problem(failure.kind() == LlmFailure.Kind.CANCELLED ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY, failure.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ProblemDetail> storage(IOException failure) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Credential storage could not be updated; check its location and private permissions");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
