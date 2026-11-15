import { defineConfig } from 'vitest/config'

// Đo toàn bộ TS/TSX ứng dụng, kể cả module chưa được import bởi test; không bỏ các trang demo khỏi báo cáo.
// Ngưỡng LINE 70% giống quy ước JaCoCo backend; test/fixture hỗ trợ kiểm thử không phải code ứng dụng.
export default defineConfig({
    test: {
        include: ['src/**/*.test.{ts,tsx}'],
        environment: 'jsdom',
        // pool vmThreads giữ hành vi tương thích Vitest 4: chia sẻ module context giữa các worker thread,
        // tránh lỗi "cannot find runner/suite" khi Vitest 5 dùng vmForks (context VM tách biệt hoàn toàn).
        pool: 'vmThreads',
        // Tăng timeout lên 15s để đảm bảo các bộ test tích hợp UI lớn (features, checkout, registration) không bị timeout khi chạy song song.
        testTimeout: 15000,
        setupFiles: ['src/test/setup.ts'],
        coverage: {
            provider: 'v8',
            reporter: ['text', 'text-summary', 'cobertura', 'html'],
            include: ['src/**/*.{ts,tsx}'],
            exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/**/*.d.ts'],
            thresholds: { lines: 70 },
        },
    },
})
