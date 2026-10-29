package com.nexus.commerce.config;

import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;

/** Cho phép callback bù trừ kho nhận trạng thái rollback xác nhận khi commit DB lỗi. */
@Configuration
public class CheckoutTransactionConfig {
    /** Commit lỗi phải thử rollback; nếu rollback cũng lỗi, Spring vẫn báo UNKNOWN để tránh release sai đơn đã lưu. */
    @Bean
    TransactionManagerCustomizer<JpaTransactionManager> checkoutRollbackOnCommitFailure() {
        return manager -> manager.setRollbackOnCommitFailure(true);
    }
}
