import { useQuery } from "@tanstack/react-query";
import { api, toApiError } from "../../api/client";

export default function HomePage() {
  // Gọi thử endpoint ping của backend qua Gateway
  const { data, error, isLoading } = useQuery({
    queryKey: ["ping"],
    queryFn: async () => (await api.get("/ping")).data as { service: string; status: string },
  });

  return (
    <main>
      <h1>Nexus</h1>
      {isLoading && <p>Đang kiểm tra kết nối...</p>}
      {error && <p role="alert">Lỗi: {toApiError(error).message}</p>}
      {data && (
        <p>
          Backend: {data.service} ({data.status})
        </p>
      )}
    </main>
  );
}
