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
- `attendance/{eventId}`: nhân viên, thiết bị, thời điểm server, check-in/out, đúng giờ/trễ.
- `payroll/{id}`: lương cơ bản đã tính theo giờ, đơn giá/giờ, số giờ làm, thưởng, khấu trừ theo kỳ.
- `performanceReviews/{id}`: kỳ đánh giá, điểm, nhận xét.
- `notifications/{id}`: thông báo nội bộ.
- `devices/{id}`: snapshot heartbeat, trạng thái, firmware, số mẫu và capability thiết bị (không lưu plaintext secret).
- `users/{uid}`: email, displayName, role `ADMIN`/`EMPLOYEE`, trạng thái active.
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

### Cập nhật và kiểm tra

1. Nạp lại `firmware/esp8266_fingerprint/esp8266_fingerprint.ino` cho NodeMCU ESP8266 để có lệnh xóa vân tay, heartbeat snapshot và hàng đợi offline. Sau khi khởi động, Serial Monitor 115200 phải có `FW: snapshot-2-offline` và `HEARTBEAT HTTP 200` khi có mạng; nếu không, log sẽ chỉ rõ lỗi Wi-Fi/NTP/Auth/HTTPS.
2. Cài APK mới: `app/build/outputs/apk/debug/app-debug.apk`.
3. Source Rules đã được cập nhật; phiên này chưa deploy lên Firebase. Sau khi kiểm tra đúng project, chạy `firebase deploy --only firestore:rules --project chamcongiot-56ae5`. Rules cho phép anonymous device tạo/cập nhật `devices/GATE-01` bằng các field snapshot giới hạn.
4. Mở app sau khi ESP8266 chạy khoảng 5–30 giây; Dashboard/Thiết bị phải hiển thị `GATE-01`, firmware `snapshot-2-offline` và heartbeat.
5. Thử thêm hai nhân viên, kiểm tra mã khác nhau; đặt đơn giá/giờ, lập phiếu với số giờ, thưởng và khấu trừ; kiểm tra công thức và phiếu cũ giữ nguyên.
6. Đăng ký một mẫu thử → xóa vân tay → đợi trạng thái Hoàn tất → quét lại phải không được nhận diện. Nếu template vẫn còn vật lý trong AS608, firmware mới vẫn không được ghi attendance vì mapping đã bị vô hiệu hóa. Thử xóa khi thiết bị tắt: app phải hiện đang chờ; bật lại để hoàn tất.
7. Xóa nhân viên thử: hồ sơ vào Đã nghỉ; lập phiếu tháng cuối, sau đó nhân viên biến mất khỏi danh sách chọn của chính tháng đó, còn lịch sử vẫn giữ nguyên.

Build Android dùng JDK 17: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`.
Build firmware: `arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 firmware/esp8266_fingerprint`.

### Ghi chú verification trên workspace hiện tại

Đường dẫn workspace có ký tự Unicode nên Android Gradle Plugin cần `android.overridePathCheck=true` trong `gradle.properties`. JUnit local test runner cũng không load class ổn định khi chạy trực tiếp từ đường dẫn này; verification đã chạy qua mapping ASCII tạm thời `Z:` tới workspace, sau đó gỡ mapping. `local.properties` chỉ trỏ tới Android SDK local và đã được `.gitignore` loại trừ.

## Nhật ký triển khai quản lý ca và nghiệp vụ tuần (14/09/2026)

Đã duyệt và triển khai lát mở rộng cho các mục 7–11 theo spec [`docs/superpowers/specs/2026-09-14-shift-attendance-workflow-design.md`](docs/superpowers/specs/2026-09-14-shift-attendance-workflow-design.md) và kế hoạch [`docs/superpowers/plans/2026-09-14-shift-attendance-workflow.md`](docs/superpowers/plans/2026-09-14-shift-attendance-workflow.md).

- Ca làm chỉ gồm `Ca sáng`, `Ca tối`, `Ca bổ sung`; admin tạo/chỉnh ca và quy định giờ, nghỉ, đi trễ, về sớm, ngày áp dụng.
- Admin phân ca cho từng nhân viên hoặc cả phòng ban. Lịch dùng tuần bắt đầu thứ Hai; từng lịch có tăng ca `0`, `1`, `2` hoặc `3` giờ. Có đổi ca thủ công, sao chép tuần trước không ghi đè lịch có sẵn và lịch tháng hiển thị số ca theo ngày.
- Dashboard và tổng hợp dùng đúng tuần đang chọn (thứ Hai–Chủ nhật), không dùng cửa sổ 7 ngày gần nhất.
- Có domain tính giờ làm, nghỉ trưa, đi trễ, về sớm, tăng ca; tổng hợp giờ/ngày công/đi trễ/về sớm/tăng ca/nghỉ phép/vắng không phép.
- Lịch phân ca có thể lưu `workedHoursOverride` từ 0–24 giờ kèm `adjustmentNote` để admin xử lý quên chấm, mất mạng hoặc chấm nhầm mà không sửa attendance gốc.
- Có domain phân loại `Đã vào công ty`, `Chưa đến`, `Đang nghỉ phép`, `Đã ra về`, `Chưa chấm ra`, `Có mặt bất thường` và workflow đơn `PENDING`/`APPROVED`/`REJECTED` với lý do từ chối bắt buộc.
- Repository đã có Flow/transaction cho `shifts`, `workSchedules`, `leaveRequests`, `notifications`; Firestore Rules giới hạn các collection này cho admin. Attendance/payroll cũ vẫn được giữ nguyên.
- File tạo mới: `app/src/main/java/vn/chamcong/iot/model/SchedulingModels.kt`, `RequestModels.kt`, `PresenceModels.kt`; `app/src/main/java/vn/chamcong/iot/domain/SchedulingRules.kt`, `PresenceRules.kt`, `RequestRules.kt`; `app/src/main/java/vn/chamcong/iot/ui/shifts/ShiftsScreen.kt`, `schedule/ScheduleScreen.kt`, `presence/PresenceScreen.kt`, `requests/RequestsScreen.kt`, `reports/WorkSummaryScreen.kt`; các test domain tương ứng.
- File đã sửa: `DashboardModels.kt`, `DashboardRules.kt`, `DashboardRulesTest.kt`, `FirebaseRepository.kt`, `MainViewModel.kt`, `ChamCongApp.kt`, `DashboardScreen.kt`, `firebase/firestore.rules` và README này.
- Kiểm chứng cuối: Gradle 8.9 chạy `:app:testDebugUnitTest :app:assembleDebug` thành công; 38 test, 0 failure, 0 error, 0 skipped. APK nằm tại `app/build/outputs/apk/debug/app-debug.apk`.
- Chưa deploy lại Firebase trong phiên triển khai này; cần chạy lệnh deploy ở phần hướng dẫn sau khi kiểm tra project Firebase đích.

Kiểm tra bằng Gradle 8.9 cài sẵn qua mapping ASCII `Z:` vì `gradlew.bat` trong repo chưa có `gradle-wrapper.jar`. Lệnh tương đương:

```text
Set-Location Z:
& 'C:\Users\DELL\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat' :app:testDebugUnitTest
& 'C:\Users\DELL\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat' :app:assembleDebug
```

## Nhật ký triển khai báo cáo, audit, bảo mật và offline (14/09/2026)

Đã duyệt và triển khai tiếp các mục 13–17 theo spec [`docs/superpowers/specs/2026-09-14-reports-audit-security-offline-design.md`](docs/superpowers/specs/2026-09-14-reports-audit-security-offline-design.md) và plan [`docs/superpowers/plans/2026-09-14-reports-audit-security-offline.md`](docs/superpowers/plans/2026-09-14-reports-audit-security-offline.md).

- Báo cáo có khoảng ngày, mã nhân viên, phòng ban; các nhóm chấm công, ngày công, trễ/sớm, nghỉ phép, tăng ca và hoạt động thiết bị; xuất CSV UTF-8 BOM và chia sẻ qua Android Sharesheet. Báo cáo tháng dùng ngày đầu/cuối tháng được chọn, chưa tự chạy Cloud Scheduler cuối tháng.
- Audit log bất biến ghi người thực hiện, hành động, đối tượng, lý do, chi tiết và server timestamp cho đăng nhập, nhân viên, xóa vân tay, điều chỉnh công, ca/lịch, duyệt đơn, lương, cấu hình thiết bị và đổi mật khẩu. Đã thêm màn `Nhật ký` chỉ đọc.
- Authentication đã thêm quên mật khẩu, đổi mật khẩu và chặn màn admin khi `users/{uid}` có `role=EMPLOYEE` hoặc `active=false`; user chưa có profile vẫn giữ cơ chế bootstrap prototype.
- Firestore Rules đã thêm `users`, `departments`, `settings`, `audit_logs`; audit không update/delete, attendance/payroll không update/delete, `syncReceipts` chỉ admin. Thêm index audit theo `createdAt`.
- Firmware đổi sang `snapshot-2-offline`, cache mapping cục bộ và outbox LittleFS `/attendance.outbox`; retry dùng cùng `eventId`, HTTP 2xx/409 được xem là đã nhận, lỗi mạng giữ hàng đợi và tự gửi lại.
- File tạo mới: `app/src/main/java/vn/chamcong/iot/model/ReportModels.kt`, `AuditModels.kt`, `UserModels.kt`; `app/src/main/java/vn/chamcong/iot/domain/ReportRules.kt`, `AuditRules.kt`, `OfflineQueueRules.kt`; `app/src/main/java/vn/chamcong/iot/data/CsvReportExporter.kt`; `app/src/main/java/vn/chamcong/iot/ui/reports/ReportsScreen.kt`, `ui/audit/AuditScreen.kt`; `app/src/main/res/xml/file_paths.xml`; các test `ReportRulesTest.kt`, `AuditRulesTest.kt`, `OfflineQueueRulesTest.kt`, `CsvReportExporterTest.kt`.
- File đã sửa: `FirebaseRepository.kt`, `MainViewModel.kt`, `ChamCongApp.kt`, `ui/devices/DevicesScreen.kt`, `AndroidManifest.xml`, `firebase/firestore.rules`, `firebase/firestore.indexes.json`, `firmware/esp8266_fingerprint/esp8266_fingerprint.ino` và README này.
- TDD đã chạy RED trước từng domain mới rồi GREEN. Kiểm chứng cuối bằng Gradle 8.9: 47 test, 0 failure, 0 error, 0 skipped; `:app:assembleDebug` thành công và tạo `app/build/outputs/apk/debug/app-debug.apk`. `arduino-cli` không có trong môi trường nên firmware chưa được compile.
