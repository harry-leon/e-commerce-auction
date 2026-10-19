import { defineConfig } from 'vitest/config'

// Chỉ thu thập unit test trong src; test trình duyệt chạy bằng Playwright riêng.
// Coverage chỉ tính api.ts và routing.ts, không đại diện toàn bộ component UI.
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
    coverage: {
      provider: 'v8',
      reporter: ['text', 'cobertura', 'html'],
      include: ['src/api.ts', 'src/routing.ts'],
    },
  },
})
