# Thiết kế báo cáo, audit, bảo mật và đồng bộ offline

**Ngày:** 14/09/2026  
**Phạm vi:** Bổ sung các mục 13–17 cho app quản trị chấm công.

## Mục tiêu

Mở rộng app hiện có để admin lọc/xuất báo cáo, truy vết thay đổi, quản lý quyền tài khoản, bảo vệ collection Firebase và không làm mất lượt chấm khi thiết bị mất mạng.

## Phạm vi đã chốt

- Báo cáo có thể lọc theo khoảng ngày, nhân viên và phòng ban; hỗ trợ báo cáo chấm công, giờ làm/tăng ca, đi trễ/về sớm, nghỉ phép, ngày công và hoạt động thiết bị. MVP xuất CSV và chia sẻ bằng Android Sharesheet.
- Audit log bất biến ghi actor, hành động, đối tượng, thời gian, lý do và chi tiết thay đổi. Các thao tác admin quan trọng ghi log trước khi hoàn tất ghi nghiệp vụ.
- Tài khoản Firebase Email/Password tiếp tục là luồng đăng nhập hiện tại; bổ sung quên mật khẩu, đổi mật khẩu và profile `users/{uid}` với `role`/`active`. Tài khoản anonymous chỉ dành cho thiết bị.
- Firestore Rules cho phép dữ liệu nghiệp vụ và audit chỉ do admin truy cập; attendance/payroll không cho update/delete; user profile không cho tự nâng quyền.
- Firmware ESP8266 có outbox LittleFS dạng JSON Lines. Khi mất mạng, event được lưu với `eventId`; khi có mạng, gửi lại cùng document id để retry idempotent và xóa khỏi hàng đợi sau khi Firebase xác nhận.

## Kiến trúc

```text
ReportRules / AuditRules / OfflineQueueRules (pure Kotlin/C++)
              ↓
MainViewModel + FirebaseRepository + ReportExporter
              ↓
Compose report/audit/auth UI       Firestore Rules
              ↓                              ↓
Firestore: reports source, audit_logs, users, departments, settings

AS608 → ESP8266 LittleFS outbox → Firebase attendance/{eventId}
```

`ReportRules` chỉ nhận dữ liệu đã quan sát và trả các dòng báo cáo; `ReportExporter` chỉ định dạng CSV, không tự đọc Firebase. `FirebaseRepository` là nơi duy nhất ghi audit và nghiệp vụ. Firmware giữ event gốc trong outbox, không thay đổi attendance đã ghi.

## Mô hình dữ liệu

### `users/{uid}`

```text
uid: String
email: String
displayName: String
role: String       // ADMIN hoặc EMPLOYEE
active: Boolean
updatedAt: Timestamp
```

### `audit_logs/{logId}`

```text
actorId: String
actorName: String
action: String     // LOGIN, EMPLOYEE_CREATE, EMPLOYEE_UPDATE, FINGERPRINT_DELETE,
                   // ATTENDANCE_ADJUST, LEAVE_REVIEW, SHIFT_UPDATE, DEVICE_CONFIG_UPDATE
targetType: String
targetId: String
reason: String
details: String
createdAt: Timestamp
```

### Báo cáo

`ReportFilter(startDate, endDate, employeeId?, department?)` chuẩn hóa ngày ISO và yêu cầu start không sau end. `AttendanceReportRow` giữ ngày, nhân viên, phòng ban, vào/ra, trạng thái, giờ làm, tăng ca; `DeviceActivityRow` giữ device, trạng thái, heartbeat, firmware, dung lượng và lệnh lỗi. Dữ liệu xuất CSV dùng UTF-8 BOM, escape dấu phẩy/nháy/ký tự xuống dòng.

## Quy tắc nghiệp vụ

- Mọi báo cáo dùng khoảng ngày đóng `[startDate, endDate]`; báo cáo tuần vẫn dùng Monday–Sunday đã chọn, báo cáo tháng dùng ngày đầu/cuối tháng.
- Lọc nhân viên/phòng ban áp dụng đồng thời cho attendance, lịch, đơn từ và tổng hợp.
- Dòng attendance ghép CHECK_IN/CHECK_OUT theo nhân viên/ngày; giờ làm lấy domain ca hiện có, không sửa event gốc. Nếu không đủ cặp thì vẫn xuất dòng với trạng thái tương ứng.
- Audit review đơn chỉ ghi khi request còn `PENDING`; update/delete audit bị cấm trong Rules.
- `isAdmin()` yêu cầu user Email/Password, profile `active != false` và nếu profile tồn tại thì `role == ADMIN`. Tài khoản email chưa có profile vẫn được phép bootstrap phần prototype hiện tại; profile `EMPLOYEE` không được truy cập màn admin.
- Outbox dùng cùng event id cho mọi retry. Firebase/Firestore là nguồn xác nhận; HTTP 2xx thì xóa dòng, lỗi mạng giữ lại, hàng đợi đầy thì bỏ event cũ nhất và ghi cảnh báo serial.

## Màn hình/luồng

1. `Báo cáo`: chọn loại báo cáo, ngày bắt đầu/kết thúc, nhân viên/phòng ban, xem bảng và bấm `Xuất CSV`/`Chia sẻ`.
2. `Nhật ký`: danh sách audit mới nhất, lọc hành động/đối tượng; không có nút sửa/xóa.
3. Đăng nhập: `Quên mật khẩu`; khi đã đăng nhập có `Đổi mật khẩu` và đăng xuất.
4. Dashboard giữ cảnh báo tuần, bổ sung nút mở báo cáo; trạng thái thiết bị lấy heartbeat/lệnh hiện có.

## Kiểm thử

- Unit test `ReportRulesTest`, `AuditRulesTest`, `OfflineQueueRulesTest` trước implementation; kiểm tra khoảng ngày, filter đồng thời, CSV escaping, audit immutable và outbox retry/deduplicate.
- Chạy full `:app:testDebugUnitTest` và `:app:assembleDebug`.
- Kiểm tra firmware bằng compile nếu Arduino CLI có sẵn; nếu không, ghi rõ chưa compile phần cứng.

## Ngoài phạm vi

- Tự động gửi email/Zalo và tự tạo file cuối tháng bằng Cloud Scheduler chưa bật trong Firebase hiện tại; MVP có nút xuất báo cáo cuối tháng.
- Tài khoản employee UI riêng, upload attachment lên Cloud Storage và chứng chỉ CA thật cho firmware cần cấu hình triển khai riêng.
