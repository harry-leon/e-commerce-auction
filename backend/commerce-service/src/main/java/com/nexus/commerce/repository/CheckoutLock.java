package com.nexus.commerce.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Khóa transaction PostgreSQL dùng chung mọi instance; tự nhả khi commit/rollback, không dùng khóa RAM. */
@Repository
@RequiredArgsConstructor
public class CheckoutLock {
    private final JdbcTemplate jdbc;

    /** Caller luôn khóa key trước buyer để replay an toàn và tuần tự hóa checkout/hạn mức của cùng người mua. */
    public void acquire(String resource) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "checkout:" + resource);
    }
}
