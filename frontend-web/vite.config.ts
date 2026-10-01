/// <reference types="vitest" />
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const gateway = env.VITE_DEV_PROXY_TARGET || "http://localhost:8080";
  return {
    plugins: [react()],
    server: {
      port: 5173,
      // Gọi /api/* và /ws/* sẽ được chuyển tới Spring Cloud Gateway khi dev
      proxy: {
        "/api": { target: gateway, changeOrigin: true },
        "/ws": { target: gateway, changeOrigin: true, ws: true },
      },
    },
    test: {
      environment: "jsdom",
      setupFiles: ["./src/test/setup.ts"],
      globals: true,
      coverage: {
        provider: "v8",
        reporter: ["text", "cobertura", "html"],
        include: ["src/**/*.{ts,tsx}"],
        exclude: ["src/main.tsx", "src/test/**", "**/*.d.ts"],
        thresholds: { lines: 70, statements: 70 },
      },
    },
  };
});
