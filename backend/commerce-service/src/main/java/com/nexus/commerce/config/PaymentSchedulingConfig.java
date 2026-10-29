package com.nexus.commerce.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật worker outbox sau IPN; tác vụ được lưu DB trước nên restart service không làm mất confirm/release kho. */
@Configuration
@EnableScheduling
public class PaymentSchedulingConfig { }
