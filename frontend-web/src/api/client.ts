import axios, { AxiosError } from "axios";
import { getAccessToken } from "../auth/token";

/** Mọi request đi qua Spring Cloud Gateway, prefix /api/v1 (SRS 4.2). */
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api/v1",
  timeout: 10_000,
});

api.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

/** Định dạng lỗi chuẩn từ backend (ApiError). */
export interface ApiError {
  code: string;
  message: string;
  details: string[];
  timestamp: string;
}

export function toApiError(err: unknown): ApiError {
  if (err instanceof AxiosError && err.response?.data?.code) {
    return err.response.data as ApiError;
  }
  return {
    code: "NETWORK_ERROR",
    message: "Không thể kết nối tới máy chủ",
    details: [],
    timestamp: new Date().toISOString(),
  };
}
