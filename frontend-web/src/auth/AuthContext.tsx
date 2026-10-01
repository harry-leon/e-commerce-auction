import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { setAccessToken } from "./token";

export interface AuthUser {
  id: string;
  fullName: string;
  /** Khớp bảng Privileges trong SRS 2.7, ví dụ "PRODUCT.CREATE", "AUCTION.BID" */
  privileges: string[];
}

interface AuthState {
  user: AuthUser | null;
  login: (token: string, user: AuthUser) => void;
  logout: () => void;
  can: (privilege: string) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);

  const login = useCallback((token: string, u: AuthUser) => {
    setAccessToken(token);
    setUser(u);
  }, []);

  const logout = useCallback(() => {
    setAccessToken(null);
    setUser(null);
  }, []);

  const can = useCallback((p: string) => !!user?.privileges.includes(p), [user]);

  const value = useMemo(() => ({ user, login, logout, can }), [user, login, logout, can]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}
