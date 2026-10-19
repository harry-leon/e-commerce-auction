package com.nexus.catalog.exception;

/**
 * Lớp tương thích ngược kế thừa từ ResourceNotFoundException.
 * @deprecated Nên dùng trực tiếp {@link ResourceNotFoundException}.
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
