# Thiết kế MVP quản trị theo MVVM

**Ngày:** 13/09/2026  
**Phạm vi:** Android app quản trị nhân sự/chấm công cho doanh nghiệp dưới 20 người  
**Công nghệ bắt buộc:** Kotlin, Jetpack Compose, MVVM, Firebase Auth, Firestore và WorkManager

## Mục tiêu

Biến app hiện tại thành một màn hình quản trị có thể sử dụng hằng ngày, vẫn giữ luồng Firebase và firmware ESP8266/AS608 đang chạy. MVP tập trung vào dashboard, nhân viên, chấm công, thiết bị, lương và trạng thái lệnh vân tay; các nghiệp vụ ca làm, đơn từ, phân quyền nhân viên và xuất báo cáo nâng cao được để ở lớp mở rộng kế tiếp.

Không lưu ảnh hoặc template vân tay trên Firebase. App chỉ lưu `fingerprintTemplateId`, thiết bị, nhân viên và trạng thái liên kết.

## Lựa chọn kiến trúc

App dùng MVVM theo luồng:

```text
Composable UI
    ↓ events / intents
MainViewModel
    ↓ suspend actions + StateFlow
FirebaseRepository
    ↓ Firebase Auth / Firestore / WorkManager receipt
Firebase và thiết bị ESP8266
```

- **View:** các Composable chỉ nhận state bất biến và callback; không gọi Firebase, không chứa nghiệp vụ tính toán.
- **ViewModel:** giữ `StateFlow<MainUiState>`, điều phối đăng nhập, quan sát dữ liệu, lọc giao diện, lưu nhân viên, lệnh vân tay, lương và đăng xuất.
- **Model/domain:** data class và hàm thuần cho summary dashboard, bộ lọc chấm công, nhãn trạng thái và kiểm tra dữ liệu.
- **Repository:** là nơi duy nhất khởi tạo/đọc Firebase Auth và Firestore. Các luồng realtime trả về `Flow`; thao tác ghi dùng `suspend` và transaction khi cần.
- **WorkManager:** chỉ dùng cho receipt đồng bộ chấm công; không đưa Firebase call vào Composable.

MVP không thêm framework DI mới để giữ dependency nhỏ. Repository được truyền qua constructor ViewModel ở nơi có thể kiểm thử; production mặc định vẫn dùng Firebase singleton.

## Giao diện và điều hướng

Giữ ứng dụng Android phone/tablet, dùng Material 3 và màu thương hiệu xanh ngọc trên nền sáng. Trên màn hình nhỏ dùng `NavigationBar`; trên màn hình rộng có thể dùng navigation rail ở bước tối ưu responsive sau.

Các khu vực chính:

1. **Tổng quan:** số liệu hôm nay, trạng thái thiết bị, cảnh báo và biểu đồ lượt chấm 7 ngày.
2. **Nhân viên:** tìm kiếm theo tên/mã, lọc đang làm/đã nghỉ, phòng ban, trạng thái vân tay; thêm nhân viên, đặt lương, đăng ký/xóa vân tay, chuyển hồ sơ sang đã nghỉ.
3. **Chấm công:** feed realtime, lọc theo thời gian và trạng thái, hiển thị nhân viên, loại vào/ra, thời điểm, thiết bị và trạng thái đúng giờ/trễ.
4. **Thiết bị:** trạng thái `online/offline/unknown`, heartbeat nếu Firestore có dữ liệu, lệnh vân tay đang chờ và phiên bản firmware nếu thiết bị gửi lên.
5. **Lương:** giữ flow phiếu lương bất biến theo tháng hiện có.
6. **Báo cáo/hiệu suất:** ở MVP hiển thị dữ liệu hiện có và thông báo rõ phần chưa có dữ liệu tự động; không tạo số liệu giả.

Mọi màn hình có trạng thái loading, rỗng, lỗi và đang lưu. Thông báo thành công/lỗi dùng state từ ViewModel, không dùng biến toàn cục.

## Dữ liệu và quy tắc nghiệp vụ

Giữ các collection hiện có:

- `employees/{id}`: hồ sơ nhân viên, `active`, lương cơ bản và liên kết vân tay.
- `attendance/{eventId}`: lượt chấm công do thiết bị ghi, dùng timestamp server.
- `payroll/{employeeId_month}`: phiếu lương lịch sử, không ghi đè/xóa.
- `deviceCommands/{deviceId}`: lệnh đăng ký/xóa vân tay theo `requestId`.
- `fingerprintMappings/{templateId}`: mapping template đang bật.
- `devices/{deviceId}`: thông tin trạng thái nếu thiết bị đã ghi dữ liệu.

Quy tắc bắt buộc:

- Chỉ nhân viên `active=true` được nhận diện và đăng ký mẫu mới.
- Lệnh thiết bị `REQUESTED`, `PROCESSING` hoặc `COMPLETED` chưa `applied` không được ghi đè.
- Vị trí template chỉ được tái sử dụng sau khi lệnh xóa hoàn tất.
- Xóa nhân viên là chuyển `active=false`, không xóa lịch sử chấm công/lương.
- Dashboard không suy diễn “đang có mặt” từ một lượt chấm đơn lẻ nếu thiếu dữ liệu vào/ra; trường hợp không đủ dữ liệu phải hiển thị là chưa xác định.
- Các nút test LED/còi chỉ được bật khi firmware công bố capability tương ứng; MVP không gửi command mà firmware hiện tại chưa xử lý.

## Dashboard summary

Tạo hàm domain thuần để tính từ `employees` và `attendance`:

- tổng nhân viên đang làm;
- số nhân viên đã có lượt chấm trong ngày;
- số lượt/nhân viên đi trễ;
- số nhân viên chưa có lượt chấm;
- số người có trạng thái vào mà chưa có lượt ra, nếu dữ liệu đủ để kết luận;
- phân bố lượt chấm theo 7 ngày gần nhất;
- trạng thái thiết bị từ snapshot gần nhất.

Các màu chỉ biểu đạt trạng thái: xanh là bình thường, cam là cần chú ý, đỏ là lỗi/cảnh báo, xám là chưa có dữ liệu.

## Xử lý lỗi và bảo mật

- Firebase exception được chuyển thành thông báo ngắn ở ViewModel; không để exception thoát ra Composable.
- Luồng realtime dùng `catch` và giữ dữ liệu cũ khi listener lỗi.
- Ghi employee/fingerprint/payroll dùng transaction và validation ở repository.
- Firestore Rules tiếp tục tách admin email/password và thiết bị anonymous như prototype hiện tại; đây chưa phải RBAC production hoàn chỉnh.
- Phân quyền nhân viên, custom claims, audit log bất biến, email/push nâng cao và signed device request là phạm vi sau MVP, phải có thiết kế riêng trước khi triển khai.

## Kiểm thử

Theo TDD, mỗi hàm domain mới phải có test trước khi viết implementation:

- summary dashboard với ngày không có dữ liệu, nhân viên đã nghỉ và lượt đi trễ;
- bộ lọc nhân viên theo trạng thái/phòng ban/từ khóa;
- bộ lọc chấm công theo status/type;
- nhãn trạng thái lệnh enrollment/deletion;
- giữ nguyên các test mã nhân viên và phiếu lương hiện có.

Kiểm tra tích hợp sau mỗi lát tính năng:

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

## File dự kiến trong lát triển khai đầu tiên

- Sửa `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt` để tách điều hướng và trạng thái UI khỏi nghiệp vụ.
- Sửa `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt` để giữ MVVM, state và intent.
- Sửa `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt` khi cần thêm truy vấn thiết bị/trạng thái.
- Sửa `app/src/main/java/vn/chamcong/iot/model/Models.kt` cho các model snapshot thiết bị và summary.
- Tạo các file domain/UI nhỏ theo từng feature nếu file hiện tại vượt quá trách nhiệm rõ ràng.
- Tạo test Kotlin cho các hàm domain mới.
- Sửa `README.md` để ghi lại từng file được sửa/tạo và cách kiểm tra.

## Ngoài phạm vi lát đầu tiên

Tự động tính ca linh hoạt/tăng ca/ngày phép, workflow duyệt đơn, tài khoản nhân viên riêng, nhiều chi nhánh, CSV/XLSX, email/Zalo, khuôn mặt/GPS/QR và tích hợp kế toán sẽ được tách thành các lát tiếp theo. Không tạo UI giả làm như các chức năng đó đã hoạt động.

