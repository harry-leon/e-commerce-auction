import { AxiosError } from "axios";
import { toApiError } from "./client";

describe("toApiError", () => {
  it("trả về ApiError từ response của backend", () => {
    const err = new AxiosError("x", "400", undefined, undefined, {
      data: { code: "VALIDATION_ERROR", message: "bad", details: [], timestamp: "t" },
    } as never);
    expect(toApiError(err).code).toBe("VALIDATION_ERROR");
  });

  it("trả về NETWORK_ERROR khi không có response", () => {
    expect(toApiError(new Error("offline")).code).toBe("NETWORK_ERROR");
  });
});
