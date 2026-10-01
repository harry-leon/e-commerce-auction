// Access token giữ trong bộ nhớ (không lưu localStorage) để giảm rủi ro XSS đánh cắp token.
// Refresh token nên do backend set qua cookie HttpOnly; khi tải lại trang, gọi API refresh để lấy access token mới.
let accessToken: string | null = null;

export const getAccessToken = () => accessToken;
export const setAccessToken = (token: string | null) => {
  accessToken = token;
};
