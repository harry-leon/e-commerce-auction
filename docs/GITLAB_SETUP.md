# Cài đặt GitLab cho monorepo Nexus

## 1. Tạo project và đẩy code lên `main`
1. Tạo project trống `nexus` trong group của nhóm (private, không tạo README).
2. Giải nén zip, vào thư mục gốc:
```bash
git init -b main
git add .
git commit -m "chore: initial monorepo structure"
git remote add origin git@gitlab.com:<group>/nexus.git
git push -u origin main
git checkout -b develop && git push -u origin develop
```
Commit khởi tạo này là lần **duy nhất** được đẩy thẳng vào `main`. Sau đó bật bảo vệ nhánh (bước 3) và làm việc trên `develop`.

## 2. Phân quyền
Settings > Members: dev = **Developer**, tech lead = **Maintainer**, BA/QA = **Reporter**.

## 3. Bảo vệ nhánh và cấu hình MR
```bash
export GITLAB_TOKEN=glpat-xxxx            # Personal Access Token, scope: api
export GITLAB_URL=https://gitlab.com      # hoặc URL GitLab nội bộ
./scripts/setup-gitlab-project.sh <group>/nexus
```
Script: đặt `develop` làm default branch; bảo vệ `main` (chỉ Maintainer merge) và `develop` (Developer merge); bật squash,
xóa nhánh nguồn sau merge, chỉ merge khi pipeline xanh và thread đã resolved.

Làm **thủ công**:
- Approval tối thiểu 1 người: Settings > Merge requests > Merge request approvals (tùy gói GitLab; nếu không có thì đặt thành quy ước team).
- Default description template cho MR: chọn `default`.

## 4. Thêm một service mới
1. Copy thư mục `backend/user-service` thành `backend/<tên>-service`.
2. Đổi: `artifactId` và `<exclude>` JaCoCo trong `pom.xml`, package `com.nexus.<tên>` (IDE: Rename package), `SERVICE_NAME` / DB mặc định trong `application.yml`.
3. Thêm tên DB vào `deploy/postgres/init-databases.sh`, thêm service vào `docker-compose.yml` và route vào `backend/api-gateway/src/main/resources/application.yml`.
4. Copy khối job của một service trong `.ci/jobs.yml` và đổi tên.

## 5. Kiểm tra sau khi cài
1. Tạo nhánh `feature/NEX-1-test`, sửa nhỏ trong `backend/user-service`, mở MR vào `develop` với tiêu đề sai (`update stuff`): `mr-title-check` phải **fail**.
2. Đổi tiêu đề thành `chore(user): test pipeline`: chỉ các job `user-service:*` chạy, không có job của service khác.
3. Thử `git push origin develop` trực tiếp: phải bị từ chối.

## 6. Biến CI/CD
Container Registry dùng sẵn `CI_REGISTRY*`. Image đặt tên `<registry>/<project>/<service>:<sha|develop|tag>`.
Khi thêm deploy Kubernetes, đặt `KUBE_CONFIG` (Masked + Protected) ở cấp Group, không đặt trong repo.
