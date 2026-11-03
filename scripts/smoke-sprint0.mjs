// Giữ tên lệnh kiểm chứng cũ; schema IAM sau V4 không còn cho phép bootstrap role bằng SQL.
// Smoke mới dựng hạ tầng tạm và gán role qua Keycloak Admin API, không chạm dữ liệu nhóm.
// Build JAR user/catalog/commerce/gateway trước; xem docs/ERD_V2_ALIGNMENT.md.
await import('./smoke-erd-v2.mjs')
