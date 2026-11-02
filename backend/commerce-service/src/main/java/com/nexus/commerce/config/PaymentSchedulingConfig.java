package com.nexus.commerce.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật worker outbox kho và thông báo; tác vụ lưu DB trước nên restart không làm mất sự kiện đã commit. */
@Configuration
@EnableScheduling
public class PaymentSchedulingConfig { }
