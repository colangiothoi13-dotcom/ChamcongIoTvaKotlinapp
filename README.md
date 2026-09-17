# Chấm công IoT

MVP quản trị nhân sự và chấm công bằng vân tay gồm:

- Android: Kotlin, Jetpack Compose, Firebase Auth, Firestore, FCM và WorkManager.
- Thiết bị: ESP8266 + cảm biến AS608/R307, LED xanh/đỏ và buzzer.
- Backend Spark miễn phí: Firebase Anonymous Auth + Firestore REST dành cho ESP8266.

## Kiến trúc

```text
Ngón tay -> AS608/R307 (đối chiếu cục bộ)
                    |
                    v
ESP8266 --Anonymous Auth/HTTPS--> Firestore <--realtime--> Android
   |
 LED/còi
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
  - Nếu hợp lệ, thiết bị ghi sự kiện chấm công với thời gian hiện tại.
  - App Android theo dõi realtime collection `attendance` để cập nhật trạng thái chấm công ngay trên dashboard và màn hình chấm công.
- Nếu không khớp hoặc mapping đã bị vô hiệu hóa:
  - Hệ thống từ chối xác thực.
  - Không ghi sự kiện attendance, tránh vi phạm/ghi nhầm người khác.

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

- Admin có thể tạo ca làm, lịch làm theo tuần, phân ca cho từng nhân viên hoặc phòng ban.
- Hệ thống hỗ trợ các loại ca như sáng, tối, bổ sung.
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

## Chạy Android

1. Mở thư mục `ChamCongIoT` bằng Android Studio (JDK 17).
2. Tạo Firebase project, thêm Android app package `vn.chamcong.iot`.
3. Tải `google-services.json` vào `app/`.
4. Trong Firebase Authentication bật Email/Password và tạo tài khoản quản trị.
5. Trong Authentication bật cả **Email/Password** và **Anonymous**.
6. Tạo Firestore, deploy rules theo phần dưới, rồi Run app.

Repository hiện có script `gradlew`/`gradlew.bat` nhưng chưa kèm `gradle-wrapper.jar`. Android Studio có thể đồng bộ bằng Gradle đã cấu hình; nếu cần build từ terminal, tạo wrapper bằng `gradle wrapper --gradle-version 8.9`.

## Deploy Firebase trên gói Spark

Yêu cầu Node.js 22 và Firebase CLI:

```bash
firebase login
firebase use YOUR_PROJECT_ID
firebase deploy --only firestore --project chamcongiot-56ae5
```

Không cần Cloud Functions, Secret Manager, Blaze hay custom claim. Trong bản prototype, tài khoản Email/Password là quản trị và tài khoản Anonymous là thiết bị. Trước khi dùng thực tế nên chuyển sang backend xác thực thiết bị riêng.

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
- `payroll/{id}`: lương cơ bản đã tính theo giờ, đơn giá/giờ, số giờ làm, thưởng, khấu trừ theo kỳ.
- `performanceReviews/{id}`: kỳ đánh giá, điểm, nhận xét.
- `notifications/{id}`: thông báo nội bộ.
- `devices/{id}`: snapshot heartbeat, trạng thái, firmware, số mẫu và capability thiết bị (không lưu plaintext secret).
- `users/{uid}`: email, displayName, role `ADMIN`/`EMPLOYEE`, trạng thái active; tài khoản Nhân viên có thêm `employeeId` để liên kết đúng hồ sơ.
- `departments/{id}`: phòng ban do admin quản lý.
- `settings/{id}`: cấu hình dùng chung do admin quản lý.
- `audit_logs/{id}`: nhật ký bất biến của các thao tác quản trị.

## Phần tiếp theo nên làm

MVP đã có đăng nhập, dashboard, danh sách/thêm nhân viên, feed chấm công realtime, mô hình lương–hiệu suất, FCM và firmware nhận dạng/gửi kết quả. Các module ca/lịch, báo cáo, audit, bảo mật role và offline outbox đã được bổ sung; KPI tự động, email/Zalo và tự động báo cáo cuối tháng vẫn là phần mở rộng.

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

## Quản lý nhân viên, vân tay và lương

- Khi thêm nhân viên, app tự cấp mã `NV0001`, `NV0002`… trong giao dịch Firestore. Cả “Chỉ lưu nhân viên” và “Lưu & đăng ký vân tay” đều dùng cùng bộ đếm. Mã cũ được giữ nguyên; mã NV có sẵn và nhân viên đã nghỉ vẫn được xét để tránh cấp lại. Cập nhật toàn bộ app quản trị sang bản mới trước khi thêm nhân viên; bản cũ còn nhập mã thủ công không tham gia bộ đếm.
- Nhân viên → **Thiết lập lương** để đặt đơn giá lương cơ bản theo giờ.
- Lương → nhập tháng `yyyy-MM` → **Lập phiếu lương / thiết lập lương** → chọn nhân viên → kiểm tra số giờ tự tính từ cặp vào/ra (có thể chỉnh) → nhập thưởng, khấu trừ → lưu. Công thức: `lương cơ bản = đơn giá/giờ × số giờ làm`; `thực lĩnh = lương cơ bản + thưởng − khấu trừ`.
- Mỗi nhân viên có một phiếu mỗi tháng. Phiếu lưu mã/tên, đơn giá/giờ, số giờ và tiền tại thời điểm tạo. Nhân viên đã nghỉ vẫn xuất hiện để lập phiếu tháng cuối; sau khi tháng đó có phiếu, nhân viên tự biến mất khỏi danh sách chọn của tháng đó. Lịch sử phiếu vẫn giữ nguyên.
- **Xóa nhân viên** chuyển hồ sơ sang `active=false`, ẩn khỏi danh sách đang làm; bật **Hiện nhân viên đã nghỉ** để tra cứu. Không xóa chấm công hoặc phiếu lương.
- **Xóa vân tay** gửi `DELETE_FINGERPRINT` đến thiết bị đã đăng ký, vô hiệu liên kết chấm công và chờ cảm biến xác nhận xóa. Lệnh thất bại giữ vị trí mẫu để có thể gửi lại, tránh cấp nhầm cho nhân viên khác. Chỉ tái sử dụng vị trí sau khi xóa thành công.
- Giữ app mở/kết nối mạng để đồng bộ kết quả thiết bị; nếu đóng app, mở lại để hoàn tất. Lệnh đang xử lý không được ghi đè. Thiết bị khởi động lại giữa đăng ký sẽ báo thất bại; xóa mẫu đang chờ trước khi đăng ký lại.
- KPI chưa tự tính. Công thức chuyên cần đề xuất được giải thích trong tab Hiệu suất; chưa có lịch làm theo tháng để áp dụng.

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
