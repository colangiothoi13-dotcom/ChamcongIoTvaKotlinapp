# Chấm công IoT bằng vân tay

Ứng dụng Android dành cho **Admin** và **nhân viên**, kết hợp ESP8266 với cảm biến AS608/R307 và Firebase. Tài liệu này mô tả đường đi đến từng màn hình, ý nghĩa các nút và điều gì xảy ra sau khi thao tác. Dự án đang vận hành theo **Firebase Spark**: không cần Cloud Functions để đăng ký vân tay hoặc để Android hiển thị công đã phân giải.

## Mục lục

- [Luồng hoạt động chung](#luồng-hoạt-động-chung)
- [Đăng nhập và điều hướng](#đăng-nhập-và-điều-hướng)
- [Chức năng Admin](#chức-năng-admin)
- [Chức năng nhân viên](#chức-năng-nhân-viên)
- [Cách hệ thống tính công trên Spark](#cách-hệ-thống-tính-công-trên-spark)
- [Cài đặt và triển khai](#cài-đặt-và-triển-khai)
- [Cấu trúc mã nguồn](#cấu-trúc-mã-nguồn)
- [Lưu ý khi vận hành](#lưu-ý-khi-vận-hành)

## Luồng hoạt động chung

```text
Admin tạo phòng ban, nhân viên, ca và lịch
  ├─ Tạo tài khoản đăng nhập cho nhân viên (tùy chọn)
  └─ Gửi lệnh đăng ký vân tay → ESP8266 lấy mẫu 2 lần → thiết bị hoàn tất trên Firestore
Nhân viên đăng nhập → đăng ký lịch tuần sau / gửi đơn / đăng ký tăng ca
Admin duyệt lịch, đơn từ, tăng ca
Nhân viên quét vân tay → ESP8266 nhận diện → lưu lượt SCAN/PENDING lên Firestore
Android ghép lượt quét với lịch, ca và quyết định duyệt → hiển thị công hiệu lực
Admin theo dõi, xử lý ngoại lệ → lập phiếu lương, xuất báo cáo, xem nhật ký
```

- **Vân tay:** mẫu vân tay nằm trong cảm biến, Firestore lưu mã mẫu và liên kết nhân viên. Sau khi Admin gửi lệnh, thiết bị tự hoàn tất hồ sơ/lệnh; không cần giữ app Admin mở hoặc đăng nhập lại.
- **Chấm công:** thiết bị gửi lượt quét gốc. Android đọc lượt quét, lịch, ca, đơn và các quyết định rồi tính kết quả vào/ra, đi trễ, về sớm, tăng ca. Lượt quét gốc không bị sửa.
- **Giờ chuẩn:** lịch đăng ký, ca, ngày công và hạn duyệt dùng múi giờ `Asia/Ho_Chi_Minh`. Ngày nhập theo `yyyy-MM-dd`, tháng lương theo `yyyy-MM`, giờ theo `HH:mm`.
- **Lịch tuần:** nhân viên đăng ký tuần sau từ thứ Hai đến thứ Bảy trước **12:00 thứ Bảy**; Admin duyệt trước **17:00 thứ Bảy**. Đơn quá hạn vẫn chờ xử lý, không tự được duyệt.

## Đăng nhập và điều hướng

| Vị trí | Nút/thao tác | Kết quả |
| --- | --- | --- |
| Màn hình đăng nhập | `Đăng nhập` | Xác thực Email/Password, đọc `users/{uid}` rồi mở giao diện theo vai trò và trạng thái tài khoản. |
| Màn hình đăng nhập | `Quên mật khẩu` | Gửi email đặt lại mật khẩu đến địa chỉ đã nhập. |
| Biểu tượng tài khoản ở góc trên | `Đổi mật khẩu` → `Lưu` | Nhập và xác nhận mật khẩu mới của tài khoản hiện tại. |
| Biểu tượng tài khoản ở góc trên | `Đăng xuất` | Kết thúc phiên hiện tại. |

Nếu app báo **“Tài khoản chưa được cấp quyền”**, Admin cần kiểm tra `users/{uid}`: `role`, `active` và `employeeId` của nhân viên. Tài khoản Firebase Auth riêng lẻ chưa đủ quyền sử dụng dữ liệu.

Thanh dưới của Admin: **Tổng quan · Tác vụ · Đơn từ · Phân ca · Nhân viên**. Mục **Tác vụ** mở các màn hình vận hành, lương, báo cáo và quản trị. Thanh dưới của nhân viên: **Trang chủ · Lịch làm việc · Chấm công của tôi · Đơn từ · Cá nhân**. **Bảng lương** mở từ **Chấm công của tôi** hoặc menu tài khoản.

## Chức năng Admin

### 1. Tổng quan và Tác vụ

**Tổng quan** hiển thị tình hình hôm nay, người đi trễ, số lượt quét trong tuần, tình trạng thiết bị, cảnh báo cần xử lý, thông báo và các lượt chấm mới nhất.

| Nút/thao tác | Kết quả |
| --- | --- |
| `‹`, `Tuần này`, `›` | Đổi tuần của biểu đồ và số liệu tuần. |
| `Xem tất cả` ở “Chấm công mới nhất” | Mở màn hình **Chấm công** để lọc và xử lý chi tiết. |
| `Đã đọc` trên thông báo | Đánh dấu thông báo đã đọc. |
| Thẻ chức năng trong **Tác vụ** | Mở màn hình tương ứng: **Chấm công, Có mặt, Thiết bị, Phân ca, Ca làm, Lịch, Lương, Hiệu suất, Báo cáo, Bảng công tháng, Nhật ký, Phòng ban, Thông báo**. |

**Phân ca** trên thanh dưới là trang điều hướng nhanh đến **Ca làm** và **Lịch**. Mục **Cài đặt** chưa có màn hình thao tác trong bản hiện tại.

### 2. Nhân viên và đăng ký vân tay

Vào **Nhân viên** để quản lý hồ sơ. Mã nhân viên được cấp tự động, ví dụ `NV0001`.

| Nút/thao tác | Kết quả |
| --- | --- |
| Ô `Tìm theo tên hoặc mã nhân viên`, chip phòng ban | Lọc danh sách trên màn hình. |
| Công tắc `Hiện nhân viên đã nghỉ` | Hiển thị cả hồ sơ đã nghỉ trong danh sách nhân sự. |
| `Thêm` | Mở biểu mẫu tên, email, phòng ban, thiết bị đăng ký, thông tin liên hệ và ngày vào làm. |
| Công tắc `Tạo tài khoản đăng nhập cho nhân viên` | Hiện ô mật khẩu; khi lưu, tạo tài khoản Email/Password và hồ sơ `users/{uid}` liên kết với nhân viên. |
| `Chỉ lưu nhân viên` | Lưu hồ sơ; có thể đăng ký vân tay sau. |
| `Lưu & đăng ký vân tay` | Lưu hồ sơ và gửi lệnh đăng ký đến mã thiết bị đã nhập. |
| `Sửa hồ sơ` → `Lưu hồ sơ` | Cập nhật thông tin nhân viên. |
| `Thiết lập lương` | Nhập đơn giá lương theo giờ và lưu. |
| `Đăng ký vân tay` → nhập `Mã thiết bị` → `Gửi lệnh` | Tạo lệnh trên Firestore. Đặt **cùng một ngón tay hai lần** trên cảm biến; theo dõi trạng thái lệnh ngay tại thẻ nhân viên. |
| `Xóa vân tay` → `Xác nhận xóa` | Gửi lệnh xóa mẫu trên thiết bị, giữ hồ sơ và lịch sử lương/công. Có thể đăng ký lại sau khi xóa thành công. |
| `Xóa nhân viên` → `Xác nhận xóa` | Chuyển hồ sơ sang **Đã nghỉ**, giữ dữ liệu lịch sử và yêu cầu thiết bị xóa mẫu vân tay. |
| `Hủy` trong hộp thoại | Đóng hộp thoại, không gửi thay đổi. |

Lệnh đăng ký/xóa có thể lần lượt ở trạng thái chờ, đang xử lý, hoàn tất hoặc lỗi. Thiết bị phải online và đúng `DEVICE_ID`. Việc hoàn tất đăng ký do **ESP8266** ghi vào Firestore; app Admin không phải chạy nền để kết thúc bước này.

### 3. Phòng ban

Vào **Tác vụ → Phòng ban**.

| Nút/thao tác | Kết quả |
| --- | --- |
| Bộ lọc `Tất cả` / đang hoạt động / đã ngừng | Chọn nhóm phòng ban muốn xem. |
| `Thêm phòng ban` → `Lưu` | Tạo phòng ban từ tên nhập vào. |
| Biểu tượng sửa → `Lưu` | Đổi tên phòng ban. |
| Công tắc `Đang hoạt động` | Bật/tắt khả năng dùng phòng ban cho thao tác mới; hồ sơ cũ vẫn được giữ. |
| `Hủy` | Đóng biểu mẫu mà không lưu. |

### 4. Lịch và ca làm

Vào **Phân ca** hoặc **Tác vụ → Lịch/Ca làm**. Ca sáng và ca chiều là ca chính; tăng ca cố định **18:00–22:00**.

| Nút/thao tác ở **Lịch** | Kết quả |
| --- | --- |
| `‹ Tuần trước`, `Tuần sau ›`, `Tuần này` | Chọn tuần đang xem và thao tác. |
| `Tuần` / `Tháng` | Đổi cách xem lịch. |
| Chạm ô nhân viên/ngày trong lưới tuần hoặc một ngày trong lưới tháng | Mở biểu mẫu phân ca cho đúng ngày; chọn nhân viên hoặc phòng ban, ca và lưu. |
| `Phân cho nhân viên` → `Lưu phân ca` | Chọn nhân viên, ngày thứ Hai–thứ Bảy, ca chính và giờ nếu cần; lưu lịch cho những lựa chọn đó. |
| `Phân cho phòng ban` | Mở biểu mẫu phân ca theo phòng ban cho ngày và ca được chọn. |
| `Sao chép tuần trước` | Sao chép lịch tuần trước sang tuần đang chọn theo quy tắc của màn hình. |
| `Duyệt hàng loạt N đơn đang chờ` | Duyệt các đăng ký lịch tuần còn chờ và chuyển các ca đăng ký thành lịch làm việc. |
| `Xử lý đơn` → `Duyệt` | Duyệt một đăng ký lịch tuần. |
| `Xử lý đơn` → nhập phản hồi → `Yêu cầu sửa` | Gửi lý do cho nhân viên để họ chỉnh sửa và gửi lại. |
| `Đóng` / `Hủy` | Rời hộp thoại không áp dụng thao tác đang nhập. |

Khi tuần mục tiêu đổi, app cập nhật listener đăng ký lịch; không cần đăng nhập lại để nhìn thấy đơn của tuần mới. Phân cho phòng ban cho phép chọn cả sáng và chiều. Mỗi lịch ngày được đọc và gộp trong transaction: phân thêm chiều giữ ca sáng; phân lại cùng loại thay ca đó, không tạo trùng. Giờ công điều chỉnh và ghi chú cũ được giữ, kể cả khi hai Admin phân hai loại ca đồng thời.

| Nút/thao tác ở **Ca làm** | Kết quả |
| --- | --- |
| `Thêm ca tăng ca` → chọn nhân viên, ngày, lý do → `Phân ca` | Admin phân trực tiếp một ca tăng ca cố định 18:00–22:00 cho nhân viên. |
| `Chỉnh sửa` → `Lưu ca` | Cập nhật **ca bổ sung**: tên, loại, giờ, thời gian cho phép, giờ nghỉ, thời gian áp dụng và cờ tính tăng ca. Ca sáng/chiều mặc định không có nút này. |
| `Hủy` | Đóng biểu mẫu. |

Đường **nhân viên đăng ký tăng ca rồi Admin duyệt** nằm ở **Đơn từ**; đường **Admin phân ca tăng ca trực tiếp** nằm ở **Ca làm**. Khi kiểm tra lương, hãy xem ca tăng ca và lượt vào/ra thực tế của đúng ngày.

### 5. Đơn từ và tăng ca

Vào **Đơn từ** trên thanh dưới. Danh sách đơn thông thường và khu **Đăng ký tăng ca** nằm trên cùng màn hình.

| Nút/thao tác | Kết quả |
| --- | --- |
| `Tất cả`, `Chờ duyệt`, `Đã duyệt`, `Từ chối`, `Đã hủy` | Lọc đơn thông thường theo trạng thái. |
| `Duyệt` trên đơn đang chờ | Ghi quyết định duyệt; các màn hình lịch/công áp dụng theo loại đơn. |
| `Từ chối` → nhập `Lý do từ chối` → `Từ chối` | Ghi quyết định và phản hồi cho nhân viên; lý do bắt buộc. |
| Bộ lọc và `Duyệt`/`Từ chối` trong **Đăng ký tăng ca** | Xử lý riêng đơn tăng ca; từ chối cần lý do. Đơn được duyệt trở thành ca tăng ca hiệu lực 18:00–22:00 trong Android. |

Đơn tăng ca **chờ duyệt** có thể vẫn thấy lượt quét nhưng chưa được tính giờ/tiền tăng ca. Đơn đã duyệt chỉ có giờ tăng ca khi có lượt vào và ra hợp lệ.

### 6. Chấm công và xử lý ngoại lệ

Vào **Tác vụ → Chấm công**, hoặc **Tổng quan → Xem tất cả**.

| Nút/thao tác | Kết quả |
| --- | --- |
| `Tất cả`, `Hôm nay`, `Hôm qua`, `Tuần này`, `Tháng này`, `Ngày cụ thể`, `Khoảng tùy chọn` | Lọc theo thời gian; với khoảng tùy chọn nhập đủ ngày đầu và cuối. |
| Bộ lọc phòng ban, nhân viên, trạng thái và loại lượt chấm | Thu hẹp các lượt đang hiển thị. |
| `Duyệt ngoài lịch` / `Xử lý chấm ngoài lịch` | Chọn ca chính, quyết định `Duyệt` hoặc `Từ chối`, nhập lý do rồi xác nhận. |
| Quyết định `Duyệt` ngoài lịch | Android gán các lượt phù hợp vào ca đã chọn rồi tính công; thiếu lượt vào hoặc ra thì ca đó vẫn **0 giờ**. |
| Quyết định `Từ chối` ngoài lịch | Giữ lượt quét ở trạng thái bất thường, không tính thành ca hợp lệ. |
| `Xác nhận / Xóa` → `Vào ca` hoặc `Ra ca` → `Xác nhận` | Tạo bản ghi sửa phân loại cho lượt quét, yêu cầu lý do. |
| `Xác nhận / Xóa` → `Xóa lượt quét` | Loại lượt quét khỏi **kết quả tính công**, giữ bản ghi gốc để tra cứu/audit. |
| `Điều chỉnh` → `Lưu điều chỉnh` | Admin nhập giờ vào, giờ ra, số giờ công cần sửa và **lý do bắt buộc**; lưu bản điều chỉnh riêng. |

Giờ điều chỉnh dùng `yyyy-MM-dd HH:mm`. Để trống một ô thời gian nghĩa là giữ giá trị cũ, không phải xóa giá trị đó. Các quyết định và điều chỉnh được áp vào dữ liệu hiệu lực; lượt quét gốc không bị ghi đè.

### 7. Có mặt và Thiết bị

**Tác vụ → Có mặt** là màn hình theo dõi, không sửa dữ liệu. `‹ Ngày trước` / `Ngày sau ›` đổi ngày; các chip trạng thái lọc danh sách và số lượng nhân viên đang có mặt, vắng, nghỉ hoặc bất thường.

**Tác vụ → Thiết bị** hiển thị kết nối, heartbeat, firmware, số mẫu, hàng đợi đồng bộ, lượt chấm mới nhất và trạng thái lệnh.

| Nút/thao tác ở **Thiết bị** | Kết quả |
| --- | --- |
| Biểu tượng `Sửa cấu hình` → `Lưu` | Đổi tên và vị trí thiết bị. |
| `Kiểm tra LED xanh`, `Kiểm tra LED đỏ`, `Kiểm tra còi` | Gửi lệnh kiểm tra phần cứng tương ứng. |
| `Mở cửa`, `Đóng cửa` | Gửi lệnh điều khiển servo cửa nếu thiết bị hỗ trợ. |
| `Đồng bộ chấm công` | Yêu cầu thiết bị đẩy các lượt đang nằm trong hàng đợi. |
| `Khởi động lại` → xác nhận | Gửi lệnh khởi động lại thiết bị. |

Mỗi thiết bị chỉ xử lý một lệnh đang chờ tại một thời điểm; xem kết quả hoặc lỗi ngay dưới khu điều khiển. Nếu offline, kiểm tra Wi-Fi, nguồn, mã thiết bị và bộ nhớ hàng đợi.

### 8. Lương, Hiệu suất, Bảng công tháng

| Màn hình / nút | Kết quả |
| --- | --- |
| **Tác vụ → Lương** → nhập `Tháng lương (yyyy-MM)` | Chọn kỳ lương và xem các phiếu đã lưu. |
| `Lập phiếu lương / thiết lập lương` → chọn nhân viên → `Đặt lương` → `Lưu đơn giá giờ` | Lưu đơn giá cơ bản theo giờ cho nhân viên đang làm. |
| Chọn nhân viên → `Lập phiếu` | Xem trước giờ ca chính, giờ tăng ca, thưởng KPI, khoản thưởng/khấu trừ và thực lĩnh. |
| `Lưu phiếu` | Chốt phiếu lương tháng thành bản lưu lịch sử; nhân viên có thể xem trên app. |
| **Tác vụ → Hiệu suất** → chọn tháng | Xem số ca/giờ tăng ca, số lần đi trễ và các khoản thưởng/phạt tự động; màn hình chỉ đọc. |
| **Tác vụ → Bảng công tháng** → `Tháng trước` / `Tháng sau` | Xem tổng hợp công theo tháng báo cáo được chọn. |
| `Xem chi tiết theo ngày và ca` / `Ẩn chi tiết theo ca` | Mở hoặc thu gọn các dòng ngày và ca của từng nhân viên. |

Nhân viên đã nghỉ vẫn xuất hiện ở **tháng lịch sử phù hợp** và có thể lập phiếu đến **tháng nghỉ**; từ tháng sau không còn là đối tượng vận hành mới. Phiếu đã lưu giữ nguyên lịch sử khi dữ liệu sống thay đổi.

### 9. Báo cáo, Nhật ký và Thông báo

| Màn hình / nút | Kết quả |
| --- | --- |
| **Tác vụ → Báo cáo** → `Tuần đang chọn` / `Tháng này` | Điền nhanh khoảng ngày. Có thể tự nhập `Từ ngày`, `Đến ngày`, mã nhân viên và phòng ban. |
| Chip `Chấm công`, `Ngày công`, `Trễ/sớm`, `Nghỉ phép`, `Tăng ca`, `Thiết bị` | Chọn loại báo cáo. |
| `Xuất CSV và chia sẻ` | Tạo tệp CSV từ dữ liệu đã tải và mở bảng chia sẻ của Android. |
| `Thử lại` | Tải lại khi truy vấn lịch sử lỗi. Nếu app báo vượt giới hạn tải, thu hẹp khoảng ngày trước khi xuất. |
| **Tác vụ → Nhật ký** → ô `Lọc hành động/đối tượng` | Tìm theo thao tác, đối tượng, người thực hiện hoặc nội dung. Nhật ký chỉ đọc. |
| **Tác vụ → Thông báo** → chọn `Tất cả nhân viên` hoặc phòng ban → `Gửi thông báo` | Gửi thông báo có tiêu đề và nội dung; xem lại tại “Lịch sử đã gửi”. |

## Chức năng nhân viên

### 1. Trang chủ

Trang chủ cho biết ca hôm nay, giờ chấm vào/ra, số giờ làm, tăng ca, số lần đi trễ, ngày công và thông báo.

| Nút/thao tác | Kết quả |
| --- | --- |
| Dòng `Ca làm việc` | Mở **Lịch làm việc**. |
| `Xem thêm` ở “Công việc hôm nay”, dòng `Trạng thái chấm công` hoặc `Xem tất cả` | Mở **Chấm công của tôi**. |
| Biểu tượng chuông | Xem thông báo. |
| Tiện ích `Đơn báo` / `Hỗ trợ` | Mở **Đơn từ**. |
| Tiện ích `Thông tin` / `Thâm niên` | Mở **Cá nhân**. |
| `Lịch họp`, `Tin tức`, `Khen thưởng`, `Tài liệu` | Hiện thông báo chưa có chức năng tương ứng trong bản hiện tại. |

### 2. Lịch làm việc và đăng ký tuần

Vào **Lịch làm việc**. Phần trên là lịch đã phân và trạng thái tăng ca; phần đăng ký là cho **tuần sau**.

| Nút/thao tác | Kết quả |
| --- | --- |
| `Tuần` / `Tháng`, mũi tên `Kỳ trước` / `Kỳ sau` | Đổi khoảng lịch đang xem. |
| `Ca sáng cả tuần`, `Ca chiều cả tuần`, `Cả ngày` | Chọn nhanh ca cho các ngày thứ Hai–thứ Bảy của tuần đăng ký. |
| `Sao chép tuần này` | Lấy lựa chọn ca từ lịch tuần hiện tại làm bản nháp tuần sau. |
| `Xóa chọn` | Bỏ toàn bộ lựa chọn trong bản nháp. |
| Chip `Sáng` / `Chiều` từng ngày | Chọn hoặc bỏ ca của ngày đó; có thể nhập ghi chú. |
| `Gửi đăng ký` | Nộp lịch tuần sau để Admin duyệt. |
| `Gửi lại / cập nhật đăng ký` | Sửa đơn còn được phép sửa hoặc gửi lại sau khi Admin yêu cầu chỉnh sửa. |

Chỉ **đơn được duyệt** mới chuyển thành lịch làm việc. App tự chuyển sang listener của tuần mục tiêu mới khi tuần thay đổi; không cần đóng/mở lại app.

### 3. Chấm công của tôi và Bảng lương

| Nút/thao tác | Kết quả |
| --- | --- |
| Mũi tên `Tháng trước` / `Tháng sau` ở **Chấm công của tôi** | Đổi tháng xem giờ vào/ra, ca, giờ làm, tăng ca, đi trễ/về sớm và trạng thái từng ngày. |
| `Gửi yêu cầu điều chỉnh` | Mở **Đơn từ** để tạo đơn loại **Sửa chấm công**. |
| `Xem phiếu lương` | Mở **Bảng lương**. |
| Chạm một phiếu lương → `Đóng` | Xem chi tiết phiếu đã được Admin lưu, rồi đóng hộp thoại. |

### 4. Đơn từ và đăng ký tăng ca

Vào **Đơn từ**. Các loại đơn hiện có: **Nghỉ phép, Đi muộn, Về sớm, Ngoài văn phòng, Sửa chấm công, Đổi ca**.

| Nút/thao tác | Kết quả |
| --- | --- |
| `Tạo đơn` / `Đóng form` | Mở/thu biểu mẫu. |
| Chọn loại, ngày, lý do → `Gửi đơn` | Gửi đơn ở trạng thái chờ Admin xử lý. **Sửa chấm công** cần thời gian đề xuất; **Đổi ca** cần ca muốn đổi. |
| `Chọn cả ngày` / `Bỏ chọn` trong đơn nghỉ phép | Chọn/bỏ các ca nghỉ theo ngày đã có lịch. |
| `Hủy đơn đang chờ duyệt` | Hủy đơn **nghỉ phép** còn chờ; đơn đã được xử lý không có nút này. |
| `Gửi đăng ký tăng ca` | Gửi đơn cho ngày hôm nay hoặc ngày tương lai, khung cố định **18:00–22:00**, kèm lý do. Đơn hôm nay phải gửi trước 18:00. |

Khu tăng ca hiển thị đơn chờ, đã duyệt và bị từ chối cùng phản hồi của Admin. Được duyệt chưa đồng nghĩa có giờ công: vẫn cần quét vào và ra hợp lệ.

### 5. Cá nhân

| Nút/thao tác | Kết quả |
| --- | --- |
| Sửa điện thoại/địa chỉ → `Lưu thông tin liên hệ` | Cập nhật phần liên hệ trong hồ sơ cá nhân. |
| Nhập `Nội dung cần hỗ trợ` → `Gửi yêu cầu hỗ trợ` | Gửi yêu cầu liên quan đến vân tay cho Admin. |
| `Tải thêm lịch sử` | Tải thêm lượt hoạt động/chấm công cũ trong hồ sơ. |
| `Đổi mật khẩu` | Mở hộp thoại đặt mật khẩu mới. |

Thông tin chức vụ, mã nhân viên, phòng ban và trạng thái vân tay là phần hiển thị; nhân viên không tự sửa các dữ liệu quản trị này.

## Cách hệ thống tính công trên Spark

1. **Thiết bị nhận diện:** AS608 so mẫu đã đăng ký tại chỗ; cảm biến không tự tải mẫu từ Firebase. Khi Wi-Fi hoặc kết nối Firebase không khả dụng, firmware có thể **lưu lượt quét và mở cửa theo mẫu đang có trong AS608**, nếu đã có giờ NTP hợp lệ và lưu LittleFS thành công. Bản hiện tại `spark-anonymous-v14-command-status` giữ hành vi v12: chỉ mở cửa khi đúng mẫu lúc bật nguồn chưa có Wi-Fi; LCD báo `CHUA LUU CONG` và không tạo lượt chấm vì chưa có giờ. ESP8266 không lưu danh sách hay cache nhân viên; liên kết danh tính của lượt offline được tra khi kết nối trở lại. Lượt được lưu sẽ được gửi lại từng bản.
2. **Lưu lượt gốc:** ESP8266 dùng Firebase Anonymous Auth, ghi `attendance/{eventId}` dạng `SCAN/PENDING`. Thiết bị không tự quyết định đây là vào hay ra theo mốc 12 giờ.
3. **Android phân giải:** app ghép lượt quét với `workSchedules`, `shifts`, các điều chỉnh và quyết định duyệt. Nó chống quét trùng, xác định lượt vào/ra, phát hiện thiếu lượt ra và tính công theo ca.
4. **Ngoài lịch:** Admin tạo quyết định duyệt/từ chối. Trên Spark, quyết định có thể được lưu ban đầu ở trạng thái `PENDING`; Android đọc nó và áp dụng vào bản công hiệu lực. Duyệt sẽ bổ sung ca được chọn cho ngày đó để ghép các lượt quét; từ chối giữ ngoại lệ.
5. **Tăng ca:** đơn 18:00–22:00 được duyệt trở thành phiên tăng ca riêng trong Android. Chỉ cặp vào/ra hợp lệ mới tạo giờ tăng ca và đi vào bảng công, KPI, lương.
6. **Lịch sử:** báo cáo và bảng công dùng **tháng được chọn** để quyết định có hiển thị nhân viên đã nghỉ. Audit lưu người thao tác và lý do; các thao tác phù hợp ghi cùng giao dịch với thay đổi nghiệp vụ.

**Nghỉ phép và giờ công:** ca chính được duyệt nghỉ có 0 giờ làm thực tế, không tính đi trễ hoặc về sớm. Nghỉ sáng rồi làm chiều chỉ tính giờ và trễ/sớm của chiều. Ca tăng ca riêng vẫn tính khi được duyệt và có cặp vào/ra hợp lệ, kể cả ngày không có ca chính hoặc ngày nghỉ ca chính. Bảng công, báo cáo, lương và KPI dùng chung quy tắc; báo cáo và CSV lọc trễ/sớm bằng số phút nên giữ cả người vừa trễ vừa về sớm.

**Tải dữ liệu:** Dashboard giữ các cửa sổ lịch nhỏ theo tuần/tháng thay vì lắng nghe lịch mọi năm. Phiếu lương được đọc theo tháng. Khi xem kỳ cũ, app tải lượt quét theo trang và tải lịch, ca, nghỉ phép, tăng ca, điều chỉnh, phân loại và duyệt ngoài lịch của kỳ đó từ server. Có thêm ngày biên để ghép ca qua đêm; điều chỉnh được tìm theo ngày công, không theo ngày tạo. CSV và lưu lương chỉ được bật khi tải đủ đúng kỳ và phạm vi nhân viên; lỗi mạng hoặc vượt giới hạn 5.000 lượt quét sẽ yêu cầu tải lại hoặc thu hẹp kỳ.

**Phản hồi thiết bị:** “Đã lưu, chờ đồng bộ” khác với “đã xác nhận”, “bị từ chối” và “không lưu được”. Khi online, xác nhận của lượt đang xử lý trong thời hạn cho phép mở cửa. Khi kết nối không khả dụng và có giờ hợp lệ, chính sách AS608 cho phép lượt đang xử lý đã lưu bền vững mở cửa một lần; LCD báo `OFFLINE: DA LUU`, chưa phải xác nhận công từ server. Trường hợp chưa có giờ chỉ mở cửa và báo `CHUA LUU CONG`. Hàng đợi gửi từng bản và retry khi lỗi tạm thời. Lượt mới thay thế quyền mở cửa trong RAM của lượt cũ, kể cả khi lượt mới bị từ chối hoặc không lưu được; bản cũ vẫn nằm trong hàng đợi. Xác nhận muộn, sau khởi động lại hoặc của lượt đã mở offline chỉ đồng bộ dữ liệu, không mở cửa thêm. `SYNC_ATTENDANCE` giữ `PROCESSING` cho đến khi hàng đợi rỗng; timeout không xóa các bản đã lưu. Wi-Fi vừa kết nối lại cho hàng đợi một lần retry ngay khi cửa đã đóng; hàng đợi được ưu tiên trước đọc lệnh mới và heartbeat. Hoãn HTTPS không đẩy lùi hạn retry thêm 30 giây. Lỗi gửi kết quả lệnh khi hàng đợi đã rỗng không làm LCD báo nhầm còn công chờ gửi.

**Điều kiện và giới hạn offline:**

- Lượt chưa tra được liên kết chỉ lưu `{offlineScan, deviceId, templateId, confidence, timestamp}`, kèm mã sự kiện bền vững. Không lưu tên hay mã nhân viên trong lượt raw. Khi mạng trở lại, firmware tra liên kết đang được phép, chuyển thành `SCAN/PENDING` và giữ nguyên mã sự kiện, thời điểm quét, mã mẫu. Nếu server trả về liên kết bị vô hiệu hóa, đã xóa hoặc không hợp lệ, firmware từ chối lượt đó, không dùng offline để vượt qua quyết định từ chối.
- **Ghi công cần giờ NTP hợp lệ.** Sau mỗi lần bật nguồn hoặc reset, ESP cần kết nối Wi-Fi có Internet để lấy giờ trước khi lưu các lượt chấm mới. Khi đã đồng bộ giờ rồi mất mạng, giờ phần mềm tiếp tục chạy nếu ESP vẫn còn nguồn và không reset; lượt có thể lưu để gửi lại. Nếu bật nguồn chưa có Wi-Fi và chưa đồng bộ giờ, đúng mẫu chỉ mở cửa và báo `CHUA LUU CONG`; lượt đó không được lưu và không tạo công bằng giờ kết nối lại. Thời điểm quét được lấy trước lần tra liên kết mạng để mạng chậm không đẩy giờ công về sau.
- Hàng đợi LittleFS giới hạn **12 KB**, đọc từng bản đầu và sao chép phần còn lại theo khối, không nạp cả hàng đợi hay danh sách nhân viên vào RAM. Hết dung lượng, lỗi ghi hoặc không cấp được mã sự kiện trả `NOT_STORED`; lượt đó không mở cửa. Lỗi mạng giữ nguyên bản đã lưu để retry.
- **Nhân viên đã cho nghỉ vẫn có thể mở offline nếu mẫu chưa được xóa khỏi AS608.** Thao tác cho nghỉ trong app cập nhật `active: false`, vô hiệu hóa liên kết và tạo lệnh `DELETE_FINGERPRINT` cùng transaction. Thiết bị xóa mẫu khi có mạng và xử lý lệnh thành công; các lượt raw đồng bộ sau khi liên kết bị vô hiệu hóa sẽ bị từ chối, không tính công. Chỉ sửa trạng thái bằng Firebase Console không tạo lệnh xóa mẫu. Lệnh `FAILED` cần gửi lại; đăng ký/xóa mẫu chờ hàng đợi rỗng để tránh gán lại mã mẫu khi còn lượt cũ. Luồng hiện tại gửi lệnh tới thiết bị được liên kết trên nhân viên, không tự xóa các bản sao mẫu trên thiết bị khác.

**Cửa và đăng ký vân tay:** servo hoạt động theo từng bước trong `loop()`. Bộ đếm giữ cửa mở 5 giây bắt đầu khi servo đã đến vị trí mở hoàn toàn; thời gian servo quay về đóng được ghi riêng. Đăng ký vân tay và hiệu ứng không dùng các vòng chờ dài; HTTPS được hoãn khi cửa mở hoặc đang chuyển động. Hoãn là trạng thái chờ, không phải lỗi.

**Giới hạn cần biết:** kết quả phân giải trên Spark là bản hiệu lực trong Android, không được ghi ngược lên lượt `SCAN/PENDING` gốc. Ứng dụng hoặc hệ thống khác chỉ đọc raw Firestore sẽ không tự thấy cùng kết quả nếu không dùng cùng quy tắc phân giải. Thư mục `firebase/functions/` là đường backend tùy chọn cho Blaze, **không phải bước triển khai của Spark**.

## Cài đặt và triển khai

### Firebase

1. Tạo hoặc chọn dự án Firebase; `.firebaserc` hiện trỏ mặc định tới `chamcongiot-56ae5`. Bật **Authentication → Email/Password** cho app và **Anonymous** cho ESP8266; tạo Cloud Firestore.
2. Đặt tệp cấu hình Android tải từ Firebase Console tại `app/google-services.json`.
3. Tạo tài khoản Admin đầu tiên trong Firebase Authentication, sau đó tạo `users/{uid}` với `role: "ADMIN"` và `active: true` bằng Firebase Console hoặc Admin SDK. Quy tắc Firestore yêu cầu hồ sơ này trước khi app có quyền Admin.
4. Triển khai Rules và indexes trong thư mục `firebase/` (Firebase CLI đã đăng nhập đúng dự án):

   ```powershell
   firebase deploy --only firestore --project chamcongiot-56ae5
   ```

   Nếu dùng dự án Firebase khác, sửa `.firebaserc`, `app/google-services.json` và `FIRESTORE_BASE_URL` trong cấu hình firmware cho cùng một project. Không chạy lệnh deploy Functions khi giữ gói Spark.

### Android

Yêu cầu Android SDK/JDK tương thích cấu hình Gradle của dự án. Mở thư mục gốc bằng Android Studio, đồng bộ Gradle, sau đó chạy trên thiết bị Android hoặc tạo APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK debug nằm tại `app/build/outputs/apk/debug/app-debug.apk`. Bản app mới cần được cài trên máy Admin **và** nhân viên để hai vai trò dùng cùng cách phân giải trên Spark. Chỉ build/cài app không tự cập nhật Firestore Rules hay firmware trên ESP8266.

### ESP8266 + AS608/R307

1. Sao chép `firmware/esp8266_fingerprint/secrets.h.example` thành `secrets.h` cùng thư mục; điền Wi-Fi, Firebase Web API key và Firestore URL của project. Không commit `secrets.h`.
2. Mở `firmware/esp8266_fingerprint/esp8266_fingerprint.ino` bằng Arduino IDE/PlatformIO với board ESP8266 và các thư viện được include trong sketch. Kiểm tra `DEVICE_ID` (mặc định `GATE-01`) khớp mã nhập trong app.
3. Nạp firmware mới lên từng thiết bị. Đăng ký vân tay trên app rồi đặt ngón tay hai lần; kiểm tra trạng thái lệnh, mã mẫu trên nhân viên và lượt quét thực tế.

| Phần cứng | Chân ESP8266 theo sketch |
| --- | --- |
| AS608/R307 TX → RX của ESP, RX → TX của ESP | D5, D6 |
| LED xanh, LED đỏ, còi | D1, D2, D7 |
| Nút cửa, tín hiệu servo | D0, D8 |
| LCD I²C SDA, SCL | D3, D4 |

Cấp nguồn đúng cho cảm biến/servo và nối chung GND. Servo nên có nguồn 5 V riêng đủ dòng; kiểm tra sơ đồ điện thực tế trước khi nạp và vận hành.

### Đồng bộ giờ trước khi chấm công ngoại tuyến

1. Nạp cả sketch v14, kiểm tra Serial 9600 baud hiện `FW: spark-anonymous-v14-command-status`. Giữ LittleFS khi nạp nếu còn lượt chờ gửi. Với lệnh đồng bộ, kiểm tra `Cap nhat lenh: HTTP 200` và trạng thái `COMPLETED` trên app; còi/đèn xanh chỉ báo thành công sau khi ghi được kết quả lệnh. Cờ `applied=true` của lệnh thông thường không thay thế trạng thái hoàn tất.
2. Cho thiết bị kết nối Wi-Fi **có Internet** để lấy giờ NTP, rồi đối chiếu ngày/giờ trên LCD với giờ Việt Nam. Chỉ thấy Wi-Fi hoặc Firebase đăng nhập thành công chưa đủ để kết luận đã có giờ hợp lệ.
3. Giữ nguồn ESP, ngắt Wi-Fi và quét mẫu đã đăng ký: lượt được lưu trước khi mở cửa; LCD báo `OFFLINE: DA LUU`.
4. Cho có mạng trở lại: hàng đợi gửi từng bản, giữ thời điểm quét ban đầu và không mở cửa thêm khi xác nhận muộn.

Nếu tắt nguồn/reset rồi bật lại khi chưa có Wi-Fi, ESP chưa biết giờ: đúng mẫu chỉ mở cửa, giữ 5 giây và báo `CHUA LUU CONG`. Lượt này không có bản để gửi lại; cần NTP thành công trước các lượt chấm tiếp theo. Các lượt đã lưu trước đó trong LittleFS vẫn được giữ và đồng bộ khi có mạng.

## Cấu trúc mã nguồn

| Đường dẫn | Vai trò |
| --- | --- |
| [`app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`](app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt) | Đăng nhập, điều hướng và các hộp thoại chung. |
| [`app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`](app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt), [`MainViewModelSubscriptions.kt`](app/src/main/java/vn/chamcong/iot/ui/MainViewModelSubscriptions.kt) | Ý định nghiệp vụ và các listener Firestore theo vai trò/tuần. |
| [`app/src/main/java/vn/chamcong/iot/ui`](app/src/main/java/vn/chamcong/iot/ui) | Màn hình Admin và nhân viên theo thư mục chức năng. |
| [`app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt`](app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt) | Quy tắc phân giải lượt chấm và áp dụng duyệt ngoài lịch trên Spark. |
| [`app/src/main/java/vn/chamcong/iot/data`](app/src/main/java/vn/chamcong/iot/data) | Repository đọc/ghi Firebase. |
| [`firebase/firestore.rules`](firebase/firestore.rules), [`firebase/firestore.indexes.json`](firebase/firestore.indexes.json) | Phân quyền và indexes Firestore. |
| [`firmware/esp8266_fingerprint`](firmware/esp8266_fingerprint) | Sketch, xử lý vân tay, lệnh thiết bị và hàng đợi chấm công. |

## Lưu ý khi vận hành

- **Không thấy công sau khi quét:** lượt báo `DA LUU` / `CHO DONG BO` cần có mạng trở lại để tra danh tính và đồng bộ, chưa hiện công ngay. Nếu LCD báo `CHUA LUU CONG`, ESP chưa có giờ hợp lệ nên lượt đó chỉ mở cửa, không được lưu hoặc gửi lại; cần đồng bộ giờ trước các lượt chấm tiếp theo. Kiểm tra đúng ngón tay đã đăng ký, hàng đợi chưa đầy, lịch/ca đã được duyệt và đã có đủ lượt vào/ra. Mở **Thiết bị** và **Chấm công** để xem trạng thái cụ thể.
- **Duyệt ngoài lịch hoặc tăng ca nhưng giờ vẫn bằng 0:** kiểm tra ca/ngày đã chọn và cặp quét vào/ra hợp lệ. Đơn chờ duyệt chưa tính thành giờ tăng ca.
- **Không thấy đơn lịch tuần:** kiểm tra tuần đang chọn, tuần mục tiêu của nhân viên, hạn nộp và trạng thái đơn. Listener tự theo tuần mới khi ngày đổi.
- **Không thấy nhân viên đã nghỉ:** bật công tắc ở **Nhân viên**; với báo cáo và bảng công, chọn đúng tháng lịch sử. Không tạo ca mới hoặc phiếu lương cho các tháng sau tháng nghỉ.
- **Báo cáo CSV thiếu dòng:** nếu màn hình cảnh báo đã chạm giới hạn tải lịch sử, thu hẹp khoảng ngày rồi xuất lại.

## Kiểm thử hồi quy

Chạy build và kiểm thử Android:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Các bài kiểm thử bao phủ gộp ca không trùng và bảo toàn điều chỉnh, ca chính 4 giờ + tăng ca 4 giờ, ngày chỉ tăng ca, nghỉ sáng/làm chiều, vừa trễ/về sớm, ca qua đêm ở cuối tháng, điều chỉnh tạo muộn và điều kiện tải đủ trước khi xuất/lưu. Kiểm thử transaction hai Admin trong `firebase/test/departmentScheduleTransactions.test.js` cần Firestore emulator với rules của dự án; chỉ dùng project demo, không chạy trên Firebase thật.

Chạy kiểm thử firmware trên máy phát triển (cần Node.js):

```powershell
node --test firmware/test/*.test.cjs
```

Kiểm thử thực thi các hàm của sketch/header qua adapter kiểu dữ liệu/API: đóng cửa sau 5 giây, đăng ký từng bước và phản hồi LCD. Lượt có giờ phải lưu trước mở cửa; khởi động chưa có giờ chỉ mở cửa và không tạo công; không cache danh tính; hàng đợi đầy, từ chối liên kết, giữ thời điểm quét khi mạng chậm, retry khi kết nối lại, đồng bộ từng bản, LCD theo đúng hàng đợi, không mở lại sau khởi động và chờ hàng đợi trước khi đổi mẫu. Kiểm thử lệnh xác nhận PATCH trạng thái cuối, giữ kết quả để retry khi lỗi và không báo thành công chỉ vì `applied=true`.

Sau khi biên dịch firmware, cần nạp và đo trực tiếp trên ESP8266:

| Bài thử trên thiết bị | Điều kiện đạt |
| --- | --- |
| Mở cửa, đồng thời gửi lệnh đăng ký rồi không đặt ngón tay | Bắt đầu đóng khoảng 5 giây sau khi mở hoàn toàn; ghi riêng thời gian quay về đóng. |
| Mở cửa trong lúc mạng chậm | Cửa vẫn đóng đúng thời hạn; HTTPS chỉ chạy khi cửa đã đóng. |
| Tạo nhiều sự kiện, gây lỗi mạng rồi kết nối lại | Hàng đợi không mất bản đã lưu; gửi lần lượt; `SYNC_ATTENDANCE` chỉ hoàn tất khi rỗng. |
| Xác nhận lượt cũ, xác nhận muộn hoặc khởi động lại khi còn hàng đợi | Dữ liệu đồng bộ nhưng cửa không mở do các xác nhận đó. |
| Lỗi ghi LittleFS và phản hồi từ chối của server | Hiển thị đúng từng trạng thái; không báo nhầm đã xác nhận. |
| Đồng bộ giờ rồi ngắt Wi-Fi, quét vào/ra và kết nối lại | Mở cửa sau khi lưu; LCD `OFFLINE: DA LUU`; dữ liệu lên đúng mã mẫu và thời điểm ban đầu, không mở cửa lần nữa. |
| Bật nguồn/reset khi chưa có Wi-Fi và chưa đồng bộ NTP | Đúng mẫu chỉ mở cửa và giữ 5 giây; LCD `CHUA LUU CONG`; không tạo công hoặc gửi công giả khi có mạng lại. |
| Có giờ hợp lệ nhưng hàng đợi đầy/lỗi ghi | Lượt không lưu được không mở cửa; các bản đã lưu trước đó vẫn còn. |
| Cho nhân viên nghỉ khi thiết bị offline, rồi kết nối lại | Trước khi nhận lệnh xóa, mẫu AS608 còn có thể mở offline; sau khi xử lý xóa thành công, mẫu không còn khớp. Lượt đồng bộ với liên kết đã vô hiệu hóa bị từ chối. |

Kiểm thử mô phỏng kiểm tra logic trạng thái và thời hạn; kết quả đó không thay thế phép đo servo, nguồn cấp, cảm biến và mạng trên thiết bị thật.
