import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { AuthProvider, useAuth } from "./AuthContext";
import { getAccessToken } from "./token";

const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;

describe("AuthContext", () => {
  it("login lưu token và cho phép kiểm tra privilege", () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    act(() =>
      result.current.login("tok", { id: "1", fullName: "A", privileges: ["AUCTION.BID"] }),
    );
    expect(getAccessToken()).toBe("tok");
    expect(result.current.can("AUCTION.BID")).toBe(true);
    expect(result.current.can("PRODUCT.CREATE")).toBe(false);
  });

  it("logout xóa token và user", () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    act(() => result.current.login("tok", { id: "1", fullName: "A", privileges: [] }));
    act(() => result.current.logout());
    expect(getAccessToken()).toBeNull();
    expect(result.current.user).toBeNull();
  });
});
