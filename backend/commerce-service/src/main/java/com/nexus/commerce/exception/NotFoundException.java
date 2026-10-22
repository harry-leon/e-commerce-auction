package com.nexus.commerce.exception;

/**
 * Backward-compatible exception class extending ResourceNotFoundException.
 * @deprecated Prefer using {@link ResourceNotFoundException} directly.
 */
@Deprecated
public class NotFoundException extends ResourceNotFoundException {
    public NotFoundException(String message) {
        super(message);
    }
    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
