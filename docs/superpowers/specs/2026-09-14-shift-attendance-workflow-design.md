# Thiết kế quản lý ca, lịch tuần, đơn từ và cảnh báo

**Ngày:** 14/09/2026  
**Phạm vi:** Mở rộng app quản trị chấm công cho doanh nghiệp nhỏ, tập trung vào các mục 7–11.

## Mục tiêu

Bổ sung một lát nghiệp vụ hoàn chỉnh để admin có thể tạo ca, phân ca cho nhân viên, lập lịch theo tuần/tháng, theo dõi trạng thái có mặt, duyệt đơn từ, tính giờ làm/tăng ca và xem cảnh báo trong app. Luồng nhận diện vân tay, attendance realtime, thiết bị và phiếu lương hiện có phải tiếp tục hoạt động.

Các quyết định đã chốt:

- Chỉ admin được tạo/sửa ca và phân ca; nhân viên không tự phân ca trong app admin.
- Tuần dùng trong lịch và biểu đồ là tuần được chọn, bắt đầu từ thứ Hai và kết thúc Chủ nhật.
- Biểu đồ dashboard hiển thị dữ liệu của tuần đang chọn, mặc định là tuần hiện tại; không dùng cửa sổ 7 ngày trượt.
- Thông báo trong app là kênh bắt buộc ở lát này. Push notification/email chỉ là hướng mở rộng, không giả lập khi backend hiện tại chưa có Cloud Functions.

## Kiến trúc

Giữ MVVM hiện tại:

```text
Compose screens
    ↓ callbacks
MainViewModel + MainUiState
    ↓ suspend actions / Flow
FirebaseRepository
    ↓
Firestore: shifts, workSchedules, leaveRequests, notifications
```

Các quy tắc tính toán và phân loại nằm trong các file domain/model thuần, không gọi Firebase. Repository là nơi duy nhất đọc/ghi Firestore. UI chỉ nhận state và phát callback.

## Mô hình dữ liệu

### Ca làm — `shifts/{shiftId}`

```text
name: String
category: String                // MORNING, EVENING, SUPPLEMENTARY
startTime: String              // HH:mm
endTime: String                // HH:mm
allowEarlyMinutes: Int
lateGraceMinutes: Int
earlyLeaveAllowedMinutes: Int
breakStartTime: String?        // HH:mm
breakEndTime: String?          // HH:mm
countsOvertime: Boolean
effectiveFrom: String          // yyyy-MM-dd
effectiveTo: String?
active: Boolean
```

Phân loại ca trong MVP chỉ gồm ba loại: `Ca sáng`, `Ca tối` và `Ca bổ sung`. Admin chọn loại ca khi tạo/gán và có thể chỉnh tên, thời gian, thời gian nghỉ cũng như quy định đi trễ/về sớm. Ca qua nửa đêm được hỗ trợ bằng cách xem giờ kết thúc nhỏ hơn giờ bắt đầu là ngày kế tiếp.

### Lịch phân ca — `workSchedules/{scheduleId}`

Một lịch là một bản phân công cụ thể cho một ngày và một nhân viên:

```text
employeeId: String
employeeName: String
department: String
shiftId: String
shiftName: String
date: String                   // yyyy-MM-dd
overtimeHours: Int              // 0, 1, 2 hoặc 3 giờ
workedHoursOverride: Double?
adjustmentNote: String
assignedBy: String?
source: String                 // EMPLOYEE hoặc DEPARTMENT
note: String
```

Document id ổn định theo `${employeeId}_${date}` để đổi ca thủ công không tạo bản ghi trùng. Khi admin gán cho phòng ban, repository đọc nhân viên active trong phòng ban và tạo/cập nhật lịch từng người. Sao chép tuần trước tạo lịch cho cùng nhân viên ở tuần kế tiếp, giữ nguyên thứ trong tuần, và không ghi đè lịch đã có nếu admin chọn chế độ an toàn.

### Đơn từ — `leaveRequests/{requestId}`

```text
employeeId: String
employeeName: String
department: String
type: String                   // LEAVE, LATE, EARLY_LEAVE, REMOTE, ATTENDANCE_ADJUSTMENT, SHIFT_CHANGE
startDate: String              // yyyy-MM-dd
endDate: String                // yyyy-MM-dd
reason: String
attachmentUrl: String?
status: String                 // PENDING, APPROVED, REJECTED
reviewerId: String?
reviewerName: String?
reviewedAt: Timestamp?
reviewNote: String?
createdAt: Timestamp
```

Trong lát admin hiện tại, admin xem danh sách và duyệt/từ chối đơn. Tạo đơn từ phía nhân viên được giữ ở mức model/repository-ready, chưa tạo tài khoản nhân viên hay màn hình nhân viên riêng.

### Thông báo — `notifications/{notificationId}`

```text
type: String                    // REQUEST, LATE, MISSING_CHECK_OUT, ABSENT, ABNORMAL, DEVICE
title: String
body: String
referenceId: String?
createdAt: Timestamp
read: Boolean
```

Khi có đơn mới, repository tạo notification nội bộ cùng luồng ghi đơn. Cảnh báo attendance/device có thể được suy ra từ state hiện tại và hiển thị trên dashboard; không tạo lặp vô hạn mỗi lần snapshot realtime phát lại.

## Quy tắc nghiệp vụ

### Lịch tuần và biểu đồ

- `selectedWeekStart` luôn là ngày thứ Hai.
- Màn hình lịch cho phép chuyển tuần trước/sau và nút “Tuần này”.
- Hiển thị bảy cột từ thứ Hai đến Chủ nhật, mỗi ô gồm ca của nhân viên.
- Dashboard dùng cùng `selectedWeekStart` và tổng hợp đúng bảy ngày `[weekStart, weekStart + 6]`.
- Attendance ngoài tuần đang chọn không góp vào dữ liệu biểu đồ.

### Tính trạng thái có mặt

Với mỗi nhân viên active trong ngày đang xét, chọn event mới nhất của ngày:

- `PRESENT`: event mới nhất là CHECK_IN và có dữ liệu hợp lệ.
- `NOT_CHECKED_IN`: không có event trong ngày.
- `ON_LEAVE`: có đơn LEAVE được APPROVED bao phủ ngày.
- `LEFT`: event mới nhất là CHECK_OUT.
- `MISSING_CHECK_OUT`: có CHECK_IN sau cùng nhưng không có CHECK_OUT hợp lệ.
- `ABNORMAL`: event có `verified=false`, loại không hợp lệ hoặc dữ liệu xung đột.

Tóm tắt hiển thị số lượng theo từng nhóm; đơn nghỉ phép được ưu tiên trước khi kiểm tra event. `PRESENT` và `MISSING_CHECK_OUT` được tách riêng để admin biết ai đang có mặt nhưng chưa chấm ra.

### Ca, đi trễ, về sớm và tăng ca

- Cho phép chấm sớm: thời điểm vào hợp lệ từ `startTime - allowEarlyMinutes`.
- Đi trễ: thời điểm vào sau `startTime + lateGraceMinutes`.
- Về sớm: thời điểm ra trước `endTime - earlyLeaveAllowedMinutes`, chỉ tính khi ca có quy định.
- Giờ làm: `checkout - checkin - breakDuration`, không âm và làm tròn hai chữ số.
- Tăng ca là lựa chọn riêng trên từng lịch phân ca: `0`, `1`, `2` hoặc `3` giờ; mặc định là `0`.
- Chỉ tính tăng ca khi ca có `countsOvertime=true`; số giờ được tính là phần thời gian ngoài khoảng ca sau khi ghép cặp check-in/check-out, tối đa bằng `overtimeHours` admin đã chọn.
- Admin có thể điều chỉnh số giờ hoặc sự kiện thiếu bằng thao tác điều chỉnh; `workedHoursOverride` từ 0 đến 24 giờ bắt buộc có `adjustmentNote`. Bản điều chỉnh được lưu dưới dạng ghi chú/override trong lịch phân ca, không sửa/xóa attendance gốc.

### Duyệt đơn và cảnh báo

- Chỉ đơn `PENDING` mới có nút duyệt/từ chối.
- Từ chối bắt buộc nhập lý do.
- Duyệt/từ chối lưu người duyệt và thời gian xử lý.
- Dashboard cảnh báo các đơn pending, đi trễ, chưa chấm ra, vắng không phép, attendance bất thường, lệnh thiết bị FAILED và device offline/unknown.
- Cảnh báo thiết bị dựa trên snapshot heartbeat hiện có; không gửi lệnh LED/còi mới vì firmware hiện tại chưa hỗ trợ command test.

## Màn hình và luồng

Thêm các destination trong shell hiện tại:

1. `Ca làm`: danh sách ba loại `Ca sáng`, `Ca tối`, `Ca bổ sung`; tạo/sửa/kích hoạt, kiểm tra giờ và ngày áp dụng.
2. `Lịch`: chọn tuần/tháng, lọc phòng ban/nhân viên, gán ca cho nhân viên, gán phòng ban, đổi ca, sao chép tuần trước; lịch tháng dùng cùng dữ liệu `workSchedules` và hiển thị dạng lưới ngày.
3. `Có mặt`: chọn ngày, sáu nhóm trạng thái và danh sách nhân viên tương ứng.
4. `Đơn từ`: danh sách pending/approved/rejected, dialog xem chi tiết, duyệt hoặc từ chối kèm lý do.
5. `Tổng hợp`: tuần đang chọn, tổng giờ/ngày công/đi trễ/về sớm/tăng ca/nghỉ phép/vắng không phép; biểu đồ cột bảy ngày của tuần đó.

Dashboard hiện tại giữ các thẻ cũ, thay dữ liệu “7 ngày gần nhất” bằng tuần được chọn và thêm thẻ cảnh báo/đơn pending.

## Firestore Rules và dữ liệu cũ

- Admin được đọc/ghi `shifts`, `workSchedules`, `leaveRequests`, `notifications`.
- Anonymous device chỉ giữ quyền tạo attendance theo rules hiện tại; không được đọc/ghi lịch, ca hay đơn từ.
- Attendance và payroll cũ không bị xóa hoặc ghi đè.
- Dữ liệu thiếu lịch được phân loại là `NOT_CHECKED_IN`/chưa xác định, không tự đoán ca.

## Kiểm thử

Theo TDD, viết test domain trước implementation cho:

- Tuần bắt đầu thứ Hai và chỉ tổng hợp đúng bảy ngày của tuần được chọn.
- Phân loại sáu trạng thái có mặt, ưu tiên ngày nghỉ đã duyệt.
- Kiểm tra ba loại ca, ca qua nửa đêm, cho phép chấm sớm, đi trễ, về sớm, nghỉ trưa và tăng ca `1/2/3` giờ.
- Tính giờ/ngày công và giữ nguyên attendance gốc khi có override.
- Sao chép lịch theo đúng thứ, không ghi đè lịch có sẵn.
- Duyệt/từ chối đơn, bắt buộc lý do khi từ chối, và tạo thông báo đơn mới.

Kiểm tra tích hợp sau mỗi lát:

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

## Ngoài phạm vi lát này

- Tài khoản đăng nhập riêng cho nhân viên.
- Upload tệp thật lên Cloud Storage; model chỉ giữ `attachmentUrl`.
- Push notification/email/Zalo qua backend.
- Đồng bộ bảng công sang kế toán hoặc xuất CSV/XLSX.
