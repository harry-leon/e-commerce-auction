import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "./AuthContext";

/** Chặn route/UI theo privilege. Chỉ là UX; quyền thật do backend kiểm tra. */
export function RequirePrivilege({
  privilege,
  children,
}: {
  privilege?: string;
  children: ReactNode;
}) {
  const { user, can } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (privilege && !can(privilege)) return <Navigate to="/" replace />;
  return <>{children}</>;
}
