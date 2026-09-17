# Chấm công IoT

MVP quản trị nhân sự và chấm công bằng vân tay gồm:

- Android: Kotlin, Jetpack Compose, Firebase Auth, Firestore, FCM và WorkManager.
- Thiết bị: ESP8266 + cảm biến AS608/R307, LED xanh/đỏ và buzzer.
- Backend: Firebase Anonymous Auth + Firestore REST dành cho ESP8266; Cloud Functions phân giải chấm công theo lịch ca.

## Kiến trúc

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

Android dùng adjustment hợp lệ mới nhất theo `createdAt` của nhân viên/ngày ca cùng các lượt `verified=true`, `ACCEPTED`, loại vào/ra để tính cặp hiệu lực cho hiện diện, báo cáo, màn hình nhân viên và giờ công khi lập phiếu lương. `workedHoursOverride` mới ưu tiên hơn override cũ trên lịch; phiếu lương đã lưu không tự được viết lại. Lượt legacy chưa có `scheduleDate` được gán một lần theo các lịch đã tải, ưu tiên ngày tường minh; không có ca thì dùng ngày địa phương. Toàn bộ phép tính dựa trên dữ liệu đã tải, không tự backfill lịch sử hoặc sửa raw scans.

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
