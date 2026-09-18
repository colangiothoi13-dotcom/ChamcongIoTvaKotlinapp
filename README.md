# Chấm công IoT

MVP quản trị nhân sự và chấm công bằng vân tay gồm:

- Android: Kotlin, Jetpack Compose, Firebase Auth, Firestore, FCM và WorkManager.
- Thiết bị: ESP8266 + cảm biến AS608/R307, LED xanh/đỏ và buzzer.
- Backend: Firebase Anonymous Auth + Firestore REST dành cho ESP8266; Cloud Functions phân giải chấm công theo lịch ca.

## Tổng quan hệ thống

Đây là hệ thống quản lý nhân sự và chấm công bằng vân tay. Admin sử dụng ứng dụng
Android để quản lý nhân viên, ca làm, lịch tuần, đơn từ, lương, KPI, báo cáo và
nhật ký. Nhân viên dùng cùng ứng dụng để xem lịch, xem công, gửi đơn và đăng ký
tăng ca. Thiết bị ESP8266 đặt tại nơi làm việc nhận dạng vân tay tại chỗ rồi gửi
lượt quét lên Firebase để hệ thống xử lý.

### Các thành phần chính

| Thành phần | Vai trò |
| --- | --- |
| Ứng dụng Android | Giao diện Admin/Nhân viên, nhập dữ liệu, hiển thị realtime và gọi các nghiệp vụ. |
| ESP8266 + AS608/R307 | Đọc và đối chiếu vân tay cục bộ, điều khiển LED/còi, gửi lượt quét và trạng thái thiết bị. |
| Firebase Authentication | Đăng nhập tài khoản Admin/Nhân viên; thiết bị dùng Anonymous Auth. |
| Firestore | Lưu nhân viên, ca, lịch, lượt chấm, đơn từ, lương, thiết bị và audit log. |
| Cloud Functions | Phân giải lượt quét theo lịch ca, xử lý ca qua đêm, tăng ca và thông báo. |
| Firestore Rules | Kiểm soát quyền đọc/ghi theo vai trò, bảo vệ dữ liệu chấm công và audit. |

Hệ thống không lưu ảnh hoặc đặc trưng vân tay trên Firestore. Mẫu vân tay được
giữ trong bộ nhớ cảm biến; Firebase chỉ lưu mã mẫu và thông tin liên kết cần thiết.

### Luồng hoạt động tổng quát

```text
Admin tạo nhân viên
        ↓
Đăng ký mẫu vân tay trên ESP8266
        ↓
Admin tạo ca và phân lịch tuần trước 17:00 Chủ nhật
        ↓
Nhân viên quét vân tay vào/ra
        ↓
ESP8266 gửi SCAN/PENDING lên Firestore
        ↓
Cloud Functions đối chiếu lịch, ca và lượt gần nhất
        ↓
CHECK_IN / CHECK_OUT / quét trùng / ngoài lịch / sai thứ tự
        ↓
Android cập nhật realtime hiện diện, giờ công, báo cáo và lương
```

### Luồng nghiệp vụ theo thời gian

1. **Chuẩn bị:** Admin thêm nhân viên, thiết lập lương, đăng ký vân tay và tạo ca.
2. **Phân lịch:** Admin vào `Phân ca → Lịch`, tích chọn nhân viên/ngày, chọn ca sáng
   hoặc ca chiều và lưu trước 17:00 Chủ nhật.
3. **Chấm công:** Nhân viên quét vân tay. Hệ thống xác định vào/ra dựa trên cửa sổ
   ca và lượt hợp lệ gần nhất, không còn dựa vào mốc 12 giờ.
4. **Tăng ca:** Nhân viên gửi đơn cho khung cố định 17:30–20:30. Admin duyệt hoặc
   từ chối; đơn chờ duyệt vẫn được giữ lượt quét nhưng chưa tính tiền tăng ca.
5. **Theo dõi:** Admin xem Tổng quan, Chấm công, Có mặt, Thiết bị và xử lý cảnh báo.
6. **Cuối kỳ:** Hệ thống tính giờ, tiền tăng ca, thưởng KPI và phạt đi muộn; Admin
   kiểm tra rồi lưu phiếu lương, xuất báo cáo và tra cứu Nhật ký.

### Vai trò người dùng

| Vai trò | Có thể thực hiện |
| --- | --- |
| Admin | Quản lý nhân viên/thiết bị/ca/lịch, duyệt đơn, điều chỉnh công có lý do, lập lương, xem báo cáo và audit. |
| Nhân viên | Xem lịch và công của mình, gửi đơn thường, gửi đăng ký tăng ca, đổi mật khẩu và đăng xuất. |
| Thiết bị | Gửi snapshot/tín hiệu, nhận lệnh vân tay và tạo lượt quét; không được tự sửa lịch sử chấm công. |

## Chức năng các nút bấm

Tên dưới đây là tên hiển thị trong ứng dụng. Các thẻ thống kê, dòng trong `Tác vụ`
và ô giao giữa nhân viên/ngày trên lịch cũng là vùng có thể bấm.

### Điều hướng chung

| Nút | Chức năng |
| --- | --- |
| `Đăng nhập` | Xác thực Email/Password và mở giao diện theo vai trò. |
| `Quên mật khẩu` | Gửi email đặt lại mật khẩu. |
| `Đổi mật khẩu` → `Lưu` | Đổi mật khẩu hiện tại nếu đủ 6 ký tự và nhập lại trùng nhau. |
| `Đăng xuất` | Kết thúc phiên và quay về màn hình đăng nhập. |
| `Tổng quan` | Xem thống kê tuần, thiết bị, cảnh báo, thông báo và lượt chấm mới. |
| `Tác vụ` | Mở danh mục nhanh: Chấm công, Thiết bị, Có mặt, Ca làm, Lịch, Lương, Hiệu suất, Báo cáo, Nhật ký, Cài đặt. |
| `Đơn từ` | Admin duyệt đơn; Nhân viên tạo và xem đơn của mình. |
| `Phân ca` | Mở Quản lý ca làm hoặc Lịch phân ca. |
| `Nhân viên` | Thêm, tìm kiếm, lọc, thiết lập lương và quản lý vân tay. |

### Nút nghiệp vụ Admin

| Màn hình | Nút/vùng thao tác | Chức năng |
| --- | --- | --- |
| Tổng quan | `‹`, `›`, `Tuần này` | Chuyển tuần trước, tuần sau hoặc quay về tuần hiện tại. |
| Tổng quan | `Đã đọc` | Đánh dấu thông báo đã xem. |
| Tổng quan | `Xem tất cả` | Mở danh sách lịch sử chấm công. |
| Tác vụ | Dòng/card chức năng | Mở đúng màn hình nghiệp vụ được chọn. |
| Nhân viên | `Thêm` / `+` | Mở form thêm nhân viên. |
| Nhân viên | Ô tìm kiếm, chip phòng ban | Lọc theo tên/mã hoặc phòng ban. |
| Nhân viên | `Hiện nhân viên đã nghỉ` | Hiện/ẩn hồ sơ có `active = false`. |
| Nhân viên | `Thiết lập lương` | Nhập đơn giá lương cơ bản theo giờ. |
| Nhân viên | `Đăng ký vân tay` → `Gửi lệnh` | Gửi lệnh đến thiết bị để quét cùng một ngón tay hai lần. |
| Nhân viên | `Xóa vân tay` → `Xác nhận xóa` | Yêu cầu thiết bị xóa mẫu, giữ lại hồ sơ và lịch sử. |
| Nhân viên | `Xóa nhân viên` → `Xác nhận xóa` | Chuyển nhân viên sang trạng thái đã nghỉ, không xóa lịch sử. |
| Chấm công | Chip trạng thái/loại lượt | Lọc đúng giờ, đi trễ, về sớm, vào ca hoặc ra ca. |
| Chấm công | `Điều chỉnh` → `Lưu điều chỉnh` | Sửa giờ/công có lý do; tạo bản ghi điều chỉnh và audit mới. |
| Đơn từ | `Duyệt` | Duyệt đơn thường hoặc đơn tăng ca. |
| Đơn từ | `Từ chối` → nhập lý do → `Từ chối` | Từ chối đơn; lý do bắt buộc và được ghi audit. |
| Ca làm | `Thêm ca` | Tạo ca sáng, ca chiều, ca bổ sung hoặc ca tùy chỉnh. |
| Ca làm | `Chỉnh sửa` / `Tạo bản tùy chỉnh` | Sửa ca hiện tại hoặc tạo snapshot mới mà không ảnh hưởng lịch sử đã phân. |
| Ca làm | `Lưu ca` / `Hủy` | Lưu cấu hình ca hoặc đóng form không lưu. |
| Lịch | `Tuần trước`, `Tuần sau`, `Tuần này` | Chuyển tuần đang xem. |
| Lịch | `Phân cho nhân viên` | Tích chọn nhiều nhân viên/ngày, chọn ca sáng hoặc ca chiều, rồi lưu hàng loạt. |
| Lịch | `Phân cho phòng ban` | Gán ca cho các nhân viên thuộc phòng ban ở ngày chọn. |
| Lịch | `Sao chép tuần trước` | Sao chép lịch tuần trước sang tuần hiện tại. |
| Lịch | `Lưu phân ca` | Ghi lịch theo transaction; mỗi nhân viên/ngày chỉ có một lịch. |
| Thiết bị | `Sửa cấu hình` → `Lưu` | Sửa tên và vị trí; tín hiệu/phiên bản phần mềm do thiết bị cập nhật. |
| Lương | `Lập phiếu lương / thiết lập lương` | Chọn nhân viên để đặt lương hoặc lập phiếu theo tháng. |
| Lương | `Đặt lương` | Lưu đơn giá lương cơ bản theo giờ. |
| Lương | `Lập phiếu` → `Lưu phiếu` | Kiểm tra giờ, thưởng, khấu trừ rồi lưu snapshot lương. |
| Hiệu suất | Ô `Tháng hiệu suất` | Chọn tháng để xem ca tăng ca, đi muộn, Top 3 và thưởng. |
| Báo cáo | `Tuần đang chọn`, `Tháng này` | Chọn nhanh khoảng ngày. |
| Báo cáo | Ô ngày/mã nhân viên/phòng ban | Lọc phạm vi dữ liệu. |
| Báo cáo | Chip loại báo cáo | Chọn Chấm công, Ngày công, Trễ/sớm, Nghỉ phép, Tăng ca hoặc Thiết bị. |
| Báo cáo | `Xuất CSV và chia sẻ` | Tạo CSV theo bộ lọc và mở chức năng chia sẻ của Android. |
| Nhật ký | Ô `Lọc hành động/đối tượng` | Tìm audit log; nhật ký chỉ đọc, không có nút sửa/xóa. |

### Nút nghiệp vụ Nhân viên

| Màn hình | Nút/vùng thao tác | Chức năng |
| --- | --- | --- |
| Trang chủ | Các thẻ thống kê | Xem ca hôm nay, giờ làm, số ngày công và số lần đi trễ. |
| Chấm công của tôi | Mũi tên tháng trước/sau | Xem dữ liệu chấm công theo tháng. |
| Chấm công của tôi | `Gửi yêu cầu điều chỉnh` | Mở khu vực Đơn từ để gửi đơn sửa công. |
| Đơn từ | `Tạo đơn` / `Đóng form` | Mở hoặc đóng form tạo đơn. |
| Đơn từ | Chip loại đơn | Chọn nghỉ phép, đi muộn, về sớm, sửa công, ngoài văn phòng hoặc đổi ca. |
| Đơn từ | `Gửi đơn` | Gửi đơn thường kèm ngày và lý do. |
| Đơn từ | `Gửi đăng ký tăng ca` | Gửi đơn tăng ca cho hôm nay/tương lai trong khung 17:30–20:30. |
| Cá nhân | `Đổi mật khẩu` | Đổi mật khẩu tài khoản nhân viên. |
| Cá nhân | `Đăng xuất` | Kết thúc phiên nhân viên. |

Các nút lưu, duyệt, từ chối, gửi lệnh và xóa sẽ bị khóa trong lúc đang xử lý.
Nút `Hủy`, mũi tên chuyển ngày/tuần/tháng và chip lọc chỉ thay đổi giao diện,
không tự ghi dữ liệu nghiệp vụ.

## Cấu trúc file và trách nhiệm từng phần

```text
ChamcongIoTvaKotlinapp-main/
├─ app/src/main/java/vn/chamcong/iot/
│  ├─ data/          Kết nối Firebase Auth/Firestore, đọc ghi và audit.
│  ├─ domain/        Luật chấm công, phân ca, tăng ca, KPI, hiện diện, báo cáo.
│  ├─ model/         Model dữ liệu nhân viên, ca, lịch, attendance, lương, thiết bị.
│  ├─ ui/             Màn hình Compose, điều hướng và MainViewModel.
│  │  ├─ admin/       Tác vụ Admin và hub Phân ca.
│  │  ├─ attendance/  Lịch sử chấm công và điều chỉnh công.
│  │  ├─ employee/    Giao diện Nhân viên.
│  │  ├─ overtime/    Đăng ký và duyệt tăng ca.
│  │  ├─ schedule/    Lịch tuần/tháng và form phân ca.
│  │  ├─ shifts/      Tạo, sửa và hiển thị ca làm.
│  │  └─ reports/     Báo cáo và xuất CSV.
│  └─ work/           WorkManager đồng bộ receipt/offline.
├─ app/src/test/      Unit test cho luật nghiệp vụ và phần trình bày UI.
├─ firebase/
│  ├─ firestore.rules Quyền đọc/ghi và ràng buộc dữ liệu Firebase.
│  ├─ firestore.indexes.json
│  └─ functions/     Cloud Functions phân giải chấm công, tăng ca, thông báo.
├─ firmware/esp8266_fingerprint/
│  └─ esp8266_fingerprint.ino  Firmware đọc vân tay, gửi scan và tín hiệu thiết bị.
├─ docs/              Spec, kế hoạch và báo cáo kỹ thuật.
├─ gradle/             Cấu hình Gradle/Android build.
├─ README.md           Tài liệu tổng quan, luồng vận hành và hướng dẫn nút bấm.
└─ app/google-services.json  Đặt cục bộ khi cấu hình Firebase Android; không đưa thông tin nhạy cảm mới lên Git.
```

### Một số file quan trọng

| File | Trách nhiệm |
| --- | --- |
| `app/.../ui/ChamCongApp.kt` | Điều hướng, shell Admin/Nhân viên, đăng nhập, menu cá nhân và các hộp thoại chung. |
| `app/.../ui/MainViewModel.kt` | Giữ trạng thái màn hình, đăng ký listener realtime và gọi thao tác nghiệp vụ. |
| `app/.../data/FirebaseRepository.kt` | Điểm truy cập Firebase duy nhất; đọc/ghi dữ liệu và tạo audit. |
| `app/.../domain/AttendanceResolutionRules.kt` | Luật chọn ca, vào/ra, quét trùng, ngoài lịch và sai thứ tự. |
| `app/.../domain/WeeklyScheduling.kt` | Luật chọn nhân viên/ngày, hạn Chủ nhật và payload phân ca hàng loạt. |
| `app/.../domain/OvertimeRules.kt` | Luật đơn tăng ca 17:30–20:30 và duyệt/từ chối. |
| `app/.../domain/KpiBonusRules.kt` | Tính thưởng theo ca, Top 3 và phạt đi muộn. |
| `app/.../ui/schedule/WeeklyAssignmentDialog.kt` | Giao diện checkbox phân nhiều nhân viên/ngày. |
| `app/.../ui/attendance/AttendanceAdjustmentDialog.kt` | Form sửa công bắt buộc lý do. |
| `app/.../ui/overtime/OvertimeRequestScreen.kt` | Form nhân viên gửi và Admin duyệt đơn tăng ca. |
| `firebase/functions/attendanceResolver.js` | Cloud Function phân giải raw scan trên server. |
| `firebase/firestore.rules` | Bảo vệ quyền và tính bất biến của attendance/audit/payroll. |

## Kiến trúc

### Đơn tăng ca cố định, phân giải chấm công và KPI (Task 6)

Luồng tăng ca dùng collection `overtimeRequests` với document ID xác định:
`overtimeRequests/{employeeId}_{workDate}`. Schema gồm `employeeId`,
`employeeName`, `department`, `workDate`, `startTime`, `endTime`, `status`,
`createdAt`, `reviewerId`, `reviewerName`, `reviewedAt` và
`rejectionReason`. `startTime`/`endTime` luôn là `17:30`/`20:30` theo
`Asia/Ho_Chi_Minh`, tương đương đúng **3 giờ**; request có trạng thái
`PENDING`, `APPROVED` hoặc `REJECTED`.

- Nhân viên gửi tối đa một request cho mỗi nhân viên/ngày; request được audit
  khi Admin duyệt hoặc từ chối. Review ghi request và
  `audit_logs/{employeeId}_{workDate}_OVERTIME_REVIEW` trong cùng transaction;
  từ chối bắt buộc có `rejectionReason`, còn xóa request bị cấm.
- Khi request `PENDING`, các lượt quét trong khung vẫn được giữ nguyên raw,
  vẫn có thể check-in/check-out, mang `overtimeRequestId` và trạng thái
  `OVERTIME_PENDING`; chúng không được tính là tăng ca trả lương.
- Khi request `APPROVED`, Cloud Function đọc lại các lượt quét gắn request,
  sắp theo timestamp và resolve tuần tự vào session tăng ca độc lập
  `..._SUPPLEMENTARY_1730_2030`; chỉ cặp `CHECK_IN`/`CHECK_OUT` hợp lệ mới
  được tính là một ca hoàn thành.
- Khi request `REJECTED`, raw scans vẫn được giữ để audit/tra cứu, được đánh
  dấu `OVERTIME_REJECTED` và không tạo giờ hoặc tiền tăng ca phải trả.
- Lịch tuần do Admin phân chỉ mô tả ca chính. Tăng ca là request của nhân
  viên, không được seed trước vào `workSchedules`; việc duyệt và thay đổi
  trạng thái được ghi audit.

KPI tháng dùng dữ liệu attendance đã resolve và request đã duyệt: mỗi ca tăng
  ca hoàn thành cộng **50.000 VND**, Top 3 nhân viên đủ điều kiện (không đi
  muộn) nhận **500.000 VND/người**, mỗi lần đi muộn ca chính trừ
  **100.000 VND**. Tổng bonus bị chặn sàn ở 0; `deduction` trên phiếu payroll
  vẫn là khoản Admin nhập riêng, không bị công thức KPI tự động thay đổi.
  Payroll cộng thêm 3 giờ cho mỗi ca tăng ca hoàn thành và vẫn lưu snapshot
  khi phiếu được tạo.

Query request của Admin đọc collection rồi sort `createdAt` ở local; query của
nhân viên chỉ dùng `whereEqualTo("employeeId", employeeId)` rồi sort local.
Resolver tra request theo document ID và re-resolve attendance bằng
`whereEqualTo("overtimeRequestId", requestId)`. Vì vậy Task 6 không thêm
composite index mới; file `firebase/firestore.indexes.json` hiện có index cho
`attendanceAdjustments`, còn các query tăng ca dùng single-field indexes mặc
định/runtime hiện có.

Kiểm tra tích hợp đã ghi nhận `npm test --prefix firebase/functions` với
**26/26 pass**. Gradle chưa thể chạy vì thiếu `gradle/wrapper/gradle-wrapper.jar`;
Firestore emulator chưa thể chạy vì môi trường có Java 17 trong khi Firebase
CLI yêu cầu Java 21. Task này chỉ cập nhật tài liệu và bàn giao local: không
deploy Firebase, không seed production và không `git push`.

### Weekly scheduling: reviewer fix round 1

The weekly dialog now explains unavailable selected employees and offers an explicit removal action; validation errors remain visible when Save is disabled. The legacy picker and its selected value show supplemental start/end times, including a next-day marker for overnight shifts. Bulk audit entries now target the first canonical workSchedule ID in each chunk and retain all affected IDs in details.

Changed-file inventory: `ui/schedule/WeeklyAssignmentDialog.kt` (selection recovery/validation), `ui/schedule/ScheduleScreen.kt` (picker labels), new `ui/schedule/WeeklySchedulingPresentation.kt` (pure selection/label helpers), and `data/FirebaseRepository.kt` (audit target), all under `app/src/main/java/vn/chamcong/iot/`; new `app/src/test/java/vn/chamcong/iot/ui/schedule/WeeklySchedulingPresentationTest.kt` (four regression tests); `README.md`; appended `.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/weekly-schedule-report.md` (verification and fix hash).

### Phân ca tuần cho nhiều nhân viên (17/09/2026)

Trong **Quản lý ca làm → Lịch → Phân cho nhân viên**, Admin chọn nhiều nhân viên, chọn ngày trong tuần thứ hai–chủ nhật, rồi lưu một trong ba mẫu: **Ca sáng 08:00–12:00**, **Ca chiều 13:00–17:00**, **Ca bổ sung/tăng ca** nhập giờ mỗi lần. Giờ nhập theo `HH:mm`, không được bằng nhau; giờ kết thúc nhỏ hơn giờ bắt đầu nghĩa là kết thúc ngày hôm sau. Mỗi nhân viên/ngày vẫn chỉ có một `WorkSchedule`; phân lại thay ca của ngày đó, không cộng ca thứ hai.

Hạn cảnh báo là **17:00 Chủ nhật trước tuần được chọn, Asia/Ho_Chi_Minh**. Cảnh báo chưa hoàn tất/quá hạn đếm nhân viên active chưa có bất kỳ lịch nào trong tuần; không bắt buộc làm cả bảy ngày. Admin tự kiểm tra các ngày cần làm, vì schema hiện tại chưa có kế hoạch ngày nghỉ. Cảnh báo cập nhật khi màn hình mở và không khóa thao tác sau hạn.

Mẫu là helper thuần; chỉ nút **Lưu phân ca** mới ghi dữ liệu. ID `weekly_v1_<template>_<start>_<end>` ổn định, transaction chỉ tạo ca nếu chưa có và kiểm tra nội dung nếu đã có; không seed trong composition, không tạo ca trùng khi thử lại/cùng lúc. Các ca legacy không bị đổi tên, giờ hoặc gộp tự động. Ca chiều dùng category `EVENING` để tương thích schema. Mẫu đã lưu là snapshot: màn hình ca cho tạo bản tùy chỉnh thay vì sửa snapshot. Nếu snapshot bị sửa từ client cũ, lưu tuần báo lỗi thay vì ghi đè lịch sử cấu hình.

Bulk save dùng ID `${employeeId}_${date}`, merge các field phân ca, giữ `workedHoursOverride`, `adjustmentNote`, `note` hiện có và không ghi attendance/attendanceAdjustments. Mỗi transaction tối đa 400 lịch kèm ca mẫu và audit với server timestamp; lỗi giữa các chunk hiện số lịch đã lưu, có thể thử lại. Phân phòng ban, chọn ô đơn và sao chép tuần vẫn dùng luồng hiện có.

| File thay đổi trong đợt này | Nội dung |
| --- | --- |
| `app/src/main/java/vn/chamcong/iot/domain/WeeklyScheduling.kt` | Mẫu ca thuần, cảnh báo hạn/độ phủ và payload nhân viên × ngày. |
| `app/src/test/java/vn/chamcong/iot/domain/WeeklySchedulingTest.kt` | Biên tuần/năm, trước/đúng/sau hạn, độ phủ, mẫu/giờ bổ sung và lựa chọn bulk. |
| `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt` | Transaction lưu mẫu không trùng, merge lịch và audit theo chunk. |
| `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt` | Intent phân ca tuần qua saving/error hiện có. |
| `app/src/main/java/vn/chamcong/iot/ui/schedule/ScheduleScreen.kt` | Nối nút phân nhân viên vào dialog tuần, cảnh báo, cuộn màn hình/thanh thao tác. |
| `app/src/main/java/vn/chamcong/iot/ui/schedule/WeeklyAssignmentDialog.kt` | Checkbox có nhãn nhân viên/ngày, validation giờ, tiến trình/lỗi và cảnh báo hạn. |
| `app/src/main/java/vn/chamcong/iot/ui/shifts/ShiftsScreen.kt` | Mô tả ba mẫu, giữ snapshot mẫu khi tạo bản tùy chỉnh. |
| `app/src/main/java/vn/chamcong/iot/ui/admin/ShiftManagementScreen.kt` | Mô tả luồng tuần trong hub hiện có. |
| `README.md` | Hướng dẫn, quyết định tương thích và inventory. |
| `.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/weekly-schedule-report.md` | Báo cáo triển khai, RED/GREEN, build và giới hạn kiểm chứng. |

```text
Ngón tay -> AS608/R307 (đối chiếu cục bộ)
                    |
                    v
ESP8266 --Anonymous Auth/HTTPS--> Firestore <--realtime--> Android
   |                                ^
 LED/còi                            | phân giải cùng document
                              Cloud Functions
```

Không lưu ảnh hay đặc trưng vân tay trên Firestore. Module cảm biến giữ template; Firestore chỉ giữ số `fingerprintTemplateId` gắn với nhân viên. Bản production cần xin đồng ý xử lý dữ liệu sinh trắc học, phân quyền, nhật ký truy cập và chính sách xóa dữ liệu.

## Bản mô tả luồng hoạt động hệ thống

Dự án này là một hệ thống chấm công thông minh kết hợp giữa ứng dụng Android, Firebase và thiết bị ESP8266 cảm biến vân tay. Mục tiêu là thay thế hình thức chấm công thủ công bằng một mô hình tự động, có thể theo dõi thời gian làm việc, quản lý nhân viên, thiết bị chấm công và báo cáo doanh nghiệp theo thời gian thực.

### 1. Tổng quan luồng nghiệp vụ

Hệ thống hoạt động theo chu trình sau:

1. Quản trị viên đăng nhập vào ứng dụng Android bằng tài khoản quản trị.
2. Quản trị viên tạo hoặc cập nhật hồ sơ nhân viên, gồm thông tin cá nhân, phòng ban, trạng thái làm việc và thông tin liên kết vân tay.
3. Thiết bị ESP8266 được gắn tại cổng hoặc vị trí làm việc, tự động kết nối Wi-Fi và Firebase.
4. Khi nhân viên đưa ngón tay lên cảm biến, thiết bị AS608/R307 so khớp mẫu vân tay cục bộ.
5. Nếu khớp, thiết bị gửi sự kiện chấm công lên Firebase với thông tin nhân viên, thiết bị, thời gian và trạng thái.
6. Ứng dụng Android nhận dữ liệu realtime từ Firestore, tính toán số giờ, trạng thái đi trễ, về sớm, nghỉ phép và các chỉ số tổng quan.
7. Quản trị viên theo dõi dashboard, xây dựng báo cáo, kiểm tra thiết bị, xử lý đơn từ và lập phiếu lương.

### 2. Luồng đăng nhập và phân quyền

- Tài khoản quản trị sử dụng phương thức Email/Password để truy cập app.
- Tài khoản thiết bị dùng đăng nhập ẩn danh (Anonymous) để giao tiếp với hệ thống mà không cần người dùng thao tác trực tiếp.
- Firestore và Firestore Rules phân quyền theo vai trò:
  - Admin: quản lý nhân viên, thiết bị, ca làm, lương, báo cáo, audit log.
  - Employee: xem thông tin cá nhân, lịch làm, chấm công của mình, gửi đơn từ.
  - Device: chỉ có quyền ghi dữ liệu định danh cần thiết như trạng thái heartbeat, snapshot và sự kiện chấm công theo document thiết bị của mình.
- Khi người dùng đăng nhập, hệ thống kiểm tra `role`, `active`, `employeeId` để xác định quyền truy cập từng màn hình.

### 3. Luồng quản lý nhân viên

- Admin mở màn hình Nhân viên trong ứng dụng.
- Hệ thống hiển thị danh sách nhân viên với các thông tin: mã nhân viên, tên, phòng ban, email, trạng thái làm việc, vân tay đã liên kết hay chưa.
- Khi thêm nhân viên mới:
  - Hệ thống cấp mã nhân viên tự động theo quy tắc định dạng như NV0001, NV0002...
  - Thông tin nhân viên được lưu vào collection `employees`.
  - Nếu admin chọn đăng ký vân tay, app gửi lệnh đến thiết bị qua collection `deviceCommands/{deviceId}`.
  - Thiết bị nhận lệnh, quét vân tay, lưu mẫu trên cảm biến AS608 và gửi lại template ID về Firestore.
- Khi xóa nhân viên:
  - Hồ sơ không bị xóa vật lý mà chuyển sang trạng thái `active = false`.
  - Dữ liệu chấm công, phiếu lương và lịch sử hoạt động vẫn được giữ nguyên để đảm bảo tính minh bạch, đúng quy định và audit.
  - Vân tay liên kết cũng được vô hiệu hóa để tránh chấm công sai khi nhân viên chưa còn làm việc.

### 4. Luồng đăng ký vân tay

- Admin chọn nhân viên và bấm chức năng “Lưu & đăng ký vân tay”.
- App tạo một lệnh đăng ký trên thiết bị tương ứng.
- ESP8266 nhận lệnh, bật LED, phát tiếng bíp và yêu cầu khách hàng đưa ngón tay lên cảm biến.
- Sensor AS608 lấy mẫu vân tay và thực hiện xác thực đủ 2 lần quét để đảm bảo độ chính xác.
- Sau khi thành công, thiết bị lưu template vân tay trong bộ nhớ cảm biến và trả về `fingerprintTemplateId` hoặc trạng thái hoàn tất.
- App cập nhật lại thông tin nhân viên trong Firestore với mối liên kết giữa nhân viên và vị trí vân tay.
- Nếu quá thời gian hoặc quét không khớp, LED đỏ báo lỗi và hệ thống giữ trạng thái thất bại để admin có thể thử lại.

### 5. Luồng chấm công bằng vân tay

- Nhân viên đưa ngón tay lên cảm biến tại thiết bị chấm công.
- Sensor đọc mẫu vân tay và đối chiếu với template đã lưu trên AS608.
- Nếu khớp:
  - Thiết bị kiểm tra mapping trên Firestore xem nhân viên có đang active và có quyền chấm công hay không.
  - Nếu hợp lệ, thiết bị ghi lượt thô `SCAN/PENDING` với thời gian NTP UTC; Cloud Function phân giải theo lịch ca.
  - App Android theo dõi realtime collection `attendance` để cập nhật trạng thái chấm công ngay trên dashboard và màn hình chấm công.
- Nếu không khớp hoặc mapping đã bị vô hiệu hóa:
  - Hệ thống từ chối xác thực.
  - Không ghi sự kiện attendance, tránh vi phạm/ghi nhầm người khác.

#### Phân giải theo lịch ca và điều chỉnh chấm công

`resolveAttendance` chạy khi tạo `attendance/{eventId}`, đọc lại lượt `SCAN` có `resolutionStatus=PENDING` trong transaction, xác minh mapping vân tay đang bật và nhân viên còn active. Hàm tra `workSchedules` của ngày quét và ngày trước đó theo `Asia/Ho_Chi_Minh`, đọc `shifts`, rồi cập nhật chính document này. ID lượt, thiết bị và thời điểm quét được giữ nguyên; loại/trạng thái được thay bằng kết quả server, kèm `scheduleDate`, `shiftId`, `receivedAt`, `resolvedAt`. Android và thiết bị không được update/delete attendance.

- `scheduleDate` là ngày bắt đầu ca (`yyyy-MM-dd`), không nhất thiết là ngày trên đồng hồ khi chấm ra. Ví dụ ca 22:00 ngày 17/09 đến 06:00 ngày 18/09: cả hai lượt mang `scheduleDate=2026-09-17`. Nếu giờ kết thúc nhỏ hơn hoặc bằng giờ bắt đầu, kết thúc thuộc ngày kế tiếp.
- Cửa sổ nhận lượt chạy từ đầu ca trừ `allowEarlyMinutes` đến cuối ca cộng `missingCheckOutGraceMinutes`, gồm cả hai mốc. Khi nhiều cửa sổ khớp, server chọn ca có mốc đầu/cuối gần lượt quét nhất, rồi ưu tiên ca bắt đầu sớm hơn nếu bằng nhau.
- Chưa có phiên mở: lượt gần đầu ca hơn (hoặc cách đều) là `CHECK_IN`; gần cuối ca hơn là `CHECK_OUT` để bộc lộ trường hợp thiếu chấm vào. Có check-in đang mở thì lượt hợp lệ tiếp theo đóng phiên bằng `CHECK_OUT`. `attendanceSessions/{employeeId}_{scheduleDate}` chỉ do backend truy cập; transaction đọc lại trạng thái để tránh phân giải lại một event.
- Trong ca đã chọn, lượt cách lượt được chấp nhận gần nhất không quá **3 phút (180.000 ms, kể cả đúng 3 phút)** là `DUPLICATE`. Lượt cũ hơn nằm ngoài cửa sổ trùng là `OUT_OF_ORDER`; không có ca khớp hoặc phiên đã đóng là `UNSCHEDULED`. Lượt bị từ chối không thay đổi phiên được chấp nhận và có `status=ABNORMAL`. Không có quy tắc trước/sau 12 giờ để quyết định vào/ra.
- Lượt hợp lệ có `resolutionStatus=ACCEPTED`; server hiện đặt `status=NORMAL`, còn Android tính đi trễ/về sớm theo ca và cặp hiệu lực. Trigger FCM xét update có trạng thái trước khác `ACCEPTED` và trạng thái sau là `ACCEPTED` (luồng resolver bình thường là `PENDING` → `ACCEPTED`); helper hiện kiểm tra cấu trúc/trường trạng thái, không tự xác minh lại loại lượt. Lịch sử vẫn hiển thị riêng pending, trùng, ngoài lịch và sai thứ tự; chúng không đóng góp giờ làm.
- Khi có chấm vào nhưng chưa chấm ra, chỉ đánh dấu thiếu chấm ra khi thời điểm hiện tại **sau** cuối ca cộng `missingCheckOutGraceMinutes`. Mặc định là **60 phút**, kể cả ca cũ chưa có field; giá trị `0` được giữ nguyên. Ca 22:00–06:00 với mặc định này chỉ quá hạn sau 07:00 hôm sau. Không tự tạo checkout hoặc tự cộng giờ đến hết ca. Khi không có thông tin ca, fallback thiếu checkout là sau ngày lịch tương ứng.

Admin vào **Chấm công → Điều chỉnh** trên dòng lịch sử để xem nhân viên, ngày ca, giờ vào/ra và giờ công hiệu lực. Nhập giờ theo `yyyy-MM-dd HH:mm` tại `Asia/Ho_Chi_Minh` (ca qua đêm phải nhập ngày hôm sau cho giờ ra), hoặc nhập giờ công từ 0 đến 24, và **bắt buộc nhập lý do không rỗng**. Ít nhất một giá trị phải thay đổi; nếu có cả hai mốc thì giờ ra phải sau giờ vào. Ô bỏ trống giữ giá trị hiệu lực hiện tại, không xóa giá trị; giờ override cũ được giữ khi để trống ô giờ công.

Mỗi lần lưu tạo mới `attendanceAdjustments/{id}` theo nhân viên/ngày ca, chứa mốc sửa và/hoặc `workedHoursOverride`, lý do, người thực hiện lấy từ phiên đăng nhập và timestamp server. Cùng một batch tạo `audit_logs/{id}` với `action=ATTENDANCE_ADJUST`, `targetType=attendanceAdjustment`, cùng ID, actor và reason. Chi tiết audit ghi giá trị adjustment trước/sau (không phải snapshot toàn bộ raw scans). Rules yêu cầu cặp adjustment/audit này tồn tại cùng lần ghi và cấm update/delete cả hai: sửa tiếp phải thêm bản ghi mới, không ghi đè lịch sử. Chỉ Admin được tạo adjustment; nhân viên chỉ đọc adjustment của mình.

Android dùng adjustment hợp lệ mới nhất theo `createdAt` của nhân viên/ngày ca cùng các lượt `verified=true`, `ACCEPTED`, loại vào/ra và `status` hợp lệ để tính cặp hiệu lực cho hiện diện, báo cáo, màn hình nhân viên và giờ công khi lập phiếu lương. Trường bị bỏ qua ở tài liệu legacy giữ default; giá trị trạng thái tường minh không hợp lệ không tạo giờ công. `workedHoursOverride` mới ưu tiên hơn override cũ trên lịch; phiếu lương đã lưu không tự được viết lại. Lượt legacy chưa có `scheduleDate` được gán một lần theo các lịch đã tải, ưu tiên ngày tường minh; không có ca thì dùng ngày địa phương. Toàn bộ phép tính dựa trên dữ liệu đã tải, không tự backfill lịch sử hoặc sửa raw scans.

Admin tải toàn bộ `workSchedules`, độc lập tuần đang chọn, để tính lương tháng, báo cáo và hiện diện kể cả ca qua ranh giới tháng. Chọn tuần chỉ thay đổi khoảng hiển thị; listener nhân viên vẫn giới hạn theo nhân viên. Dashboard và bộ lọc lịch sử tính đi trễ từ ca và cặp hiệu lực, cập nhật khi lịch/ca/adjustment thay đổi; không có ca thì giữ fallback trạng thái legacy. Hộp lập phiếu cập nhật giờ tự tính theo dữ liệu mới cho đến khi người dùng nhập giờ thủ công. Listener toàn bộ lịch tăng số document đọc; giới hạn lịch sử attendance/adjustment hiện có vẫn áp dụng.

Lượt server `DUPLICATE` không ghi đè trạng thái của cặp hợp lệ; `UNSCHEDULED`/`OUT_OF_ORDER` vẫn hiện bất thường. Tổng ngày và báo cáo dùng `PRESENT` (đang làm việc) trước hoặc đúng hạn cuối ca cộng grace; chỉ sau hạn mới là `MISSING_CHECK_OUT`. Nghỉ phép chỉ áp dụng cho nhân viên trên đơn. Nghỉ giữa ca 02:00–02:30 của ca 22:00–06:00 được trừ ở ngày kế tiếp. Rules cho phép bỏ qua `missingCheckOutGraceMinutes`; nếu có thì phải là số nguyên không âm.

### 6. Luồng xử lý thiết bị ESP8266

- ESP8266 khởi động và tự động kết nối Wi-Fi.
- Thiết bị cần xác thực với Firebase hoặc sử dụng Anonymous Auth nếu được cấu hình cho môi trường prototype.
- Thiết bị gửi `heartbeat` định kỳ cho Firestore, cho biết trạng thái online/offline, firmware đang chạy, số lượng mẫu vân tay và capability của thiết bị.
- Nếu có lệnh từ hệ thống, ví dụ: đăng ký vân tay, xóa vân tay, cập nhật cấu hình, thiết bị thực hiện theo hàng đợi.
- Nếu mất mạng, thiết bị lưu outbox và tự retry khi có kết nối trở lại.
- Mỗi lần quét hợp lệ được gửi lên Firestore dưới dạng một sự kiện thô trong chính document `attendance/{eventId}`, với `type=SCAN`, `resolutionStatus=PENDING`, `status=PENDING`. `eventId`, `deviceId`, timestamp NTP UTC và provenance của lượt quét thô được giữ nguyên; sau đó Cloud Function cập nhật các trường phân giải (`type`, `resolutionStatus`, `status`, `scheduleDate`) ngay trên cùng document theo ca làm. Không tạo document hoặc type phân giải thứ hai, và `SCAN` không được lưu song song với một resolved document/type khác; firmware không suy đoán loại lượt theo giờ địa phương.
- Dashboard và màn hình Thiết bị trên Android nhận snapshot heartbeat để hiển thị trạng thái đầu vào, thời gian online, firmware version và tình trạng hoạt động.

#### Chế độ offline của thiết bị

Khi ESP8266 mất kết nối mạng hoặc Firebase không phản hồi, thiết bị không bỏ qua dữ liệu chấm công. Thay vào đó, thiết bị lưu các sự kiện chưa gửi vào bộ nhớ cục bộ dưới dạng hàng đợi offline, thường được triển khai trên filesystem như `LittleFS` với đường dẫn `/attendance.outbox`.

Mỗi bản ghi trong hàng đợi offline chứa thông tin như:

- `eventId`: mã duy nhất của sự kiện chấm công
- `deviceId`: mã thiết bị
- `employeeId` hoặc thông tin nhân viên được xác định
- `timestamp`: thời gian quét vân tay
- `type`: bắt đầu là `SCAN` trong payload firmware, sau đó Cloud Function ghi đè ngay trên cùng document bằng loại đã phân giải như `CHECK_IN` hoặc `CHECK_OUT`
- `resolutionStatus`: `PENDING` trước khi Cloud Function phân giải, sau đó được cập nhật in place thành trạng thái như `ACCEPTED`, `DUPLICATE`, `UNSCHEDULED` hoặc `OUT_OF_ORDER`
- `status`: bắt đầu là `PENDING` ở payload firmware và được Cloud Function cập nhật cùng các trường phân giải; không dùng giờ địa phương trên thiết bị để gán `LATE`/`NORMAL`

Thiết bị và client không được update hoặc delete document `attendance`; Firestore Rules cấm các thao tác đó. Chỉ Cloud Function có quyền đặc quyền mới được cập nhật các trường phân giải trên document hiện có.

Khi mạng trở lại, ESP8266 tự động đọc lại hàng đợi, gửi từng sự kiện theo thứ tự và dùng cùng `eventId` để tránh trùng lặp dữ liệu. Những request gửi thành công với mã HTTP 2xx hoặc 409 sẽ được đánh dấu là đã xử lý; các lỗi mạng hoặc lỗi xác thực tạm thời sẽ được giữ lại để retry ở lần gửi tiếp theo. Cách làm này giúp hệ thống duy trì tính toàn vẹn dữ liệu và giảm nguy cơ mất lượt chấm công trong điều kiện mạng yếu.

### 7. Luồng dashboard và giám sát realtime

- Mỗi khi dữ liệu chấm công, nhân viên, thiết bị hoặc đơn từ thay đổi, ViewModel sẽ nhận dữ liệu từ Firebase và hiển thị trên giao diện.
- Dashboard tổng hợp các chỉ số chính:
  - Tổng số nhân viên
  - Số nhân viên đang làm
  - Số người đến muộn
  - Số người chưa chấm ra
  - Số ca làm trong tuần
  - Tình trạng thiết bị online/offline
- Dữ liệu được tính theo logic nghiệp vụ trong domain layer, không tính trực tiếp trong UI.
- Biểu đồ hiển thị theo tuần và theo trạng thái công việc để quản lý dễ dàng hơn.

### 8. Luồng lịch làm, ca, đơn từ và có mặt

- Admin có thể tạo ca chính, lập lịch theo tuần, phân ca cho từng nhân viên hoặc phòng ban.
- Lịch tuần hiện dùng ca chính như ca sáng 08:00–12:00 và ca chiều 13:00–17:00; tăng ca không phân sẵn mà đi qua đơn 17:30–20:30 của nhân viên.
- Nếu có thay đổi lịch làm hoặc nhân viên vắng mặt, admin có thể gửi yêu cầu từ nhân viên/điều chỉnh bằng đơn từ.
- Hệ thống phân loại trạng thái:
  - Đã vào công ty
  - Chưa đến
  - Đang nghỉ phép
  - Đã ra về
  - Chưa chấm ra
  - Có mặt bất thường
- Đơn từ được xử lý theo quy trình `PENDING` → `APPROVED/REJECTED`, có lý do từ chối bắt buộc nếu từ chối.

### 9. Luồng lương và báo cáo

- Admin chọn tháng, nhân viên và nhập đơn giá theo giờ.
- Hệ thống tự tính số giờ làm dựa trên các cặp chấm công vào/ra, có thể chỉnh sửa thủ công nếu cần.
- Công thức lương thường là:
  - Lương cơ bản = Đơn giá/giờ × Số giờ làm
  - Thực lĩnh = Lương cơ bản + Thưởng − Khấu trừ
- Mỗi nhân viên có một phiếu lương theo tháng; dữ liệu này được lưu để theo dõi và xuất báo cáo.
- Báo cáo có thể được tổng hợp theo khoảng thời gian, phòng ban, mã nhân viên và loại hoạt động, rồi xuất ra file CSV hoặc chia sẻ qua hệ thống Android share sheet.

### 10. Luồng audit log và bảo mật

- Mọi hành động quan trọng của admin đều được ghi lại trong `audit_logs`.
- Audit log ghi:
  - Người thực hiện
  - Hành động
  - Đối tượng ảnh hưởng
  - Thời gian
  - Lý do hoặc chi tiết thay đổi
- Nhờ đó, hệ thống có thể theo dõi các thao tác như đăng nhập, thêm/sửa/xóa nhân viên, xóa vân tay, điều chỉnh chấm công, trả lời đơn từ, cập nhật lương, thiết lập cài đặt hệ thống.
- Nếu tài khoản bị vô hiệu hóa, không có quyền role phù hợp hoặc không có employeeId, hệ thống chặn tiếp cận các màn hình quan trọng.

### 11. Luồng dữ liệu và kiến trúc phần mềm

- Ứng dụng Android dùng mô hình MVVM + Jetpack Compose.
- UI chỉ hiển thị state và phát sự kiện, không trực tiếp thao tác Firebase.
- `MainViewModel` là trung tâm điều phối state, realtime listener và các intent nghiệp vụ.
- `FirebaseRepository` là lớp duy nhất giao tiếp với Firebase.
- Domain layer chứa toàn bộ logic tính toán và quy tắc nghiệp vụ như dashboard, lương, lọc dữ liệu, báo cáo, đơn từ, trạng thái thiết bị.
- Model layer định nghĩa dữ liệu nền tảng để app làm việc với Firestore, attendance, payroll, audit, device, user.

### 12. Kết luận

Dự án này không chỉ là một ứng dụng chấm công đơn thuần, mà là một hệ thống quản lý nhân sự hiện đại tích hợp thiết bị phần cứng và nền tảng điện toán đám mây. Từ việc đăng nhập, quản lý nhân viên, đăng ký vân tay, chấm công tự động, theo dõi thiết bị, cho đến lương, báo cáo và audit, toàn bộ hệ thống được thiết kế theo hướng tự động hóa và minh bạch. Điều này giúp giảm sai sót thủ công, tăng độ tin cậy và tạo nền tảng để doanh nghiệp quản lý nhân sự hiệu quả hơn trong thời gian dài.

## Báo cáo luồng hoạt động và chức năng từng tác vụ, nút bấm

Phần này mô tả hệ thống theo góc nhìn người sử dụng. Tên nút bên dưới là tên đang hiển thị trong Android app; một số thẻ/card, dòng tác vụ và ô trên lịch là vùng có thể bấm dù không được vẽ dưới dạng nút. Khi nút đang xử lý, app khóa thao tác ghi trùng và hiển thị thông báo thành công hoặc lỗi ngay trên màn hình.

### 1. Luồng sử dụng tổng thể

```text
Đăng nhập
   -> Firebase xác thực tài khoản
   -> đọc users/{uid} để kiểm tra role, active, employeeId
   -> mở giao diện Admin hoặc Nhân viên

Thiết bị đọc vân tay
   -> ghi lượt SCAN/PENDING vào attendance
   -> Cloud Function đối chiếu lịch và ca gần nhất
   -> phân giải CHECK_IN/CHECK_OUT hoặc trạng thái bất thường
   -> Android nhận realtime và cập nhật công, hiện diện, báo cáo

Nhân viên gửi đơn tăng ca
   -> Admin duyệt hoặc từ chối
   -> lượt quét tăng ca được phân giải lại theo ca 17:30–20:30
   -> ca tăng ca hợp lệ được cộng giờ và thưởng vào phiếu lương
```

Quy trình vận hành chuẩn của Admin là: tạo hồ sơ nhân viên → đăng ký vân tay → tạo/kiểm tra ca → phân lịch tuần trước **17:00 Chủ nhật** → theo dõi chấm công → xử lý đơn → điều chỉnh các trường hợp có lý do → lập lương và xem báo cáo/audit.

### 2. Đăng nhập, phân quyền và menu chung

| Vị trí/nút | Chức năng | Kết quả |
| --- | --- | --- |
| `Đăng nhập` | Đăng nhập bằng Email/Password. | Firebase xác thực, sau đó app đọc hồ sơ `users/{uid}`. |
| `Quên mật khẩu` | Gửi email đặt lại mật khẩu cho email đã nhập. | Không mở giao diện nếu tài khoản chưa xác thực thành công. |
| `Đăng xuất` | Kết thúc phiên hiện tại. | Xóa trạng thái người dùng trên app và quay về màn hình đăng nhập. |
| `Đổi mật khẩu` | Mở form nhập mật khẩu mới và nhập lại mật khẩu. | Chỉ lưu khi mật khẩu đủ tối thiểu 6 ký tự và hai ô trùng nhau. |
| `Tổng quan` | Màn hình tổng hợp tuần dành cho Admin. | Hiển thị lượt chấm, thiết bị, cảnh báo và thông báo cần xử lý. |
| `Tác vụ` | Mở danh mục các module quản trị. | Điều hướng nhanh đến từng màn hình nghiệp vụ. |
| `Đơn từ` | Mở đơn nghỉ/đi muộn/về sớm/điều chỉnh/đổi ca và đơn tăng ca. | Admin lọc, duyệt hoặc từ chối theo từng loại đơn. |
| `Phân ca` | Mở hub quản lý ca và lịch. | Điều hướng đến `Ca làm` hoặc `Lịch`. |
| `Nhân viên` | Mở danh sách hồ sơ nhân sự. | Thêm, tìm kiếm, lọc, cài lương, đăng ký/xóa vân tay hoặc chuyển nhân viên sang đã nghỉ. |

Nếu hồ sơ không có `role` phù hợp, bị `active = false`, hoặc tài khoản nhân viên không có `employeeId`, app hiển thị trạng thái chưa được cấp quyền và chỉ cho phép `Đăng xuất`.

### 3. Chức năng các nút trên giao diện Admin

#### 3.1. Tổng quan

| Nút/vùng thao tác | Chức năng |
| --- | --- |
| `‹` | Xem tuần trước. |
| `Tuần này` | Trở về tuần hiện tại theo múi giờ ứng dụng. |
| `›` | Xem tuần sau. |
| `Đã đọc` | Đánh dấu một thông báo trong app là đã đọc. |
| `Xem tất cả` | Mở màn hình `Chấm công` từ danh sách lượt chấm mới nhất. |
| Thẻ cảnh báo/thống kê | Hiển thị số đơn chờ duyệt, người chưa chấm ra, lượt bất thường, lệnh thiết bị lỗi và thiết bị offline; dữ liệu được cập nhật từ Firebase realtime. |

#### 3.2. Tác vụ và Phân ca

Các dòng trong `Tác vụ` và các card trong `Phân ca` đều có thể bấm:

| Mục | Chức năng |
| --- | --- |
| `Chấm công` | Xem lịch sử lượt vào/ra, lọc trạng thái/loại lượt và điều chỉnh công. |
| `Thiết bị` | Xem tín hiệu cuối, phiên bản phần mềm, trạng thái hoạt động/mất kết nối, số mẫu vân tay và lệnh gần đây. |
| `Có mặt` | Xem ai đã vào, chưa đến, đang nghỉ, đã ra, chưa chấm ra hoặc bất thường theo ngày. |
| `Ca làm` | Tạo và quản lý các mẫu ca chính; xem ca sáng 08:00–12:00, ca chiều 13:00–17:00 và cấu hình ca tùy chỉnh. Tăng ca 17:30–20:30 không phân trước ở màn hình này. |
| `Lịch` | Phân ca cho từng nhân viên, nhiều nhân viên hoặc phòng ban theo tuần/tháng. |
| `Lương` | Tính giờ, thưởng KPI, khấu trừ và lưu phiếu lương theo tháng. |
| `Hiệu suất` | Xem số ca tăng ca, giờ tăng ca, số lần đi muộn, hạng Top 3 và tiền thưởng tự động. |
| `Báo cáo` | Lọc dữ liệu và xuất CSV để chia sẻ. |
| `Nhật ký` | Xem audit log ở chế độ chỉ đọc. |
| `Cài đặt` | Hiện khu vực dành cho cấu hình doanh nghiệp; phần kết nối Firebase Settings cụ thể chưa được nối vào một nghiệp vụ riêng. |

#### 3.3. Nhân viên

| Nút/vùng thao tác | Chức năng |
| --- | --- |
| `Thêm` hoặc nút `+` | Mở form tạo nhân viên mới. |
| Bộ lọc phòng ban | Chỉ hiển thị nhân viên thuộc phòng ban đã chọn. |
| Ô tìm kiếm | Tìm theo tên hoặc mã nhân viên. |
| `Hiện nhân viên đã nghỉ` | Bật/tắt việc hiển thị hồ sơ `active = false`. Hồ sơ cũ không bị xóa vật lý. |
| `Thiết lập lương` | Nhập đơn giá lương cơ bản theo giờ. |
| `Đăng ký vân tay` | Chọn mã thiết bị rồi gửi lệnh để ESP8266 hướng dẫn quét cùng một ngón tay hai lần. |
| `Xóa vân tay` | Gửi lệnh xóa mẫu trên thiết bị nhưng giữ hồ sơ nhân viên và lịch sử lương. |
| `Xóa nhân viên` | Chuyển nhân viên sang đã nghỉ, giữ lịch sử, đồng thời yêu cầu xóa mẫu vân tay nếu có. |
| `Lưu & đăng ký vân tay` | Lưu hồ sơ mới và gửi luôn lệnh đăng ký vân tay. |
| `Chỉ lưu nhân viên` | Chỉ lưu hồ sơ/tài khoản, chưa gửi lệnh vân tay. |
| `Gửi lệnh` | Xác nhận mã thiết bị trong hộp thoại đăng ký/xóa vân tay. |
| `Xác nhận xóa` | Xác nhận xóa vân tay hoặc chuyển nhân viên sang đã nghỉ. |
| `Hủy` | Đóng hộp thoại mà không ghi thay đổi đang nhập. |

#### 3.4. Chấm công và điều chỉnh công

| Nút/vùng thao tác | Chức năng |
| --- | --- |
| Lọc trạng thái `Tất cả`, `Đúng giờ`, `Đi trễ`, `Về sớm` | Lọc các lượt đã được phân giải theo trạng thái hiển thị. |
| Lọc loại `Tất cả loại`, `Vào ca`, `Ra ca` | Lọc theo `CHECK_IN` hoặc `CHECK_OUT`. |
| `Điều chỉnh` | Mở hồ sơ công của nhân viên/ngày ca đang chọn. |
| `Lưu điều chỉnh` | Lưu giờ vào, giờ ra hoặc giờ công override; bắt buộc có lý do. Mỗi lần sửa tạo bản ghi append-only và audit mới. |
| `Hủy` | Đóng hộp thoại điều chỉnh. |

Khi điều chỉnh, Admin nhập giờ theo `yyyy-MM-dd HH:mm` của `Asia/Ho_Chi_Minh`, hoặc giờ công từ 0 đến 24. Ô để trống giữ giá trị đang có; không dùng form này để xóa dữ liệu. Với ca qua đêm, giờ ra phải ghi sang ngày hôm sau.

#### 3.5. Đơn từ và tăng ca

| Nút/vùng thao tác | Chức năng |
| --- | --- |
| Chip `Tất cả`, `Chờ duyệt`, `Đã duyệt`, `Từ chối` | Lọc đơn thường theo trạng thái. |
| `Duyệt` | Duyệt đơn thường hoặc đơn tăng ca. Đơn tăng ca chuyển sang `APPROVED` và trigger phân giải lại các lượt quét liên quan. |
| `Từ chối` | Mở form lý do từ chối; lý do bắt buộc không được để trống. |
| `Hủy` trong form từ chối | Không thay đổi trạng thái đơn. |
| Chip lọc tăng ca `Tất cả`, `Chờ duyệt`, `Đã duyệt`, `Từ chối` | Lọc riêng các đơn tăng ca. |

Đơn tăng ca luôn có khung cố định **17:30–20:30**, nhân viên tự gửi đơn cho hôm nay hoặc ngày tương lai. Nếu Admin chưa kịp duyệt, đơn vẫn nằm chờ và nhân viên vẫn được quét; những lượt đó được giữ `OVERTIME_PENDING`, chưa được tính tiền. Khi duyệt, hệ thống mới tính cặp vào/ra hợp lệ; khi từ chối, raw scan vẫn giữ để tra cứu nhưng không tính giờ/tiền tăng ca.

#### 3.6. Ca làm

| Nút/vùng thao tác | Chức năng |
| --- | --- |
| `Thêm ca` | Mở form tạo mẫu ca. |
| `Chỉnh sửa` | Chỉnh mẫu ca đã lưu. |
| `Tạo bản tùy chỉnh` | Tạo bản mới từ snapshot ca tuần, không sửa ngược lịch sử đã phân. |
| Chip loại ca | Chọn loại ca khi cấu hình mẫu; mẫu ca bổ sung không được gán trước vào lịch tuần. |
| Checkbox `Ca này được tính tăng ca` | Đánh dấu thuộc tính tăng ca của mẫu ca; không thay thế quy trình đơn tăng ca cố định 17:30–20:30. |
| `Lưu ca` | Kiểm tra và lưu mẫu ca. |
| `Hủy` | Đóng form ca. |

#### 3.7. Lịch phân ca

| Nút/vùng thao tác | Chức năng |
| --- | --- |
| `‹ Tuần trước` / `Tuần sau ›` | Chuyển tuần đang xem. |
| `Tuần này` | Trở về tuần hiện tại. |
| `Phân cho nhân viên` | Mở form tuần; tích chọn nhiều nhân viên và nhiều ngày để gán nhanh ca sáng hoặc ca chiều. |
| `Phân cho phòng ban` | Gán một ca cho phòng ban ở ngày đang chọn. |
| `Sao chép tuần trước` | Sao chép lịch tuần trước sang tuần đang xem. |
| Chip `Tuần` / `Tháng` | Đổi kiểu hiển thị lịch. |
| Ô ngày/ô nhân viên | Mở form phân ca cho đúng ngày và nhân viên. |
| Nút chọn ca | Mở danh sách các ca chính được phép gán. Ca tăng ca không xuất hiện ở đây. |
| `Bỏ nhân viên không còn khả dụng` | Loại các nhân viên đã nghỉ/không còn hợp lệ khỏi danh sách checkbox tuần. |
| Checkbox nhân viên/ngày | Chọn nhanh ma trận nhân viên × ngày. |
| `Lưu phân ca` | Ghi lịch theo transaction, mỗi nhân viên/ngày chỉ có một lịch; giữ các override/lý do đang có. |
| `Hủy` | Đóng form mà không lưu. |

Hạn nhắc lịch tuần là **17:00 Chủ nhật** theo giờ Việt Nam. Đây là cảnh báo nghiệp vụ, không khóa thao tác; Admin vẫn có thể cập nhật lịch sau thời hạn. Ca bổ sung không được phân sẵn trong lịch tuần, vì nhân viên phải gửi đơn và Admin duyệt trong `Đơn từ`.

#### 3.8. Thiết bị, Lương, Hiệu suất, Báo cáo và Nhật ký

| Màn hình/nút | Chức năng |
| --- | --- |
| `Sửa cấu hình` → `Lưu` | Sửa tên/vị trí thiết bị; không sửa heartbeat do firmware ghi. |
| `Lập phiếu lương / thiết lập lương` | Mở danh sách nhân viên cần cài đơn giá hoặc lập phiếu trong tháng. |
| `Đặt lương` | Lưu đơn giá lương cơ bản theo giờ. |
| `Lập phiếu` | Mở phiếu lương với giờ đã tính, thưởng tự động và khấu trừ Admin nhập. |
| `Lưu phiếu` | Lưu snapshot phiếu lương theo tháng; phiếu đã lưu không tự viết lại. |
| Ô `Tháng hiệu suất` | Chọn tháng để xem KPI, không có nút lưu riêng. |
| `‹ Tuần trước` / `Tuần sau ›` ở báo cáo tổng hợp | Chuyển khoảng tuần báo cáo. |
| `Tuần đang chọn` / `Tháng này` | Đặt nhanh khoảng thời gian báo cáo. |
| Chip loại báo cáo | Chọn chấm công, nghỉ phép hoặc thiết bị. |
| `Xuất CSV và chia sẻ` | Tạo file CSV theo bộ lọc ngày/nhân viên/phòng ban rồi mở Android share sheet. |
| Ô lọc trong `Nhật ký` | Tìm theo hành động hoặc đối tượng; nhật ký chỉ đọc, không có nút sửa/xóa. |

#### 3.9. Chức năng trong từng hộp thoại

| Hộp thoại | Thành phần/nút | Chức năng và điều kiện |
| --- | --- | --- |
| `Thêm nhân viên` | `Họ tên`, `Email`, `Phòng ban` | Nhập thông tin hồ sơ. Mã nhân viên được cấp tự động khi lưu, không nhập thủ công. |
| `Thêm nhân viên` | `Mã thiết bị đăng ký` | Xác định ESP8266 sẽ nhận lệnh đăng ký vân tay nếu Admin chọn lưu kèm vân tay. |
| `Thêm nhân viên` | `Tạo tài khoản đăng nhập cho nhân viên` | Bật để tạo tài khoản Email/Password cho nhân viên; mật khẩu tối thiểu 6 ký tự và phải nhập lại trùng nhau. |
| `Thêm nhân viên` | `Lưu & đăng ký vân tay` | Lưu hồ sơ, tạo tài khoản nếu đã bật và gửi lệnh cho thiết bị để quét cùng một ngón tay hai lần. |
| `Thêm nhân viên` | `Chỉ lưu nhân viên` | Chỉ lưu hồ sơ/tài khoản, chưa gửi lệnh vân tay. |
| `Đăng ký vân tay` | `Mã thiết bị` → `Gửi lệnh` | Gửi yêu cầu đăng ký mẫu đến thiết bị. Thiết bị phải bật và có Wi-Fi để nhận lệnh. |
| `Thiết lập lương` | `Lương cơ bản / giờ` → `Lưu đơn giá giờ` | Lưu đơn giá theo giờ; chỉ nhận số không âm trong giới hạn hệ thống. |
| `Điều chỉnh chấm công` | `Giờ vào`, `Giờ ra`, `Giờ công`, `Lý do điều chỉnh` | Sửa công cho đúng nhân viên/ngày ca. Ít nhất một giá trị công phải thay đổi và lý do không được để trống. |
| `Điều chỉnh chấm công` | `Lưu điều chỉnh` | Ghi một bản điều chỉnh mới và audit; không ghi đè hoặc xóa lịch sử điều chỉnh cũ. |
| `Thêm ca`/`Chỉnh sửa ca` | Chip `Ca sáng`, `Ca chiều`, `Ca bổ sung` | Chọn loại ca. Khi đổi loại, tên ca được gợi ý lại nhưng Admin vẫn có thể sửa tên. |
| `Thêm ca`/`Chỉnh sửa ca` | Các ô giờ và thời gian cho phép | Nhập giờ `HH:mm`, phút chấm sớm/đi trễ/về sớm, thời gian nghỉ và khoảng ngày áp dụng. |
| `Thêm ca`/`Chỉnh sửa ca` | `Ca này được tính tăng ca` | Đánh dấu thuộc tính tăng ca của mẫu ca; không thay thế đơn tăng ca cố định 17:30–20:30. |
| `Thêm ca`/`Chỉnh sửa ca` | `Lưu ca` / `Hủy` | Kiểm tra và lưu mẫu ca hoặc đóng hộp thoại không lưu. Ca snapshot từ lịch tuần dùng `Tạo bản tùy chỉnh` để không sửa lịch sử. |
| `Phân ca cho nhân viên` | Chip phòng ban, nhân viên, ngày trong tuần | Lọc và tích chọn nhanh nhiều nhân viên × nhiều ngày. Có thể bỏ các nhân viên không còn hợp lệ khỏi danh sách chọn. |
| `Phân ca cho nhân viên` | Chip `Ca sáng` / `Ca chiều`, ô `Điều chỉnh giờ làm`, ô `Lý do điều chỉnh` | Chọn ca chính và tùy chọn ghi nhận số giờ override khi quên chấm/mất mạng. Ca tăng ca không được chọn sẵn ở đây. |
| `Phân ca cho nhân viên` | `Lưu phân ca` / `Hủy` | Lưu theo transaction; mỗi nhân viên/ngày chỉ có một lịch và vẫn giữ override/lý do hợp lệ đang có. |
| `Từ chối đơn từ` hoặc `Từ chối đăng ký tăng ca` | `Lý do từ chối` → `Từ chối` | Bắt buộc nhập lý do, sau đó chuyển đơn sang `Từ chối` và ghi audit. |
| `Từ chối đơn từ` hoặc `Từ chối đăng ký tăng ca` | `Hủy` | Đóng form từ chối, giữ nguyên trạng thái `Chờ duyệt`. |
| `Lập phiếu lương / thiết lập lương` | `Đặt lương` / `Lập phiếu` | Với nhân viên đang làm, `Đặt lương` mở form đơn giá; `Lập phiếu` mở bản tính lương của tháng. |
| `Phiếu lương` | `Khấu trừ` → `Lưu phiếu` | Nhập khoản khấu trừ do Admin quyết định, kiểm tra giờ/thưởng tự động và lưu snapshot phiếu lương. |
| `Đổi mật khẩu` | Mật khẩu mới, nhập lại → `Lưu` / `Hủy` | Đổi mật khẩu tài khoản hiện tại nếu đủ 6 ký tự và hai ô trùng nhau; `Hủy` bỏ thao tác. |

#### 3.10. Quy tắc phản hồi khi bấm nút

- Nút lưu, duyệt, từ chối, gửi lệnh và xóa bị vô hiệu hóa trong lúc Firebase đang xử lý để tránh ghi trùng.
- Nút lưu chỉ được bật khi dữ liệu bắt buộc hợp lệ: ngày/giờ đúng định dạng, lý do không rỗng, số tiền và số giờ nằm trong giới hạn.
- Thông báo màu đỏ là lỗi kiểm tra dữ liệu hoặc lỗi quyền Firebase; dữ liệu chưa được ghi nếu thao tác thất bại.
- Các nút `Hủy`, mũi tên chuyển tuần/tháng và chip lọc chỉ thay đổi giao diện hoặc bộ lọc, không ghi dữ liệu nghiệp vụ.
- Nhật ký hệ thống chỉ đọc. Mã ID nội bộ được lưu trong Firebase để truy vết nhưng không hiển thị trên giao diện người dùng.

### 4. Chức năng các nút trên giao diện Nhân viên

| Vị trí/nút | Chức năng |
| --- | --- |
| `Trang chủ` | Xem ca hôm nay, giờ vào/ra, trạng thái, tổng giờ tháng, số ngày đi làm và số lần đi trễ. |
| `Chấm công của tôi` | Xem lịch sử theo tháng, giờ làm, đi trễ/về sớm và trạng thái từng ngày. |
| Mũi tên trái/phải ở lịch sử | Chuyển tháng trước hoặc tháng sau. |
| `Gửi yêu cầu điều chỉnh` | Điều hướng sang khu `Đơn từ` để gửi đơn sửa công. |
| `Đơn từ` | Xem lịch sử đơn và khu đăng ký tăng ca. |
| `Tạo đơn` / `Đóng form` | Mở/đóng form tạo đơn thường. |
| Chip loại đơn | Chọn nghỉ phép, đi muộn, về sớm, sửa chấm công, ngoài văn phòng hoặc đổi ca. |
| `Gửi đơn` | Gửi đơn thường; lý do bắt buộc phải có. |
| `Gửi đăng ký tăng ca` | Gửi một đơn tăng ca cho ngày hợp lệ từ hôm nay trở đi, khung 17:30–20:30. |
| `Cá nhân` | Xem hồ sơ, mã nhân viên, phòng ban, email và trạng thái đăng ký vân tay. |
| `Đổi mật khẩu` | Mở form đổi mật khẩu. |
| `Đăng xuất` | Kết thúc phiên nhân viên. |

Nhân viên không có nút tự sửa lịch, tự sửa lương, tự duyệt đơn hoặc xóa dữ liệu chấm công. Các quyền đó thuộc Admin và các thay đổi công phải có lý do/audit theo Rules.

### 5. Luồng tính công, tăng ca và tiền thưởng

1. Firmware chỉ gửi raw scan `SCAN/PENDING` kèm thời gian UTC; firmware không quyết định vào hay ra theo mốc 12 giờ.
2. Cloud Function đọc lịch của ngày quét và ngày trước đó, dựng cửa sổ ca theo giờ bắt đầu/kết thúc và hỗ trợ ca qua ngày.
3. Lượt gần đầu ca được chọn làm `CHECK_IN`, lượt gần cuối ca được chọn làm `CHECK_OUT`; lượt kế tiếp khi phiên đang mở sẽ đóng phiên.
4. Hai lượt trong vòng **3 phút** bị coi là quét trùng; lượt ngoài thứ tự, ngoài lịch hoặc không có cửa sổ hợp lệ được giữ lại với trạng thái bất thường để tra cứu.
5. Nếu có chấm vào nhưng chưa chấm ra, hệ thống chỉ đánh dấu thiếu chấm ra sau cuối ca cộng grace mặc định 60 phút; không tự tạo giờ ra.
6. Ca chính được tính theo cặp vào/ra hợp lệ và lịch đã phân. Ca tăng ca chỉ được tính khi đơn đã `APPROVED` và có cặp quét hợp lệ trong 17:30–20:30.
7. Mỗi ca tăng ca hoàn thành cộng **50.000 đ**; nhân viên thuộc Top 3 số ca tăng ca và không đi muộn nhận thêm **500.000 đ/người**; mỗi lần đi muộn trừ **100.000 đ** khỏi khoản thưởng. Tổng thưởng không thấp hơn 0.
8. Lương giờ được tính từ đơn giá × tổng giờ (gồm giờ tăng ca), sau đó cộng thưởng tự động và trừ khoản khấu trừ Admin nhập. Phiếu lương được lưu thành snapshot.

### 6. Ánh xạ thao tác với dữ liệu Firebase

| Thao tác | Collection/dữ liệu chính | Ghi chú kiểm soát |
| --- | --- | --- |
| Đăng nhập/quyền | Firebase Auth, `users/{uid}` | Kiểm tra role, active, employeeId. |
| Hồ sơ nhân viên | `employees/{id}` | Chuyển nghỉ bằng `active = false`, không xóa lịch sử. |
| Lệnh vân tay | `deviceCommands/{deviceId}` và `devices/{deviceId}` | Thiết bị cập nhật trạng thái lệnh/heartbeat. |
| Raw/resolved attendance | `attendance/{eventId}` | Cùng một document được Cloud Function cập nhật in-place; client không được sửa/xóa. |
| Phiên phân giải | `attendanceSessions/{employeeId}_{scheduleDate}` | Chỉ backend truy cập. |
| Ca và lịch | `shifts/{id}`, `workSchedules/{employeeId}_{date}` | Lịch tuần chỉ chứa ca chính. |
| Đơn thường | `leaveRequests/{id}` | Duyệt/từ chối và lý do được ghi theo luồng review. |
| Đơn tăng ca | `overtimeRequests/{employeeId}_{workDate}` | Một đơn/ngày; review đồng thời ghi audit. |
| Điều chỉnh công | `attendanceAdjustments/{id}` | Append-only; lý do bắt buộc và có audit cặp. |
| Lương/KPI | `payroll/{id}` và dữ liệu attendance/request | Phiếu đã lưu giữ nguyên snapshot. |
| Audit | `audit_logs/{id}` | Nhật ký bất biến, dùng để tra cứu trách nhiệm. |

### 7. Kịch bản thao tác mẫu cho một tuần

**Trước 17:00 Chủ nhật:** Admin vào `Phân ca → Lịch → Phân cho nhân viên`, tích chọn nhân viên và các ngày, chọn `Ca sáng` hoặc `Ca chiều`, rồi bấm `Lưu phân ca`.

**Trong tuần:** Nhân viên quét vân tay khi vào/ra. Nếu muốn làm thêm, nhân viên vào `Đơn từ → Gửi đăng ký tăng ca`; Admin vào `Đơn từ`, lọc đơn tăng ca và bấm `Duyệt` hoặc `Từ chối` kèm lý do.

**Cuối tháng:** Admin mở `Hiệu suất` để kiểm tra KPI, mở `Lương` để chọn tháng và từng nhân viên, kiểm tra số giờ/thưởng/khấu trừ, sau đó bấm `Lưu phiếu`. Nếu có lỗi do quên chấm hoặc mất mạng, Admin vào `Chấm công → Điều chỉnh`, nhập dữ liệu và lý do trước khi lưu.

### 8. Phạm vi hiện tại và lưu ý vận hành

- README này mô tả giao diện và nghiệp vụ theo code hiện tại; các nút `Cài đặt` vẫn là placeholder cho phần cấu hình Firebase Settings cụ thể.
- Tăng ca không được seed trước vào lịch tuần. Mọi lượt tăng ca hợp lệ phụ thuộc vào đơn của nhân viên và trạng thái duyệt của Admin.
- `Đã duyệt` là trạng thái nghiệp vụ của đơn, không có nghĩa mọi lượt quét đều tự động thành một ca; hệ thống vẫn yêu cầu cặp vào/ra hợp lệ.
- Kiểm tra cục bộ hiện có `npm test --prefix firebase/functions` đạt 26/26; Gradle và Firestore Emulator còn phụ thuộc môi trường JDK/wrapper được nêu ở phần kiểm chứng bên dưới.
- Các thay đổi trong tài liệu này chỉ được thực hiện local trong worktree; chưa deploy Firebase và chưa `git push`.

## Chạy Android

1. Mở thư mục `ChamCongIoT` bằng Android Studio (JDK 17).
2. Tạo Firebase project, thêm Android app package `vn.chamcong.iot`.
3. Tải `google-services.json` vào `app/`.
4. Trong Firebase Authentication bật Email/Password và tạo tài khoản quản trị.
5. Trong Authentication bật cả **Email/Password** và **Anonymous**.
6. Tạo Firestore, deploy rules theo phần dưới, rồi Run app.

Repository hiện có script `gradlew`/`gradlew.bat` nhưng chưa kèm `gradle-wrapper.jar`. Android Studio có thể đồng bộ bằng Gradle đã cấu hình; nếu cần build từ terminal, tạo wrapper bằng `gradle wrapper --gradle-version 8.9`.

## Backend Firebase và kiểm chứng cục bộ

`firebase/functions/package.json` khai báo runtime Node.js 22. Luồng chấm công hiện yêu cầu Cloud Functions hoạt động; chỉ cấu hình Auth/Firestore như bản Spark prototype cũ sẽ để lượt mới ở `SCAN/PENDING`. Firmware gửi Firestore REST trực tiếp; các endpoint HTTPS cũ trong `index.js` vẫn khai báo secret `DEVICE_API_KEY`. Khi chuẩn bị triển khai, người vận hành cần kiểm tra cấu hình Functions, secret cho endpoint dùng đến và yêu cầu dịch vụ của project đích.

Chạy kiểm chứng trước khi triển khai (PowerShell, từ thư mục dự án):

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
Push-Location firebase/functions
npm test
Pop-Location
rg -n "localHour|tm_hour.*12|< 12|CHECK_IN.*12|CHECK_OUT.*12" firmware firebase/functions app/src/main/java
git diff --check
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`. `npm test` kiểm thử logic resolver/notification, không thay thế kiểm thử Firestore Rules trên emulator hoặc thiết bị thật. Cần xem từng kết quả `rg`: `< 128` và `<< 12` trong bộ chuyển UTF-8 của firmware không phải quyết định chấm công.

Task 8 chỉ cập nhật tài liệu và kiểm chứng cục bộ; không deploy, flash thiết bị hoặc thay đổi dữ liệu production. Việc triển khai Functions, Rules/indexes trong `firebase/`, cấu hình project và nạp firmware được để lại cho người vận hành sau khi kiểm chứng môi trường phù hợp. Bằng chứng và giới hạn của lần chạy này được ghi tại `.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/task-8-report.md`.

## Nạp firmware

Mở `firmware/esp8266_fingerprint/esp8266_fingerprint.ino` trong Arduino IDE, cài:

- ESP8266 board package
- Adafruit Fingerprint Sensor Library
- ArduinoJson

Điền Wi-Fi. `FIREBASE_API_KEY` và project ID đã được lấy từ cấu hình Android. Wiring mẫu:

| Linh kiện | ESP8266 |
|---|---|
| Sensor TX / RX | D5 / D6 |
| LED xanh | D1 |
| LED đỏ | D2 |
| Buzzer | D7 |

Nguồn cảm biến phải đúng thông số module và chung GND với ESP8266. Không kéo buzzer công suất trực tiếp từ GPIO; dùng transistor và diode bảo vệ.

## Đăng ký vân tay từ app

1. Deploy lại Functions và Firestore Rules sau mỗi lần cập nhật backend.
2. Nạp firmware mới và bảo đảm `DEVICE_ID` trên ESP trùng mã thiết bị trong app (mặc định `GATE-01`).
3. Trong app mở **Nhân viên → +**, nhập thông tin và nhấn **Lưu & đăng ký vân tay**.
4. Trong tối đa vài giây ESP phát tiếng bíp. Đặt một ngón tay lên cảm biến, nhấc ra khi có bíp, rồi đặt lại đúng ngón đó lần hai.
5. LED xanh/bíp ngắn nghĩa là thành công; LED đỏ nghĩa là hết thời gian hoặc hai lần quét không khớp.

App tạo lệnh tại `deviceCommands/{deviceId}`; ESP đọc và cập nhật lệnh trực tiếp bằng Firestore REST. Khi trạng thái thành `COMPLETED`, app tự cập nhật `fingerprintTemplateId` của nhân viên.

Firmware đang dùng `setInsecure()` để bản mẫu dễ chạy. Trước khi triển khai thật, thay bằng CA certificate pinning, đổi API key định kỳ, giới hạn tốc độ theo `deviceId`, và tốt hơn là ký HMAC từng request kèm timestamp/nonce.

## Cấu trúc Firestore

- `employees/{id}`: mã, họ tên, phòng ban, email, `fingerprintTemplateId`, trạng thái.
- `attendance/{eventId}`: giữ nguyên định danh sự kiện, thiết bị, thời điểm quét NTP UTC và provenance raw scan; Cloud Function cập nhật in place kết quả phân giải theo ca (`CHECK_IN`/`CHECK_OUT`, `scheduleDate`, `resolutionStatus`) trên cùng document.
- `attendanceSessions/{employeeId}_{scheduleDate}`: trạng thái phiên phân giải của backend, cấm client đọc/ghi.
- `attendanceAdjustments/{id}`: điều chỉnh append-only theo nhân viên/ngày ca, lý do bắt buộc và audit `ATTENDANCE_ADJUST` cùng ID. Index truy vấn: `employeeId ASC`, `scheduleDate ASC`, `createdAt DESC`.
- `overtimeRequests/{employeeId}_{workDate}`: đơn tăng ca một đơn/ngày, khung cố định 17:30–20:30, trạng thái `PENDING`/`APPROVED`/`REJECTED`, người duyệt và lý do từ chối.
- `payroll/{id}`: lương cơ bản đã tính theo giờ, đơn giá/giờ, số giờ làm, thưởng, khấu trừ theo kỳ.
- `performanceReviews/{id}`: kỳ đánh giá, điểm, nhận xét.
- `notifications/{id}`: thông báo nội bộ.
- `devices/{id}`: snapshot heartbeat, trạng thái, firmware, số mẫu và capability thiết bị (không lưu plaintext secret).
- `users/{uid}`: email, displayName, role `ADMIN`/`EMPLOYEE`, trạng thái active; tài khoản Nhân viên có thêm `employeeId` để liên kết đúng hồ sơ.
- `departments/{id}`: phòng ban do admin quản lý.
- `settings/{id}`: cấu hình dùng chung do admin quản lý.
- `audit_logs/{id}`: nhật ký bất biến của các thao tác quản trị.

## Phần tiếp theo nên làm

MVP đã có đăng nhập, dashboard, danh sách/thêm nhân viên, feed chấm công realtime, mô hình lương–hiệu suất, FCM và firmware nhận dạng/gửi kết quả. Các module ca/lịch, báo cáo, audit, bảo mật role, offline outbox và KPI tăng ca/đi muộn đã được bổ sung. Phần còn có thể mở rộng là tích hợp email/Zalo, tự động gửi báo cáo cuối tháng và hoàn thiện khu cấu hình doanh nghiệp trong `Cài đặt`.

## Thiết kế MVVM và nhật ký file

App được triển khai bằng Kotlin + Jetpack Compose theo MVVM:

- Composable chỉ hiển thị state và phát sự kiện.
- `MainViewModel` điều phối `StateFlow`, đăng nhập, quan sát realtime và các thao tác ghi.
- `FirebaseRepository` là lớp duy nhất gọi Firebase; không gọi Firebase trực tiếp từ UI.
- Model/domain chứa các quy tắc thuần, được kiểm thử bằng unit test.

Spec thiết kế của lát MVP quản trị: [`docs/superpowers/specs/2026-09-13-mvvm-admin-mvp-design.md`](docs/superpowers/specs/2026-09-13-mvvm-admin-mvp-design.md).

Kế hoạch triển khai: [`docs/superpowers/plans/2026-09-13-mvvm-admin-mvp.md`](docs/superpowers/plans/2026-09-13-mvvm-admin-mvp.md). Kế hoạch chia code theo các lát MVVM, mỗi lát có test trước implementation và build kiểm tra.

Mọi lần thay đổi code phải cập nhật mục này, ghi rõ file đã sửa hoặc tạo. Lát tài liệu ngày 13/09/2026 đã:

- Tạo `docs/superpowers/specs/2026-09-13-mvvm-admin-mvp-design.md`: đặc tả MVVM, UI, dữ liệu, bảo mật và kiểm thử cho MVP quản trị.
- Tạo `docs/superpowers/plans/2026-09-13-mvvm-admin-mvp.md`: kế hoạch triển khai theo task, interface, test và lệnh build.
- Sửa `gradle.properties`: cho phép Android Gradle Plugin build trong workspace có đường dẫn Unicode hiện tại.
- Tạo `local.properties` (đã bị `.gitignore` loại trừ): trỏ Gradle tới Android SDK local để chạy build/test trên máy này.
- Tạo `app/src/main/java/vn/chamcong/iot/model/DashboardModels.kt`: model `DashboardSummary` và dữ liệu biểu đồ theo tuần.
- Tạo `app/src/main/java/vn/chamcong/iot/domain/DashboardRules.kt`: tính summary dashboard bằng hàm domain thuần.
- Tạo `app/src/main/java/vn/chamcong/iot/domain/FilterRules.kt`: lọc nhân viên và lượt chấm công.
- Tạo `app/src/test/java/vn/chamcong/iot/domain/DashboardRulesTest.kt`: kiểm thử summary nhân viên, đi trễ và chưa chấm ra.
- Tạo `app/src/test/java/vn/chamcong/iot/domain/FilterRulesTest.kt`: kiểm thử bộ lọc theo tên/mã/phòng ban/trạng thái.
- Tạo `app/src/main/java/vn/chamcong/iot/model/DeviceModels.kt`: snapshot thiết bị, heartbeat online/offline và capability.
- Sửa `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`: thêm luồng realtime `observeDevices()` từ collection `devices`.
- Tạo `app/src/test/java/vn/chamcong/iot/model/DeviceModelsTest.kt`: kiểm thử heartbeat quá hạn và thiết bị thiếu heartbeat.
- Sửa `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`: thêm dashboard/device/filter state, intent MVVM và subscription realtime thiết bị.
- Sửa `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`: chuẩn bị shell đọc state từ ViewModel, giữ UI không gọi Firebase.
- Tạo `app/src/main/java/vn/chamcong/iot/ui/dashboard/DashboardScreen.kt`: dashboard cards, biểu đồ tuần được chọn, trạng thái thiết bị và lượt chấm mới nhất.
- Sửa `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`: thêm destination shell MVVM, điều hướng tổng quan/nhân viên/chấm công/lương/hiệu suất.
- Tạo `app/src/main/java/vn/chamcong/iot/ui/employees/EmployeesScreen.kt`: search, lọc phòng ban/trạng thái và callback thao tác nhân viên.
- Tạo `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt`: bộ lọc trạng thái/loại lượt chấm và danh sách realtime.
- Sửa `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`: tách màn hình Nhân viên/Chấm công và giữ dialog nghiệp vụ ở shell.
- Sửa `app/src/main/java/vn/chamcong/iot/model/DeviceModels.kt`: thêm nhãn trạng thái lệnh enrollment/deletion và quy tắc heartbeat.
- Tạo `app/src/main/java/vn/chamcong/iot/ui/devices/DevicesScreen.kt`: trạng thái online/offline/unknown, heartbeat, firmware, dung lượng và lệnh thiết bị.
- Sửa `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`: nối màn hình Thiết bị vào navigation.
- Sửa `app/src/test/java/vn/chamcong/iot/model/DeviceModelsTest.kt`: kiểm thử nhãn lệnh đang xử lý và xóa vân tay thất bại.
- Tạo `app/src/main/java/vn/chamcong/iot/model/FingerprintRules.kt`: tìm lại vị trí vân tay từ `fingerprintMappings` cho hồ sơ nhân viên cũ bị thiếu slot.
- Tạo `app/src/test/java/vn/chamcong/iot/model/FingerprintRulesTest.kt`: kiểm thử ưu tiên slot đã lưu và fallback mapping cũ.
- Sửa `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`: khi xóa nhân viên, tra cứu và vô hiệu hóa cả mapping vân tay cũ còn sót trên Firebase.
- Sửa `firmware/esp8266_fingerprint/esp8266_fingerprint.ino`: từ chối chấm công nếu mapping không có `enabled=true`, kể cả khi template vật lý chưa xóa khỏi AS608.
- Sửa `firebase/firestore.rules`: thiết bị anonymous chỉ được tạo attendance khi mapping đang bật và nhân viên còn `active=true`.
- Sửa `app/src/main/java/vn/chamcong/iot/model/Models.kt`: thêm `hourlyRate` và `hoursWorked` vào phiếu lương; `baseSalary` của phiếu là tiền cơ bản sau khi nhân giờ.
- Sửa `app/src/main/java/vn/chamcong/iot/model/PersonnelRules.kt`: tính tiền lương theo giờ, ghép cặp vào/ra để tính giờ và lọc nhân viên đã nghỉ đã có phiếu theo tháng.
- Tạo `app/src/test/java/vn/chamcong/iot/model/PayrollRulesTest.kt`: kiểm thử tiền cơ bản theo giờ, giờ làm theo cặp chấm công và danh sách nhân viên cần lập phiếu.
- Sửa `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`: nhận số giờ làm khi lưu phiếu lương.
- Sửa `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`: truyền số giờ làm vào luồng lưu phiếu lương MVVM.
- Sửa `app/src/main/java/vn/chamcong/iot/ui/PayrollScreen.kt`: nhập đơn giá/giờ, tự tính giờ từ attendance, cho chỉnh số giờ và ẩn nhân viên đã nghỉ sau khi đã lập phiếu tháng.
- Sửa `firmware/esp8266_fingerprint/esp8266_fingerprint.ino`: gửi snapshot `devices/GATE-01` lúc khởi động và heartbeat mỗi 30 giây; in version `snapshot-2-offline` và lý do bỏ qua nếu chưa đủ Wi-Fi/NTP/Auth/HTTPS.
- Sửa `firebase/firestore.rules`: cho phép thiết bị anonymous ghi snapshot đúng document của mình với các field giới hạn.
- Sửa `README.md`: ghi kiến trúc MVVM, đường dẫn spec và quy tắc nhật ký file.

### Bổ sung nhật ký file: phân giải theo ca và điều chỉnh (17/09/2026)

Đối chiếu thay đổi của Tasks 1–8 từ base `2c5c2f6`; mỗi file source/test/rules/firmware/functions thay đổi được liệt kê dưới đây. Không tạo README riêng cho Functions.

| File | Thay đổi |
| --- | --- |
| `app/src/main/java/vn/chamcong/iot/model/AttendanceResolutionModels.kt` | Tạo enum phân giải, cặp chấm công và adjustment với `Instant`, không phụ thuộc Firebase. |
| `app/src/main/java/vn/chamcong/iot/model/Models.kt` | Thêm `SCAN`, loại bất thường và metadata phân giải attendance. |
| `app/src/main/java/vn/chamcong/iot/model/SchedulingModels.kt` | Thêm thời gian chờ thiếu checkout mặc định 60 phút cho ca. |
| `app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt` | Tạo cửa sổ ca, ghép cặp accepted/verified, chọn adjustment mới nhất, hạn checkout và gán legacy scan một lần. |
| `app/src/main/java/vn/chamcong/iot/domain/SchedulingRules.kt` | Kiểm tra adjustment/ca qua đêm; tính giờ và tổng tuần theo cặp hiệu lực/ngày ca. |
| `app/src/main/java/vn/chamcong/iot/domain/PresenceRules.kt` | Hiện diện theo cặp điều chỉnh và hạn cuối ca, giữ trạng thái bất thường. |
| `app/src/main/java/vn/chamcong/iot/domain/ReportRules.kt` | Báo cáo dùng ngày ca, cặp hiệu lực và adjustment. |
| `app/src/main/java/vn/chamcong/iot/domain/EmployeeRules.kt` | Tổng ngày nhân viên dùng ca và adjustment, bỏ lượt không được xác minh/chấp nhận. |
| `app/src/main/java/vn/chamcong/iot/model/PersonnelRules.kt` | Tính giờ lương từ cặp phân giải và adjustment, giữ fallback legacy. |
| `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt` | Parse metadata/default ca; listener adjustment và batch adjustment/audit có actor, lý do, before/after. |
| `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt` | State/listener adjustment theo vai trò, intent lưu và truyền dữ liệu tới các phép tính. |
| `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt` | Hiển thị nhãn phân giải, giờ Việt Nam và callback điều chỉnh Admin trên lịch sử. |
| `app/src/main/java/vn/chamcong/iot/ui/PayrollScreen.kt` | Truyền lịch, ca và adjustment khi tính giờ lập phiếu. |
| `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeAttendanceScreen.kt` | Tổng chấm công nhân viên dùng lịch, ca và adjustment. |
| `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt` | Nối thao tác/dialog điều chỉnh và tạo giá trị hiệu lực từ state đã tải. |
| `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceAdjustmentDialog.kt` | Tạo dialog lý do/giờ, giữ giá trị hiện tại và xử lý saving/error. |
| `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceAdjustmentInput.kt` | Tạo parser ngày giờ Việt Nam, validate mốc giờ, giờ công và lý do. |
| `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceRowPresentation.kt` | Tạo nhãn phân giải gồm type thực tế từ server và quy tắc ngày mục tiêu điều chỉnh. |
| `app/src/test/java/vn/chamcong/iot/domain/AttendanceAdjustmentRulesTest.kt` | Tạo test validation, chọn adjustment mới nhất, giữ raw scan và ảnh hưởng tới hiện diện/tổng giờ. |
| `app/src/test/java/vn/chamcong/iot/domain/AttendanceResolutionRulesTest.kt` | Tạo test ghép cặp, ca qua đêm, grace và ưu tiên adjustment. |
| `app/src/test/java/vn/chamcong/iot/domain/SchedulingRulesTest.kt` | Bổ sung ca qua đêm, mốc ngày ca và tính giờ hiệu lực. |
| `app/src/test/java/vn/chamcong/iot/domain/PresenceRulesTest.kt` | Bổ sung hạn checkout, adjustment và loại trừ lượt không hợp lệ. |
| `app/src/test/java/vn/chamcong/iot/domain/ReportRulesTest.kt` | Bổ sung báo cáo theo ca/adjustment và tránh tính trùng legacy scan. |
| `app/src/test/java/vn/chamcong/iot/domain/EmployeeRulesTest.kt` | Bổ sung tổng ngày theo cặp hiệu lực và lượt được xác minh. |
| `app/src/test/java/vn/chamcong/iot/model/PayrollRulesTest.kt` | Bổ sung giờ lương theo ca, adjustment, legacy và loại trừ unverified. |
| `app/src/test/java/vn/chamcong/iot/ui/attendance/AttendanceAdjustmentInputTest.kt` | Tạo test parser, ca qua đêm, giờ 0 và validation đầu vào. |
| `app/src/test/java/vn/chamcong/iot/ui/attendance/AttendanceRowPresentationTest.kt` | Tạo test nhãn gồm server-shaped rejection, ngày ca và giá trị hiệu lực khi mở dialog. |
| `firebase/firestore.rules` | Chỉ cho device tạo raw scan hợp lệ; cấm client sửa attendance/session; tạo adjustment Admin kèm audit và cấm sửa/xóa lịch sử. |
| `firebase/firestore.indexes.json` | Thêm index adjustment theo nhân viên, ngày ca và thời gian giảm dần. |
| `firebase/functions/attendanceResolver.js` | Tạo resolver thuần theo ca, múi giờ, cửa sổ trùng, phiên và mapping active. |
| `firebase/functions/attendanceNotification.js` | Tạo điều kiện thông báo khi chuyển từ trạng thái khác sang accepted, có kiểm tra cấu trúc/trường trạng thái. |
| `firebase/functions/index.js` | Endpoint tạo raw scan; trigger phân giải transaction in place và trigger thông báo khi update. |
| `firebase/functions/package.json` | Thêm script `npm test` chạy `node --test`. |
| `firebase/functions/test/attendanceResolver.test.js` | Tạo test cửa sổ ca/grace, mapping, trùng, thứ tự và đóng phiên. |
| `firebase/functions/test/attendanceNotification.test.js` | Tạo test chuyển trạng thái được thông báo và từ chối dữ liệu không hợp lệ. |
| `firmware/esp8266_fingerprint/esp8266_fingerprint.ino` | Gửi `SCAN/PENDING`, bỏ phân loại theo giờ, giữ event ID/outbox/NTP UTC và hiển thị xác nhận gửi lượt. |
| `README.md` | Sửa kiến trúc/backend, tài liệu workflow theo ca, audit, kiểm chứng và nhật ký đầy đủ. |
| `.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/run-task6-tests.ps1` | Runner JUnit trực tiếp dùng classpath cache trên máy, hỗ trợ `-All` cho kiểm chứng fallback. |
| `.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/task-6-report.md` | Bằng chứng triển khai và sửa sau review Task 6. |
| `.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/task-7-report.md` | Bằng chứng UI điều chỉnh và sửa nhãn sự kiện server Task 7. |
| `.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/task-8-report.md` | Bằng chứng kiểm chứng cuối, commit tài liệu, APK và các bước triển khai cố ý bỏ qua. |

### Nhật ký file: final review fix wave (17/09/2026)

Các file thay đổi so với `80b5d36` trong đợt sửa cuối:

| File | Thay đổi |
| --- | --- |
| `firebase/functions/index.js` | Ưu tiên ID snapshot nhân viên sau data; hỗ trợ document Android có `id=""`. |
| `firebase/functions/test/employeeMapping.test.js` | Chạy handler thật với transaction giả ở biên Firestore, kiểm tra ID canonical và tạo raw scan. |
| `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt` | Listener Admin lấy toàn bộ lịch, không lọc theo tuần. |
| `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt` | Giữ lịch khi đổi tuần; dashboard tính từ state hiện tại, cập nhật cả adjustment/ca/lịch; truyền context vào bộ lọc. |
| `app/src/main/java/vn/chamcong/iot/ui/PayrollScreen.kt` | Giờ tự tính phản ánh context mới đến khi người dùng nhập override. |
| `app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt` | Điều kiện accepted dùng chung, loại trạng thái sai, xử lý duplicate và tính phút trễ theo ca. |
| `app/src/main/java/vn/chamcong/iot/domain/PresenceRules.kt` | Dùng điều kiện accepted/bất thường chung; duplicate không ghi đè hiện diện. |
| `app/src/main/java/vn/chamcong/iot/domain/EmployeeRules.kt` | Dùng trạng thái chung và deadline checkout; clock mặc định cho tổng ngày/tháng; giữ timestamp tạo đơn bằng default model. |
| `app/src/main/java/vn/chamcong/iot/domain/ReportRules.kt` | Truyền clock mặc định vào tổng ngày để báo cáo phản ánh deadline. |
| `app/src/main/java/vn/chamcong/iot/domain/SchedulingRules.kt` | Nghỉ phép đúng nhân viên; neo nghỉ sau nửa đêm vào cửa sổ ca. |
| `app/src/main/java/vn/chamcong/iot/domain/DashboardRules.kt` | Đếm accepted/cặp hiệu lực, ngày ca, đi trễ và adjustment thay cho status raw. |
| `app/src/main/java/vn/chamcong/iot/domain/FilterRules.kt` | Lọc/hiển thị trạng thái hiệu lực theo ca, giữ thứ tự và không sửa raw data. |
| `app/src/main/java/vn/chamcong/iot/model/EmployeeModels.kt` | Thêm trạng thái `PRESENT` cho cặp đang mở trước deadline. |
| `app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeHomeScreen.kt` | Nhãn tiếng Việt cho trạng thái đang làm việc. |
| `app/src/test/java/vn/chamcong/iot/domain/FinalReviewRulesTest.kt` | Hồi quy rejection server, trạng thái sai/default legacy, deadline ngày/đêm, phép hai nhân viên, nghỉ giữa ca. |
| `app/src/test/java/vn/chamcong/iot/ui/FinalReviewStateTest.kt` | Lịch ngoài tuần, qua tháng, dashboard/filter và cập nhật theo adjustment/ca/lịch. |
| `firebase/firestore.rules` | Kiểm tra grace tùy chọn: integer >= 0. |
| `firebase/test/shiftRules.test.js` | Emulator local riêng: Admin create/update với omitted, zero, positive, negative, fraction, string, null. |
| `README.md` | Cập nhật hành vi và inventory đợt sửa cuối. |
| `.superpowers/sdd/2026-09-17-attendance-resolution-adjustment/final-fix-report.md` | Kết quả RED/GREEN, lệnh kiểm chứng, commit và giới hạn. |

Chạy kiểm tra rules riêng bằng `node --test firebase/test/shiftRules.test.js` sau khi khởi động Firestore emulator tại `127.0.0.1:8085`, project `demo-attendance-final-fix`, nạp `firebase/firestore.rules`. Script chỉ dùng endpoint local cố định. `npm test` trong `firebase/functions` vẫn chạy độc lập, không cần emulator. Lệnh khởi động emulator, kết quả chính xác và giới hạn được ghi trong báo cáo cuối.

## Quản lý nhân viên, vân tay và lương

- Khi thêm nhân viên, app tự cấp mã `NV0001`, `NV0002`… trong giao dịch Firestore. Cả “Chỉ lưu nhân viên” và “Lưu & đăng ký vân tay” đều dùng cùng bộ đếm. Mã cũ được giữ nguyên; mã NV có sẵn và nhân viên đã nghỉ vẫn được xét để tránh cấp lại. Cập nhật toàn bộ app quản trị sang bản mới trước khi thêm nhân viên; bản cũ còn nhập mã thủ công không tham gia bộ đếm.
- Nhân viên → **Thiết lập lương** để đặt đơn giá lương cơ bản theo giờ.
- Lương → nhập tháng `yyyy-MM` → **Lập phiếu lương / thiết lập lương** → chọn nhân viên → kiểm tra số giờ tự tính từ cặp vào/ra (có thể chỉnh) → nhập thưởng, khấu trừ → lưu. Công thức: `lương cơ bản = đơn giá/giờ × số giờ làm`; `thực lĩnh = lương cơ bản + thưởng − khấu trừ`.
- Mỗi nhân viên có một phiếu mỗi tháng. Phiếu lưu mã/tên, đơn giá/giờ, số giờ và tiền tại thời điểm tạo. Nhân viên đã nghỉ vẫn xuất hiện để lập phiếu tháng cuối; sau khi tháng đó có phiếu, nhân viên tự biến mất khỏi danh sách chọn của tháng đó. Lịch sử phiếu vẫn giữ nguyên.
- **Xóa nhân viên** chuyển hồ sơ sang `active=false`, ẩn khỏi danh sách đang làm; bật **Hiện nhân viên đã nghỉ** để tra cứu. Không xóa chấm công hoặc phiếu lương.
- **Xóa vân tay** gửi `DELETE_FINGERPRINT` đến thiết bị đã đăng ký, vô hiệu liên kết chấm công và chờ cảm biến xác nhận xóa. Lệnh thất bại giữ vị trí mẫu để có thể gửi lại, tránh cấp nhầm cho nhân viên khác. Chỉ tái sử dụng vị trí sau khi xóa thành công.
- Giữ app mở/kết nối mạng để đồng bộ kết quả thiết bị; nếu đóng app, mở lại để hoàn tất. Lệnh đang xử lý không được ghi đè. Thiết bị khởi động lại giữa đăng ký sẽ báo thất bại; xóa mẫu đang chờ trước khi đăng ký lại.
- KPI tăng ca và đi muộn được tính tự động theo tháng trong Payroll/Performance: chỉ ca tăng ca `APPROVED` hoàn thành mới được tính, còn `deduction` vẫn do Admin nhập riêng.

## Nhật ký triển khai tạo tài khoản nhân viên (14/09/2026)

- Form **Thêm nhân viên** đã có tùy chọn **Tạo tài khoản đăng nhập cho nhân viên**. Admin nhập email, mật khẩu và xác nhận mật khẩu ngay khi lưu hồ sơ.
- Khi bật tùy chọn này, app lưu hồ sơ nhân viên trước, tạo tài khoản Email/Password trong Firebase Auth bằng Firebase App phụ để không đăng xuất phiên Admin, sau đó tạo `users/{uid}` với `role=EMPLOYEE`, `active=true` và `employeeId` trỏ đúng ID document trong `employees`.
- Mật khẩu chỉ gửi trực tiếp cho Firebase Auth, không lưu vào Firestore và không xuất hiện trong log. Nếu tạo profile thất bại sau khi Auth đã tạo, app cố gắng xóa tài khoản Auth vừa tạo để tránh tài khoản mồ côi.
- Tài khoản nhân viên đăng nhập tại màn hình đăng nhập chung bằng email/mật khẩu. Sau khi kiểm tra profile, app mở shell Nhân viên gồm Trang chủ, Chấm công của tôi, Đơn từ và Cá nhân.
- Khi Admin chuyển nhân viên sang **Đã nghỉ**, các profile tài khoản liên kết được chuyển `active=false`; nhân viên không thể tiếp tục truy cập dữ liệu nghiệp vụ.
- Firestore Rules chỉ cho Admin tạo/cập nhật profile có `role=EMPLOYEE`, không cho client tạo hoặc nâng tài khoản lên Admin.
- File tạo mới: `app/src/main/java/vn/chamcong/iot/model/EmployeeAccountModels.kt`, `app/src/main/java/vn/chamcong/iot/domain/EmployeeAccountRules.kt`, `app/src/test/java/vn/chamcong/iot/domain/EmployeeAccountRulesTest.kt`.
- File đã sửa: `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`, `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`, `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`, `app/src/main/java/vn/chamcong/iot/model/AuditModels.kt`, `firebase/firestore.rules` và README này.
- Cách sử dụng: Admin vào **Nhân viên → +**, nhập họ tên/email/phòng ban, tích **Tạo tài khoản đăng nhập**, nhập mật khẩu ít nhất 6 ký tự, rồi chọn **Chỉ lưu nhân viên** hoặc **Lưu & đăng ký vân tay**. Gửi email và mật khẩu cho nhân viên đăng nhập lần đầu; nhân viên có thể đổi mật khẩu sau khi đăng nhập.
- Kiểm chứng sau cập nhật: 58 unit test, 0 failure, 0 error, 0 skipped; `:app:testDebugUnitTest` và `:app:assembleDebug` đều thành công.
