package com.yachaq.api.account;

/**
 * Exception thrown when an operation is invalid for the account type.
 */
public class InvalidAccountTypeException extends RuntimeException {
    
    public InvalidAccountTypeException(String message) {
        super(message);
    }
}
