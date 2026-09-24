# Kế hoạch hoàn thiện các chức năng MVP

**Mục tiêu:** Hoàn thiện những phần còn thiếu trong MVP đã thống nhất theo `hẻ.txt`: điều hướng theo vai trò, bảng tổng quan hằng ngày cho quản trị viên, bộ lọc ngày chấm công, điều khiển và theo dõi thiết bị, cùng đồng hồ NTP trên LCD của ESP8266.

**Kiến trúc:** Giữ nguyên mô hình MVVM của ứng dụng Android và ranh giới Firebase hiện có. Các lệnh thiết bị của quản trị viên tiếp tục dùng tài liệu đơn `deviceCommands/{deviceId}` và giao thức `requestId/status` hiện tại. Firebase Cloud Functions tiếp tục quyết định lượt quét vân tay thuộc ca nào. LCD hiển thị giờ Việt Nam từ NTP; thời gian chấm công trên Firebase vẫn lưu UTC.

**Phần cứng đã lắp đặt:** ESP8266, AS608, LCD, hai LED (xanh D1, đỏ D2) và còi (D7). Không giả định có RTC hoặc nút VÀO/RA riêng; không thêm phần cứng ngoài danh sách này.

**Đặc tả:** `docs/superpowers/specs/2026-09-21-mvp-gap-closure-design.md`

## Ràng buộc

- Điều hướng quản trị viên: Tổng quan, Chấm công, Nhân viên, Đơn từ, Thiết bị.
- Điều hướng nhân viên: Trang chủ, Chấm công của tôi, Đơn từ, Cá nhân.
- Khi chuyển một màn hình khỏi thanh điều hướng chính, vẫn phải giữ đường dẫn để mở màn hình đó.
- Giữ nguyên việc phân loại chấm công theo ca, không sửa/xóa lượt quét gốc, các điều chỉnh có nhật ký kiểm toán, kiểm tra quyền, đăng ký/xóa vân tay và hàng đợi ngoại tuyến.
- Dùng `Asia/Ho_Chi_Minh` để hiển thị và lọc ngày; thời điểm sự kiện vẫn lưu UTC.
- Chỉ điều khiển hai LED và còi đã lắp. Không thêm nút đổi chiều chấm công hay mô tả các màu LED không có trên thiết bị.
- Yêu cầu hiện tại chưa bao gồm việc thêm hoặc chạy kiểm thử tự động. Rà soát thủ công thay đổi cuối cùng; có thể bổ sung hoặc chạy kiểm thử nếu bạn yêu cầu.

## Các tệp dự kiến chỉnh sửa

- `app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`: khung điều hướng theo vai trò và các mục trong menu tài khoản.
- `app/src/main/java/vn/chamcong/iot/ui/admin/AdminTasksScreen.kt`: danh sách màn hình quản trị thứ cấp.
- `app/src/main/java/vn/chamcong/iot/model/DashboardModels.kt` và `app/src/main/java/vn/chamcong/iot/domain/DashboardRules.kt`: mô hình và phép tính tổng quan ngày.
- `app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`: dữ liệu tổng quan ngày và trạng thái bộ lọc ngày.
- `app/src/main/java/vn/chamcong/iot/ui/dashboard/DashboardScreen.kt`: số liệu trong ngày, danh sách đi trễ, lượt quét gần đây và cảnh báo thiết bị.
- `app/src/main/java/vn/chamcong/iot/domain/AttendanceDateRange.kt`: phân tích và lọc khoảng ngày bao gồm cả ngày đầu/cuối.
- `app/src/main/java/vn/chamcong/iot/ui/attendance/AttendanceScreen.kt`: bộ lọc nhanh và giao diện chọn khoảng ngày.
- `app/src/main/java/vn/chamcong/iot/model/DeviceModels.kt` và `app/src/main/java/vn/chamcong/iot/model/AuditModels.kt`: kiểu lệnh thiết bị và mô hình trạng thái.
- `app/src/main/java/vn/chamcong/iot/data/FirebaseRepository.kt`: tạo lệnh thiết bị có ghi nhật ký kiểm toán.
- `app/src/main/java/vn/chamcong/iot/ui/devices/DevicesScreen.kt`: thông tin sức khỏe và nút điều khiển thiết bị.
- `firebase/firestore.rules`: giới hạn xác nhận lệnh và các trường trạng thái thiết bị.
- `firmware/esp8266_fingerprint/esp8266_fingerprint.ino`: xử lý lệnh, gửi trạng thái và hiển thị đồng hồ.
- `README.md`: mô tả cách dùng và phần cứng.

## Việc 1: Sắp xếp điều hướng theo vai trò

1. Đặt các mục chính của quản trị viên và nhân viên theo đúng thứ tự trong phần Ràng buộc.
2. Đưa Chấm công và Thiết bị thành mục chính của quản trị viên. Chuyển Quản lý ca và các trang quản trị còn lại vào danh sách Tác vụ, mở từ menu tài khoản quản trị viên.
3. Giữ Lịch làm việc và Bảng lương cho nhân viên trong menu Cá nhân.
4. Cập nhật biểu tượng/nhãn và rà từng đường dẫn để bảo đảm các trang được chuyển vẫn mở được.

## Việc 2: Bổ sung tổng quan trong ngày cho quản trị viên

1. Tạo mô hình kết quả trong ngày gồm: số nhân viên đang hoạt động, đã chấm vào, chưa chấm vào, đi trễ, hiện đang có mặt, nghỉ phép được duyệt và còn thiếu lượt chấm ra.
2. Tính trạng thái bằng quy tắc phân ca, lượt chấm công hợp lệ, đơn nghỉ đã duyệt, điều chỉnh và trạng thái có mặt hiện tại. Mỗi chỉ số chỉ tính một nhân viên một lần, kể cả người có nhiều ca; không tính nhân viên đã nghỉ.
3. Chỉ xếp nhân viên vào nhóm “chưa chấm vào” khi chưa có lượt chấm vào hợp lệ và còn ít nhất một ca làm chưa được đơn nghỉ đã duyệt bao phủ. Nếu toàn bộ ca trong ngày được nghỉ phép duyệt thì không tính là thiếu chấm công. Các chỉ số có thể giao nhau khi nhân viên nghỉ một ca nhưng vẫn làm ca khác.
4. Đưa kết quả ngày hiện tại theo giờ Việt Nam vào `MainUiState`, đồng thời giữ nguyên dữ liệu biểu đồ tuần hiện tại.
5. Hiển thị đủ bảy chỉ số, danh sách nhân viên đi trễ, chấm công gần đây và cảnh báo tình trạng thiết bị. Giữ liên kết tới màn hình Chấm công đầy đủ.
6. Rà thủ công các trường hợp nhiều ca, nghỉ nửa ngày nhưng chưa chấm ca còn lại, ca qua đêm và thiếu lượt chấm ra theo các quy tắc miền hiện có.

## Việc 3: Thêm bộ lọc ngày chấm công

1. Tạo `AttendanceDateRange` với ngày bắt đầu và ngày kết thúc đều được tính vào khoảng lọc. Từ chối ngày sai định dạng, thiếu một đầu mút hoặc ngày kết thúc trước ngày bắt đầu.
2. Nếu lượt chấm công đã được gán ngày lịch làm việc thì lọc theo ngày lịch đó; chỉ dùng ngày sự kiện theo giờ Việt Nam khi chưa gán được lịch.
3. Thêm các lựa chọn Hôm nay, Hôm qua, Tuần này, Tháng này và Khoảng tùy chọn. Khoảng tùy chọn có hai ô ngày và thông báo lỗi rõ ràng; khoảng không hợp lệ không hiển thị kết quả.
4. Cho phép kết hợp bộ lọc ngày với nhân viên, phòng ban, trạng thái và loại sự kiện. Giữ tương thích bộ lọc một ngày cũ cho tới khi mọi nơi gọi dùng bộ lọc khoảng ngày.
5. Rà thủ công ngày đầu/cuối khoảng và lượt chấm sau nửa đêm thuộc ca qua đêm.

## Việc 4: Bổ sung điều khiển thiết bị và trạng thái trên Android/Firebase

1. Thêm các lệnh `TEST_LED_GREEN`, `TEST_LED_RED`, `TEST_BUZZER`, `SYNC_ATTENDANCE` và `RESTART_DEVICE`. Giữ nguyên dữ liệu và cách hoạt động của các lệnh đăng ký/xóa vân tay.
2. Tạo lệnh của quản trị viên bằng giao thức tài liệu hiện tại, kèm thông tin người thao tác, thiết bị và hành động vào nhật ký kiểm toán. Chỉ cho phép một lệnh đang chờ; không ghi đè yêu cầu vân tay đang chờ xử lý.
3. Khi tạo lệnh không liên quan vân tay, đặt `applied=true` để cơ chế hiện tại cho phép gửi lệnh tiếp theo sau khi hoàn tất. Lệnh vân tay giữ luồng `applied=false` và xử lý hoàn tất hiện có.
4. Bổ sung các trường trạng thái thiết bị có giá trị mặc định an toàn cho tài liệu cũ: trạng thái Wi-Fi, trạng thái đồng bộ Firebase, trạng thái cảm biến, số lần quét lỗi và lỗi gần nhất.
5. Hiển thị lượt chấm công mới nhất của thiết bị (lọc `state.attendance` theo `deviceId`), thời điểm báo trạng thái định kỳ gần nhất, số lượng/sức chứa mẫu vân tay, hàng đợi chưa đồng bộ, các trường sức khỏe và trạng thái lệnh. Tắt điều khiển khi thiết bị ngoại tuyến hoặc đang có lệnh khác; yêu cầu xác nhận trước khi khởi động lại.
6. Giới hạn xác nhận của thiết bị vào đúng tài liệu `/deviceCommands/{deviceId}`; bắt buộc `deviceId` lưu trong tài liệu trùng ID tài liệu và chỉ cho sửa `status`, `message`, `completedAt`. Kiểm tra giá trị/độ dài trạng thái và chỉ chấp nhận tên lệnh quản trị được hỗ trợ, đồng thời giữ nguyên dữ liệu lệnh đăng ký/xóa vân tay cũ.
7. Đối chiếu thủ công tên lệnh, trường tài liệu, quy tắc Firestore và trạng thái giao diện với giao thức chương trình cơ sở ở Việc 5.

## Việc 5: Bổ sung xử lý lệnh, trạng thái và đồng hồ cho ESP8266

1. Mở rộng luồng lệnh đăng ký/xóa vân tay hiện tại để xử lý đúng một lần năm lệnh ở Việc 4, xác nhận bằng `requestId` và trạng thái hiện hành.
2. Điều khiển LED/còi theo đúng chân đã lắp. Với đồng bộ, chỉ báo thành công khi gửi dữ liệu thành công và hàng đợi chấm công đã rỗng. Với khởi động lại, ghi trạng thái hoàn tất trước rồi mới khởi động lại. Lệnh không biết hoặc còn `PROCESSING` sau khi thiết bị khởi động lại phải báo thất bại, không được báo thành công hay chạy lại.
3. Gửi trạng thái Wi-Fi, đồng bộ Firebase, cảm biến, số lần quét lỗi trong năm phút gần nhất và lỗi mới nhất. Cảnh báo trên bảng quản trị khi có từ năm lần quét lỗi trong năm phút.
4. Bỏ vòng lặp lỗi chặn sớm khi AS608 không xác thực được: vẫn kết nối Wi-Fi/Firebase, gửi `sensorStatus=ERROR` cùng lỗi gần nhất, đồng thời khóa việc đọc cảm biến và đăng ký/xóa vân tay cho tới khi cảm biến hoạt động. Các chức năng mạng, LED, còi, khởi động lại và báo trạng thái định kỳ vẫn tiếp tục hoạt động.
5. Bỏ `name` và `location` cố định khỏi dữ liệu báo trạng thái định kỳ gửi lên. Vì hiện chương trình cơ sở dùng Firestore REST PATCH không có mặt nạ trường, hãy thêm `updateMask` chỉ gồm các trường do thiết bị quản lý, bao gồm cả năm trường trạng thái mới; nhờ vậy tên/vị trí và dữ liệu khác do quản trị viên đặt không bị ghi đè hoặc mất.
6. Thêm đồng hồ LCD khi thiết bị rảnh, cập nhật mỗi giây sau khi nhận được giờ NTP đáng tin cậy. Trước đó hiển thị chưa đồng bộ giờ; giữ nguyên màn hình đăng ký/lỗi trong luồng đang chạy và vẫn từ chối lượt chấm công khi chưa có giờ hợp lệ.
7. Rà thủ công luồng gửi lệnh, xác nhận, khởi động lại, lỗi cảm biến, báo trạng thái định kỳ và đồng hồ chờ. Các tín hiệu LED/còi/LCD thực tế có thể được xem trực tiếp trên phần cứng đã lắp.

## Việc 6: Cập nhật tài liệu và rà soát tổng thể

1. Cập nhật `README.md` về điều hướng mới, bộ lọc ngày, lệnh/trạng thái thiết bị, chân phần cứng, đồng hồ NTP và việc ESP8266 không giữ được giờ khi mất điện rồi khởi động lạnh nếu chưa đồng bộ lại NTP.
2. Đối chiếu toàn bộ thay đổi với đặc tả MVP đã duyệt. Xác nhận các trang chuyển vị trí vẫn mở được; lượt chấm công gốc không bị sửa/xóa; tên/vị trí do quản trị viên đặt không bị lần báo trạng thái định kỳ ghi đè; tên lệnh và trường trạng thái khớp giữa Android, quy tắc Firestore và chương trình cơ sở; chỉ dùng phần cứng đã lắp.

## Tự rà soát

- Sáu việc trên bao phủ các mục trong đặc tả MVP đã duyệt.
- Cài đặt doanh nghiệp nâng cao, nhiều mẫu vân tay cho một người, tệp đính kèm, ảnh đại diện, nút vật lý, đổi chế độ VÀO/RA, thông báo ngoài ứng dụng và tự gửi báo cáo cuối tháng nằm ngoài phạm vi.
- Giữ rõ các ràng buộc về phân ca, nhật ký kiểm toán, quyền truy cập và hàng đợi ngoại tuyến.
- Không thêm hoặc chạy kiểm thử tự động trong kế hoạch này vì bạn chưa yêu cầu kiểm thử/xác minh.

## Bàn giao triển khai

Khuyến nghị triển khai trực tiếp trong phiên này vì giao thức lệnh cần đồng bộ giữa Kotlin, quy tắc Firestore và chương trình cơ sở ESP8266; phần xử lý lệnh và đồng hồ cũng cùng nằm trong một tệp chương trình cơ sở. Có thể giao các phần điều hướng, bảng tổng quan và lọc ngày độc lập cho tác tử phụ, nhưng nên giữ phần tích hợp giao thức thiết bị cùng nhau.
