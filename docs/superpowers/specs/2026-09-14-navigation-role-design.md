# Điều hướng Admin và Nhân viên — Thiết kế

## Mục tiêu

Sắp xếp lại giao diện theo tài liệu người dùng đã duyệt, giữ toàn bộ chức năng Admin đang có và bổ sung một shell riêng cho tài khoản Nhân viên.

## Điều hướng

Admin có đúng 5 mục chính ở thanh điều hướng dưới:

1. Tổng quan
2. Chấm công
3. Nhân viên
4. Đơn từ
5. Thiết bị

Các chức năng đã có nhưng ít dùng hằng ngày được đưa vào menu cá nhân/top bar: Lương, Hiệu suất, Ca làm, Lịch, Có mặt, Báo cáo, Nhật ký, Đổi mật khẩu và Đăng xuất. Mỗi mục vẫn mở màn hình hiện tại, không xóa hoặc thay đổi nghiệp vụ.

Nhân viên có 4 mục chính:

1. Trang chủ
2. Chấm công của tôi
3. Đơn từ
4. Cá nhân

## Phân quyền và liên kết dữ liệu

`users/{uid}` dùng các trường `role`, `active`, `employeeId`. Tài khoản `EMPLOYEE` phải có `active=true` và `employeeId` trỏ tới `employees/{employeeId}`. Nếu thiếu liên kết, app hiển thị màn hình hướng dẫn liên hệ Admin thay vì đọc dữ liệu người khác.

Admin tiếp tục dùng các luồng hiện tại. Nhân viên dùng các truy vấn Firestore theo `employeeId` cho hồ sơ, chấm công, lịch và đơn từ; không dùng listener danh sách toàn công ty.

Rules cho phép Nhân viên đọc đúng tài liệu của mình, tạo đơn mới ở trạng thái `PENDING`, nhưng không được tự sửa chấm công, lịch, hồ sơ, trạng thái duyệt hoặc nhật ký.

## Màn hình Nhân viên

- Trang chủ: lời chào, ngày hiện tại, ca hôm nay, giờ vào/ra, trạng thái hôm nay và tổng quan tháng.
- Chấm công của tôi: danh sách ngày trong tháng hiện tại, giờ vào, giờ ra, giờ làm và trạng thái; không có nút sửa trực tiếp.
- Đơn từ: tạo đơn nghỉ phép, đi muộn, về sớm, điều chỉnh chấm công, làm ngoài văn phòng hoặc đổi ca; hiển thị lịch sử và trạng thái xử lý.
- Cá nhân: hiển thị thông tin được phép xem, nhắc các trường do Admin quản lý, đổi mật khẩu và đăng xuất.

Tệp đính kèm chưa thêm Firebase Storage trong phạm vi này; form giữ trường dữ liệu để mở rộng sau.

## Bảo toàn chức năng và giới hạn

Không xóa các module ca/lịch, có mặt, lương, hiệu suất, báo cáo, audit, vân tay, thiết bị hoặc offline outbox. Không thay đổi công thức chấm công. Cài đặt doanh nghiệp chưa có model/backend trong project hiện tại nên chỉ được giữ ở menu dưới dạng mục mở rộng, không tự tạo cấu hình giả.

## Kiểm thử

- Unit test quyền Admin/Nhân viên và giới hạn employeeId.
- Unit test tổng hợp ngày/tháng của Nhân viên.
- Unit test tạo đơn Nhân viên luôn ở trạng thái `PENDING`.
- Build Android và chạy toàn bộ unit test hiện có.
