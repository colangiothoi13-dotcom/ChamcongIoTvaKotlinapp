# Giao diện và chức năng Admin dùng chung

Android và `web-admin` dùng cùng tài khoản Firebase, hồ sơ nhân viên và dữ liệu quản trị. Giao diện web là mẫu chung. Web dùng bảng rộng và thanh điều hướng bên trái; app dùng thẻ, menu Danh mục và năm mục ở thanh dưới để phù hợp điện thoại.

## Thiết kế

Hai giao diện dùng màu xanh chính `#087F46`, nền sáng `#F6F8F7`, chữ chính `#172B23`, cùng màu lỗi/cảnh báo và trạng thái đang làm/đã nghỉ. Chữ nội dung chính 16 px/sp, bảng hoặc nội dung phụ 14, chú thích 12; khoảng cách theo 4/8/12/16/24/32. Thẻ bo 16, cửa sổ bo 20; vùng bấm Android và màn hình cảm ứng ít nhất 48, nút web trên máy tính ít nhất 44. App vẫn có bảng màu tối phù hợp thiết lập hệ thống.

Tên mục và thao tác thống nhất: Bảng lương, Lịch làm việc, Thiết bị IoT, Báo cáo & bảng công, Camera & khuôn mặt, Đặt đơn giá giờ, Sửa hồ sơ, Điều chỉnh công và Chuyển sang đã nghỉ. Đã nghỉ giữ hồ sơ để tra cứu lịch sử.

Lượt chỉnh ngày 13/10/2026 đưa bố cục web vào các màn quản trị của app: tiêu đề và mô tả trang, thẻ thống kê, khu vực bộ lọc, nút chính và thẻ danh sách có viền dùng component chung. Danh mục app chia đúng bốn nhóm **Làm việc, Nhân sự, Tổng hợp, Quản trị** như web; các màn Phân ca và Bảng công tháng vẫn có đường mở riêng.

Tổng quan có cùng sáu thao tác nhanh: Thêm nhân viên, Giao công việc, Phân ca làm việc, Điều chỉnh công, Duyệt đơn từ và Quản lý lương. Bốn thống kê chính cùng tên; app giữ thêm thống kê và lựa chọn tuần đã có. Biểu đồ app ghi rõ số nhân viên theo tuần đã chọn, biểu đồ web ghi số lượt hợp lệ trong bảy ngày gần nhất.

Nhân viên trên app có trạng thái Tất cả/Đang làm việc/Đã nghỉ, tìm theo tên/mã/email/phòng ban và nút Xem / quản lý để mở các phần tài khoản, đơn giá giờ, vân tay, khuôn mặt và nghỉ việc. Phần đầu, bộ lọc và nhân viên cuộn trong cùng danh sách, phù hợp màn hình thấp. Thông báo và tiện ích có danh sách trước, mở biểu mẫu bằng nút; Thông báo có cả thông báo quản trị nhận được và lịch sử gửi như web.

## Các phần được bổ sung

| Chức năng | Android | Web |
| --- | --- | --- |
| Tài khoản của nhân viên đã có hồ sơ | Xem email/trạng thái, tạo tài khoản riêng, giữ phiên Admin | Luồng tương đương trong hồ sơ nhân viên |
| Chấm công | Chi tiết/lịch sử; sửa phân loại; điều chỉnh từ dòng hoặc chọn nhân viên/ngày; giữ nội dung nhập khi đổi ngày; kiểm tra tháng nghỉ việc khi lưu | Luồng tương đương, giữ lượt quét gốc và nhật ký |
| Lương | Xem lịch sử phiên bản đã lưu; xuất phiếu đã lưu ra CSV | Lịch sử phiên bản và xuất CSV |
| Hiệu suất | Nhập điểm tháng 0–100 và ghi chú, xuất chỉ số/điểm/ghi chú CSV, dùng cùng `performanceReviews` | Xem/sửa và xuất dữ liệu chung |
| Báo cáo | Tổng hợp thực theo nhân viên, CSV lương đã lưu, bảng công nhân viên × ngày và các báo cáo quản trị | Dùng cùng trường dữ liệu, cutoff nghỉ việc và quy tắc công |
| Duyệt đơn | Ghi chú duyệt tùy chọn; từ chối cần lý do; tăng ca lưu ghi chú trong thông báo cho nhân viên | Luồng tương đương |
| Lịch làm việc | Gộp ca mặc định; lựa chọn thay ca rõ ràng; xóa lịch ngày có xác nhận/nhật ký | Phân ca theo người/nhóm, gộp/thay và sửa từng ô |
| Ca bổ sung | Thêm, sửa, ngừng và kích hoạt lại cấu hình | Luồng tương đương |
| Công việc | Lọc ưu tiên, hạn hôm nay, 24 giờ tới hoặc quá hạn | Bổ sung bộ lọc tương đương app |
| Camera | Xem, dừng, thử lại và xoay qua kết nối HTTPS với laptop | Xem qua proxy local trên laptop |
| Vân tay/khuôn mặt, phòng ban, thiết bị, thông báo, tiện ích | Giữ các luồng đã có và dữ liệu dùng chung | Giữ các luồng đã có và dữ liệu dùng chung |

Các biểu mẫu kiểm tra lại dữ liệu trước khi ghi; lịch sử công/lương chỉ đọc, không tự tính lại phiếu. Tạo tài khoản dùng Firebase Auth riêng trong bộ nhớ, giao dịch kiểm tra `accountVersion` và dọn tài khoản Auth mới nếu không lưu được hồ sơ.

## Cập nhật và mở

Cài APK được tạo tại `app/build/outputs/apk/debug/app-debug.apk` lên điện thoại. Web đang mở tải lại bằng **Ctrl + F5**; khi cần chạy lại, vào `web-admin` và dùng `npm start` hoặc `mo-web-admin.cmd`.

### Camera trên app

1. Laptop và điện thoại nối cùng mạng. Dừng cửa sổ web cũ bằng **Ctrl+C**, rồi mở `web-admin/mo-web-admin-camera-app.cmd`.
2. Cửa sổ hiển thị địa chỉ dạng `https://192.168.x.x:4174` và **Mã xác minh laptop (SHA-256)**. Có thể có nhiều địa chỉ khi laptop dùng nhiều card mạng; chọn địa chỉ của mạng Wi-Fi đang nối với điện thoại.
3. Trong app Admin, mở **Danh mục → Quản trị → Camera & khuôn mặt**, nhập địa chỉ HTTPS và mã xác minh, rồi bấm **Bắt đầu xem**. App nhớ hai giá trị này cho lần sau.
4. Bấm **Dừng xem**, hoặc chuyển trang/đưa app vào nền để dừng lấy ảnh. Bấm **Xoay 90°** chỉ đổi góc xem. Đăng ký mẫu khuôn mặt vẫn nằm trong Nhân viên.

Nếu Windows hỏi quyền mạng khi bật máy chủ, chỉ cho phép mạng riêng đang sử dụng. Nếu laptop đổi IP hoặc chứng chỉ hết hạn, cửa sổ máy chủ có mã mới; cập nhật địa chỉ/mã trong app.

Camera dùng cấu hình hiện có `services/face_attendance/config.json`. Token camera và khóa HTTPS chỉ nằm trên laptop, không gửi xuống app. App xác minh đúng chứng chỉ laptop trước khi gửi Firebase ID token qua HTTPS. Máy chủ xác thực token qua Firebase Auth, kiểm tra tài khoản ADMIN đang hoạt động trong Firestore; lần xác thực hợp lệ được giữ tối đa 15 giây và không vượt hạn token. Tài khoản nhân viên, thiết bị, anonymous và token sai dự án không được xem.

Máy chủ camera cho điện thoại chỉ bật khi dùng launcher riêng hoặc `WEB_ADMIN_MOBILE_CAMERA=1`; có thể đặt `WEB_ADMIN_MOBILE_PORT` để đổi cổng HTTPS. Web thường vẫn mở trên localhost:4173. Firebase Hosting không chạy proxy camera; laptop phải giữ cửa sổ máy chủ mở.

## Kiểm tra

Lượt kiểm tra cuối: **418 bài Android đạt**, tạo APK debug thành công; **246 bài web đạt**, **17 bài emulator bỏ qua** vì emulator chưa bật; build web thành công. **15 kịch bản Edge đạt**, gồm chuyển từng trang bằng chuột/chạm, tiêu đề khớp menu, không tràn trang ở màn hình 375 px và vùng bấm cảm ứng 48 px. Sáu bài Android bổ sung xác nhận biểu mẫu chỉnh công không tự ghép giờ vào ca sáng với giờ ra ca chiều, giữ điều chỉnh mới nhất và loại giờ ca đã duyệt nghỉ. Có 12 bài cho HTTPS camera, dùng kết nối TLS thật với Auth/Firestore/camera giả.

```powershell
.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug
npm test --prefix web-admin
npm run build --prefix web-admin
```

Kiểm tra Edge dùng `web-admin/scripts/check-browser.mjs`: dữ liệu thử trong bộ nhớ, profile riêng, không đọc/ghi Firebase thật. Đã kiểm tra biểu mẫu, bộ lọc, lỗi, trạng thái đang chờ, chữ/vùng bấm trên desktop và màn hình 375 px; ảnh kiểm tra nằm trong `web-admin/verification`.

Kiểm tra camera dùng camera/Auth/Firestore giả và kết nối TLS thật để xác minh pin chứng chỉ, giới hạn quyền/host/đường dẫn, token hết hạn, lỗi mạng và đọc ảnh cố định. Những bài này không thay thế thử điện thoại, tài khoản Admin và camera thực. Chưa triển khai Firebase Hosting/Rules hoặc gửi lệnh đăng ký tới thiết bị trong công việc đồng nhất này.

Nguồn API xác thực: [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth), [Firestore REST với Firebase ID token](https://firebase.google.com/docs/firestore/use-rest-api).
