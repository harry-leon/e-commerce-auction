package com.nexus.commerce.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/** IPN phải trả đúng wire protocol VNPAY thay vì envelope Nexus; endpoint buyer vẫn dùng ApiResponse v2. */
public record VnpayIpnResponse(@JsonProperty("RspCode") String code, @JsonProperty("Message") String message) { }
