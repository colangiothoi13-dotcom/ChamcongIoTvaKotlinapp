# Đặc tả hoàn thiện các chức năng còn thiếu trong MVP

## Mục tiêu

Hoàn thiện ứng dụng chấm công hiện có theo phần MVP trong tệp `D:\decode\hẻ.txt`, đồng thời giữ nguyên cách tự xác định lượt chấm công theo ca. Người dùng đã xác nhận ưu tiên danh sách MVP này và cho biết bộ phần cứng ESP8266, AS608, LCD, hai đèn LED và còi đã được lắp đặt đầy đủ. Người dùng cũng muốn thiết bị hiển thị thời gian thực.

## Hiện trạng

Dự án đã có quản lý nhân viên và tài khoản, đăng ký/xóa một mẫu vân tay cho mỗi nhân viên, lịch sử chấm công và chỉnh sửa có lưu vết, quy trình nghỉ phép/điều chỉnh công, báo cáo CSV, trang chủ/lịch sử/hồ sơ nhân viên, tín hiệu báo trạng thái định kỳ của thiết bị (heartbeat) và hàng đợi chấm công ngoại tuyến. Các phần còn thiếu trong MVP đã chọn:

- Mục Chấm công và Thiết bị của quản trị viên đang nằm bên trong danh mục Tác vụ thay vì xuất hiện trong năm mục điều hướng chính theo yêu cầu.
- Điều hướng của nhân viên có sáu mục thay vì bốn mục: Trang chủ, Chấm công của tôi, Đơn từ và Cá nhân.
- Trang tổng quan quản trị viên tập trung vào hoạt động trong tuần, chưa hiển thị đầy đủ số liệu trong ngày và danh sách nhân viên đi trễ.
- Chấm công chỉ lọc theo một ngày cụ thể; chưa có bộ lọc nhanh Hôm nay/Hôm qua/Tuần này/Tháng này.
- Quản lý thiết bị đã có trạng thái và lệnh vân tay, nhưng chưa có kiểm tra LED/còi từ xa, đồng bộ thủ công, khởi động lại hoặc hiển thị lỗi gần nhất.
- Chương trình cơ sở của ESP8266 đồng bộ thời gian qua NTP để ghi dấu thời gian cho sự kiện, nhưng LCD chưa hiển thị đồng hồ trực tiếp.

## Thiết kế

### Điều hướng

- Điều hướng chính của quản trị viên gồm: Tổng quan, Chấm công, Nhân viên, Đơn từ và Thiết bị.
- Điều hướng chính của nhân viên gồm: Trang chủ, Chấm công của tôi, Đơn từ và Cá nhân.
- Các trang nâng cao hiện có của quản trị viên vẫn mở được từ danh mục Tác vụ trong menu tài khoản quản trị viên. Lịch làm việc và Bảng lương của nhân viên vẫn mở được từ menu cá nhân. Thay đổi này điều chỉnh vị trí hiển thị trong điều hướng, không bỏ quyền truy cập các chức năng đang có.

### Trang tổng quan quản trị viên

Hiển thị số liệu của ngày hiện tại: tổng số nhân viên đang hoạt động, số nhân viên đã chấm công vào hợp lệ, chưa chấm công, đi trễ, hiện đang có mặt, đang nghỉ phép đã được duyệt và chưa chấm công ra. Dùng dữ liệu lịch làm việc, quy tắc xác định lượt chấm công, các điều chỉnh và trạng thái có mặt hiện có làm nguồn dữ liệu chuẩn. Giữ danh sách chấm công gần đây, bổ sung danh sách nhân viên đi trễ, tiếp tục cảnh báo tình trạng thiết bị và giữ liên kết đến màn hình chấm công đầy đủ.

### Bộ lọc chấm công

Thêm các bộ lọc nhanh: Hôm nay, Hôm qua, Tuần này, Tháng này và Khoảng ngày tùy chỉnh. Khoảng ngày tùy chỉnh nhận ngày bắt đầu và ngày kết thúc, bao gồm cả hai ngày đó trong kết quả. So sánh ngày theo giờ Việt Nam và quy tắc gán ngày làm việc hiện có. Các bộ lọc nhân viên, phòng ban, trạng thái và loại sự kiện tiếp tục kết hợp được với bộ lọc ngày.

### Trạng thái và điều khiển thiết bị

Giữ giao thức gửi lệnh hiện tại, dùng một tài liệu cho mỗi thiết bị, cùng chức năng đăng ký/xóa vân tay. Bổ sung các lệnh dành cho quản trị viên: kiểm tra LED xanh, kiểm tra LED đỏ, kiểm tra còi, đồng bộ hàng đợi chấm công và khởi động lại thiết bị. Tái sử dụng `requestId`, trạng thái lệnh, nhật ký kiểm toán và cơ chế chỉ xử lý một lệnh tại một thời điểm. Chương trình cơ sở phải xác nhận lệnh đã hoàn tất thì ứng dụng mới báo thành công. Vô hiệu hóa các nút điều khiển khi thiết bị ngoại tuyến hoặc đang có lệnh khác chờ xử lý.

Hiển thị sự kiện chấm công gần nhất của thiết bị, đồng thời cung cấp thời điểm báo trạng thái định kỳ gần nhất (heartbeat), số lượng/sức chứa mẫu vân tay, số bản ghi đang chờ trong hàng đợi, trạng thái Wi-Fi, trạng thái đồng bộ Firebase, trạng thái cảm biến, số lần quét thất bại và lỗi thiết bị gần nhất. Khi cập nhật trạng thái định kỳ, chương trình cơ sở phải giữ nguyên tên và vị trí thiết bị do quản trị viên chỉnh sửa, không ghi đè bằng các giá trị cố định.

### Đồng hồ trực tiếp trên thiết bị

Dùng đồng bộ NTP hiện có để hiển thị giờ Việt Nam trên LCD khi thiết bị ở trạng thái chờ. Cập nhật mỗi giây. Cho đến khi NTP cung cấp thời gian đáng tin cậy, hiển thị rõ trạng thái chưa đồng bộ và tiếp tục từ chối sự kiện chấm công như chương trình cơ sở hiện tại. Dấu thời gian trong dữ liệu chấm công tiếp tục dùng UTC; chỉ đồng hồ trên LCD hiển thị giờ địa phương. Thiết kế không giả định có module RTC hoặc nút bấm vật lý.

## Hành vi được giữ nguyên

- Nhân viên vẫn chấm công bằng cách quét vân tay thông thường; hệ thống xác định lượt vào/ra theo khung giờ ca và bộ phân giải hiện có ở máy chủ. Chương trình cơ sở không tự suy đoán VÀO/RA bằng nút vật lý mới, vì bộ phần cứng đã lắp không có nút đó.
- Giữ nguyên việc đăng ký/xóa vân tay, hàng đợi ngoại tuyến, kiểm tra quyền theo vai trò, điều chỉnh chấm công có lưu vết, lịch làm việc/bảng lương/báo cáo và các quy tắc truy cập.
- Dùng hai đèn LED và còi hiện có để kiểm tra. Thay đổi này không thêm phản hồi bằng LED xanh dương, hổ phách hoặc tím vì các đèn đó không nằm trong bộ phần cứng đã lắp đặt.

## Ngoài phạm vi của đợt MVP này

- Bộ cài đặt doanh nghiệp đầy đủ (chính sách công ty, nghỉ trưa, quy tắc đi trễ/về sớm, quản trị tài khoản quản trị viên).
- Nhiều mẫu vân tay cho mỗi nhân viên, tệp đính kèm trong đơn, tải ảnh đại diện và thay đổi chế độ xác định chiều chấm công.
- Gửi email/Zalo, tự động gửi báo cáo cuối tháng hoặc bổ sung phần cứng như module RTC hay nút VÀO/RA.

## Tiêu chí nghiệm thu

1. Điều hướng chính của quản trị viên và nhân viên lần lượt có năm mục và bốn mục như yêu cầu; các chức năng phụ hiện có vẫn truy cập được.
2. Số liệu trên trang tổng quan tính cho hôm nay và tuân theo các quy tắc lịch làm việc, điều chỉnh, nghỉ phép và xác định lượt chấm công hiện có; trang hiển thị nhân viên đi trễ và các lượt quét gần đây.
3. Các bộ lọc ngày có sẵn và khoảng ngày tùy chỉnh bao gồm cả ngày đầu/cuối, đồng thời dùng được cùng các bộ lọc hiện có.
4. Quản trị viên có thể gửi từng lệnh điều khiển khi thiết bị trực tuyến, xem trạng thái `REQUESTED`, `PROCESSING`, `COMPLETED` hoặc `FAILED`, và không thể ghi đè lệnh chưa được xử lý.
5. Chương trình cơ sở báo cáo các trường sức khỏe/lỗi của thiết bị và giữ nguyên thông tin thiết bị do quản trị viên chỉnh sửa khi cập nhật trạng thái định kỳ.
6. Khi thiết bị chờ, LCD hiển thị chính xác giờ Việt Nam sau khi đồng bộ NTP và thông báo chưa đồng bộ trước thời điểm đó; dấu thời gian sự kiện chấm công vẫn dùng UTC.
