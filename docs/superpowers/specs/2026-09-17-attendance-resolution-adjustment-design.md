# Thiết kế resolve chấm công theo lịch ca và điều chỉnh công

**Ngày:** 17/09/2026  
**Phạm vi:** Firmware ESP8266, Firebase Cloud Functions, Android domain/repository/UI và Firestore rules.

## Mục tiêu

Thay thế quyết định `CHECK_IN`/`CHECK_OUT` dựa trên mốc 12 giờ bằng cơ chế dựa trên lịch làm và lượt chấm gần nhất. Thiết kế phải:

- hỗ trợ ca qua ngày;
- chống quét trùng trong một khoảng thời gian ngắn;
- phát hiện phiên có vào nhưng quên ra;
- giữ nguyên dữ liệu quét gốc;
- cho phép Admin điều chỉnh công qua lớp dữ liệu riêng, bắt buộc có lý do và audit;
- dùng cùng một quy tắc cho realtime, báo cáo, bảng lương và màn hình nhân viên;
- tương thích với các bản ghi `attendance` cũ đã có `CHECK_IN`/`CHECK_OUT`.

## Quyết định kiến trúc

Cloud Function là nguồn quyết định trung tâm. Firmware chỉ gửi lượt quét thô và không còn tự suy đoán vào/ra theo giờ địa phương. Vì thiết bị hiện tại ghi Firestore bằng anonymous Firebase Auth, firmware tiếp tục ghi document `attendance` với `type=SCAN`; Cloud Function trigger sẽ resolve document đó bằng transaction và cập nhật kết quả.

Android không tự resolve lại thành một kết quả khác. Android chỉ tái sử dụng các event đã được resolve để tính công, đồng thời áp dụng adjustment mới nhất nếu có. Các hàm domain thuần vẫn là nơi kiểm thử cách ghép cặp, tính ca qua đêm và trạng thái thiếu chấm ra.

## Mô hình dữ liệu

### Attendance raw và resolved — `attendance/{eventId}`

Các field hiện có tiếp tục được giữ để tương thích:

```text
employeeId: String
employeeName: String
deviceId: String
templateId: Int
confidence: Int
timestamp: Timestamp       // thời điểm quét từ thiết bị, không phải thời điểm đồng bộ
receivedAt: Timestamp?     // thời điểm server nhận/resolve
type: String               // SCAN, CHECK_IN, CHECK_OUT, DUPLICATE, UNSCHEDULED
status: String             // NORMAL, LATE, EARLY_LEAVE, ABNORMAL, PENDING
resolutionStatus: String   // PENDING, ACCEPTED, DUPLICATE, UNSCHEDULED, OUT_OF_ORDER
scheduleDate: String?      // yyyy-MM-dd, ngày bắt đầu ca
shiftId: String?
resolvedAt: Timestamp?
verified: Boolean
syncStatus: String?
```

`attendance` là append-only đối với client và thiết bị. Cloud Function được phép cập nhật các field resolve của chính document raw. Bản ghi `DUPLICATE`, `UNSCHEDULED` và `OUT_OF_ORDER` vẫn được giữ để truy vết nhưng không tham gia tính công.

Bản ghi cũ thiếu `resolutionStatus` được coi là event hợp lệ nếu `type` là `CHECK_IN` hoặc `CHECK_OUT`; các loại khác được coi là bất thường.

### Trạng thái phiên — `attendanceSessions/{employeeId}_{scheduleDate}`

Document trạng thái do Cloud Function quản lý để serialize các lượt quét của cùng nhân viên và lịch:

```text
employeeId: String
scheduleDate: String
shiftId: String
lastAcceptedEventId: String?
lastAcceptedType: String?     // CHECK_IN hoặc CHECK_OUT
lastAcceptedAt: Timestamp?
openCheckInAt: Timestamp?
closed: Boolean
updatedAt: Timestamp
```

Client không được đọc/ghi collection này trong MVP; Cloud Function dùng transaction để chống race khi nhiều lượt quét đến gần nhau.

### Điều chỉnh công — `attendanceAdjustments/{id}`

Đây là collection append-only, chỉ Admin được tạo; không sửa/xóa:

```text
employeeId: String
employeeName: String
scheduleDate: String        // yyyy-MM-dd
checkInAt: Timestamp?
checkOutAt: Timestamp?
workedHoursOverride: Double?
reason: String              // bắt buộc, trim không rỗng
actorId: String
actorName: String
createdAt: Timestamp
```

Một adjustment có thể sửa giờ vào, giờ ra, số giờ công, hoặc kết hợp các trường này. Khi có nhiều adjustment cho cùng nhân viên/ngày, bản ghi mới nhất theo `createdAt` được áp dụng. Dữ liệu `attendance` gốc không bị sửa hoặc xóa.

### Audit — `audit_logs/{id}`

Thao tác điều chỉnh dùng `action=ATTENDANCE_ADJUST`, `targetType=attendanceAdjustment`, `targetId` là id adjustment, `reason` là lý do bắt buộc và `details` chứa giá trị trước/sau hoặc mô tả các trường đã thay đổi. Audit cũng là append-only.

## Luồng resolve Cloud Function

1. Thiết bị tạo document với `type=SCAN`, `resolutionStatus=PENDING`, `timestamp` lấy từ NTP và `eventId` ổn định trong outbox.
2. Trigger đọc employee đang active theo mapping/template. Nếu document đã được xử lý, không xử lý lại.
3. Từ `timestamp` và timezone `Asia/Ho_Chi_Minh`, Function đọc schedule của ngày local và ngày trước đó. Mỗi schedule được chuyển thành một khoảng tuyệt đối:
   - end lớn hơn start: kết thúc cùng ngày;
   - end nhỏ hơn hoặc bằng start: kết thúc ngày kế tiếp.
4. Chọn schedule có khoảng thời gian gần nhất trong cửa sổ `[start - allowEarlyMinutes, end + missingCheckOutGraceMinutes]`. Không có schedule phù hợp thì đánh dấu `UNSCHEDULED`.
5. Trong transaction, đọc session và event hợp lệ gần nhất:
   - khoảng cách tuyệt đối không quá 3 phút: `DUPLICATE`;
   - session đang mở bằng `CHECK_IN` và timestamp mới lớn hơn lượt vào: nhận `CHECK_OUT`;
   - session đã đóng: chỉ nhận lượt nếu nó thuộc một schedule kế tiếp; nếu không thì `UNSCHEDULED`;
   - session chưa có lượt: chọn loại gần mốc bắt đầu/kết thúc ca. Gần mốc bắt đầu là `CHECK_IN`, gần mốc kết thúc là `CHECK_OUT` để không che giấu trường hợp quên chấm vào.
6. Cập nhật document raw với type, resolution status, schedule date, shift id, status và resolvedAt; đồng thời cập nhật session. Nếu cùng eventId được retry, trả lại kết quả cũ thay vì tạo bản ghi thứ hai.
7. Nếu timestamp mới cũ hơn trạng thái đã chốt ngoài phạm vi chống trùng, đánh dấu `OUT_OF_ORDER`; Admin có thể xử lý bằng adjustment.

Ngưỡng chống trùng 3 phút là hằng số nghiệp vụ của lát này. Khoảng đệm phát hiện quên chấm ra là field cấu hình của ca, mặc định 60 phút; cần bổ sung `missingCheckOutGraceMinutes` vào `WorkShift` và Firestore validation.

## Quy tắc domain Android

Các hàm domain mới hoặc được mở rộng:

- ghép attendance theo `scheduleDate` và session, không theo ngày local đơn thuần;
- tính ca qua đêm bằng `shiftDate.atTime(start)` và end ở ngày kế tiếp khi cần;
- lọc `DUPLICATE`, `UNSCHEDULED`, `OUT_OF_ORDER` khỏi cặp công;
- báo `MISSING_CHECK_OUT` khi có `CHECK_IN` chưa đóng và thời điểm hiện tại vượt end ca + grace; với ngày đã qua, trạng thái vẫn thiếu ra;
- ưu tiên đơn nghỉ phép đã duyệt trước khi xét thiếu chấm;
- áp dụng adjustment mới nhất trước khi tính `workedHours`, `lateMinutes`, `earlyLeaveMinutes` và overtime;
- giữ nguyên các hàm tính lương/report hiện tại nhưng chuyển chúng sang nguồn cặp đã resolve chung.

Không dùng lại mốc 12 giờ ở firmware, Cloud Function, presence, employee summary, report hay payroll.

## Luồng Admin điều chỉnh

Attendance screen hiển thị trạng thái resolve và cho Admin mở dialog điều chỉnh theo nhân viên/ngày ca. Dialog cho phép nhập giờ vào, giờ ra và/hoặc số giờ công; form phải chặn submit khi lý do trống hoặc dữ liệu thời gian không hợp lệ.

Repository ghi adjustment và audit trong cùng một batch/transaction với server timestamp. Sau khi ghi thành công, realtime listener cập nhật summary, report và bảng lương; không sửa document attendance gốc. Audit screen hiển thị action, actor, target, reason và details trước/sau.

## Firestore rules

- Device được tạo `attendance` raw với `type=SCAN`, `resolutionStatus=PENDING`, mapping fingerprint active và timestamp hợp lệ; không được tự tạo event đã resolve.
- Client không được update/delete attendance.
- Cloud Function dùng Admin SDK để resolve và cập nhật event/session.
- Admin được đọc/tạo `attendanceAdjustments`; employee chỉ được đọc adjustment của chính mình nếu UI nhân viên cần hiển thị kết quả đã điều chỉnh.
- `attendanceAdjustments` không cho update/delete.
- Admin được đọc audit; client không update/delete audit.
- Chỉ Admin được ghi `missingCheckOutGraceMinutes` trong shifts.

## Tương thích và chuyển đổi

- Không migrate/xóa attendance cũ. Bộ đọc coi `CHECK_IN`/`CHECK_OUT` cũ là accepted legacy event.
- Firmware thay đổi payload từ type theo 12 giờ sang `SCAN`; outbox vẫn dùng `eventId` ổn định và tiếp tục retry khi mất mạng.
- Thêm index cho truy vấn attendance theo employee/timestamp, workSchedules theo date và adjustments theo employee/scheduleDate/createdAt nếu Firestore yêu cầu.
- Rollout theo thứ tự: domain/tests → rules/model/repository → Cloud Function → firmware → UI. Trong lúc Cloud Function chưa deploy, raw `SCAN` phải hiển thị rõ là đang chờ resolve, không tính công.

## Kiểm thử và tiêu chí nghiệm thu

### Kotlin unit tests

- Resolve/pair ca `22:00–06:00` với lượt `23:00` và `05:30`.
- Ngày công của ca qua đêm gắn với ngày bắt đầu ca.
- Trạng thái thiếu ra dựa trên end ca + grace, không dựa mốc 12 giờ.
- Lượt duplicate trong 3 phút không làm tăng giờ công.
- Event `UNSCHEDULED`/`OUT_OF_ORDER` không tham gia pair.
- Adjustment mới nhất được áp dụng, attendance raw không bị thay đổi.
- Adjustment thiếu reason, thời gian sai hoặc worked hours ngoài `0..24` bị từ chối.
- Báo cáo, payroll và employee summary dùng cùng kết quả pair.

### Cloud Function tests

Dùng Node built-in test runner hoặc test harness hiện có để kiểm tra:

- idempotency theo eventId;
- duplicate window;
- ca qua ngày và lookup schedule ngày trước;
- chuyển trạng thái phiên vào/ra;
- không có lịch;
- retry/out-of-order;
- transaction không tạo hai lượt accepted khi quét đồng thời.

### Build/verification

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
node --test
```

## Ngoài phạm vi

- Tự động tạo lịch ca khi chưa được phân lịch.
- Nhiều phiên làm việc độc lập trong cùng một `WorkSchedule`.
- Sửa hoặc xóa attendance raw.
- Push notification mới ngoài cơ chế notification hiện có.
- Đồng bộ sang hệ thống kế toán bên ngoài.
