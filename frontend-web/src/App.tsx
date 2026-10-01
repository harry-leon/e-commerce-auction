import { Route, Routes } from "react-router-dom";
import HomePage from "./pages/Home/HomePage";
import LoginPage from "./pages/LoginPage";
import NotFoundPage from "./pages/NotFoundPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/login" element={<LoginPage />} />
      {/* Thêm route theo feature, bọc <RequirePrivilege privilege="AUCTION.BID"> khi cần */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
