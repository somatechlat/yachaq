package com.yachaq.api.account;

/**
 * Exception thrown when attempting to register with an existing email.
 */
public class EmailAlreadyExistsException extends RuntimeException {
    
    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}
