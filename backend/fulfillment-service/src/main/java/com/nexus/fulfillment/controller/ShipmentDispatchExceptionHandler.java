package com.nexus.fulfillment.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ánh xạ lỗi nghiệp vụ của API shipment/preparation/recovery đúng status, không để 409/401 thành lỗi server chung. */
@RestControllerAdvice(assignableTypes = {ShipmentDispatchResolutionController.class, ShipmentPreparationController.class,
        ShipmentController.class, ShippingEventReplayController.class})
public class ShipmentDispatchExceptionHandler {
    /** Chỉ trả mã/thông điệp nghiệp vụ đã kiểm soát; không trả cause/stacktrace hoặc snapshot PII. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handle(ApiException error) {
        return ResponseEntity.status(error.getHttpStatus()).body(ApiResponse.error(error.getCode(), error.getMessage(), null));
    }
}
