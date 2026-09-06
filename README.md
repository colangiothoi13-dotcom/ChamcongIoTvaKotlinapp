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

Repository chưa kèm Gradle wrapper vì máy tạo dự án hiện không có Gradle/Android SDK. Android Studio có thể đồng bộ bằng Gradle đã cấu hình; nên tạo wrapper bằng `gradle wrapper --gradle-version 8.9` nếu cần build từ terminal.

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
- `payroll/{id}`: lương cơ bản, thưởng, khấu trừ theo kỳ.
- `performanceReviews/{id}`: kỳ đánh giá, điểm, nhận xét.
- `notifications/{id}`: thông báo nội bộ.
- `devices/{id}`: trạng thái và cấu hình thiết bị (không lưu plaintext secret).

## Phần tiếp theo nên làm

MVP đã có đăng nhập, dashboard, danh sách/thêm nhân viên, feed chấm công realtime, mô hình lương–hiệu suất, FCM và firmware nhận dạng/gửi kết quả. Đã có thiết lập lương và lưu phiếu lương theo tháng; KPI, ca làm, tăng ca và ngày phép chưa tự động tính.

## Quản lý nhân viên, vân tay và lương

- Khi thêm nhân viên, app tự cấp mã `NV0001`, `NV0002`… trong giao dịch Firestore. Cả “Chỉ lưu nhân viên” và “Lưu & đăng ký vân tay” đều dùng cùng bộ đếm. Mã cũ được giữ nguyên; mã NV có sẵn và nhân viên đã nghỉ vẫn được xét để tránh cấp lại. Cập nhật toàn bộ app quản trị sang bản mới trước khi thêm nhân viên; bản cũ còn nhập mã thủ công không tham gia bộ đếm.
- Nhân viên → **Thiết lập lương** để đặt lương cơ bản theo tháng.
- Lương → nhập tháng `yyyy-MM` → **Lập phiếu lương / thiết lập lương** → chọn nhân viên → nhập thưởng, khấu trừ → lưu. Thực lĩnh = cơ bản + thưởng − khấu trừ; chưa tự quy đổi ngày công, thuế hoặc tăng ca.
- Mỗi nhân viên có một phiếu mỗi tháng. Phiếu lưu mã/tên và số tiền tại thời điểm tạo. Đổi lương hoặc chuyển nhân viên sang đã nghỉ không thay đổi phiếu đã lưu. Có thể lập phiếu cuối cùng cho nhân viên đã nghỉ từ danh sách chọn trong tab Lương.
- **Xóa nhân viên** chuyển hồ sơ sang `active=false`, ẩn khỏi danh sách đang làm; bật **Hiện nhân viên đã nghỉ** để tra cứu. Không xóa chấm công hoặc phiếu lương.
- **Xóa vân tay** gửi `DELETE_FINGERPRINT` đến thiết bị đã đăng ký, vô hiệu liên kết chấm công và chờ cảm biến xác nhận xóa. Lệnh thất bại giữ vị trí mẫu để có thể gửi lại, tránh cấp nhầm cho nhân viên khác. Chỉ tái sử dụng vị trí sau khi xóa thành công.
- Giữ app mở/kết nối mạng để đồng bộ kết quả thiết bị; nếu đóng app, mở lại để hoàn tất. Lệnh đang xử lý không được ghi đè. Thiết bị khởi động lại giữa đăng ký sẽ báo thất bại; xóa mẫu đang chờ trước khi đăng ký lại.
- KPI chưa tự tính. Công thức chuyên cần đề xuất được giải thích trong tab Hiệu suất; chưa có lịch làm theo tháng để áp dụng.

### Cập nhật và kiểm tra

1. Nạp lại `firmware/esp8266_fingerprint/esp8266_fingerprint.ino` cho NodeMCU ESP8266 **trước khi dùng chức năng xóa vân tay**. Firmware cũ chỉ biết đăng ký, không phân biệt lệnh xóa.
2. Cài APK mới: `app/build/outputs/apk/debug/app-debug.apk`.
3. Publish nội dung `firebase/firestore.rules` trong Firebase Console để cấm sửa/xóa phiếu lương đã lưu. Không cần Cloud Functions cho luồng Spark hiện tại.
4. Thử thêm hai nhân viên, kiểm tra mã khác nhau; đặt lương, lưu phiếu, đổi mức lương, kiểm tra phiếu cũ giữ nguyên.
5. Đăng ký một mẫu thử → xóa vân tay → đợi trạng thái Hoàn tất → quét lại phải không được nhận diện. Thử xóa khi thiết bị tắt: app phải hiện đang chờ; bật lại để hoàn tất.
6. Xóa nhân viên thử: hồ sơ vào Đã nghỉ, phiếu lương/chấm công còn nguyên. Chỉ thử với dữ liệu kiểm thử.

Build Android dùng JDK 17: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`.
Build firmware: `arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 firmware/esp8266_fingerprint`.
