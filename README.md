# Quản lý công việc kết hợp chấm công IoT bằng vân tay

Ứng dụng Android dành cho **Admin** và **nhân viên**, kết hợp ESP8266 với cảm biến AS608/R307 và Firebase. Tài liệu này mô tả đường đi đến từng màn hình, ý nghĩa các nút và điều gì xảy ra sau khi thao tác. Dự án đang vận hành theo **Firebase Spark**: không cần Cloud Functions để đăng ký vân tay hoặc để Android hiển thị công đã phân giải.

Cập nhật tài liệu ngày **09/10/2026** theo mã nguồn hiện tại. [Báo cáo đồ án](BAO_CAO_DO_AN_CHAM_CONG_IOT.txt) trình bày kiến trúc, thiết kế dữ liệu, Use Case và đánh giá sản phẩm.

**Trạng thái bản cập nhật 09/10/2026:** đã bổ sung đối tượng Công việc, giao việc theo ca, báo cáo kết quả, duyệt/làm lại, lịch sử phiên bản, cập nhật đồng thời và hiệu suất theo việc. Đã cập nhật Firestore Rules/index và sửa lỗi queue IoT/danh tính lượt chấm. Cần deploy Rules/index mới và cài APK mới trước khi dùng công việc online; đối chiếu Firebase ngày 08/10/2026 thuộc bản trước. Hướng dẫn, sơ đồ dữ liệu, Use Case và kết quả kiểm thử bổ sung nằm ở [Quản lý công việc](docs/QUAN_LY_CONG_VIEC.md).

## Mục lục

- [Luồng hoạt động chung](#luồng-hoạt-động-chung)
- [Đăng nhập và điều hướng](#đăng-nhập-và-điều-hướng)
- [Công việc và kết quả](#công-việc-và-kết-quả)
- [Chức năng Admin](#chức-năng-admin)
- [Chức năng nhân viên](#chức-năng-nhân-viên)
- [Cách hệ thống tính công trên Spark](#cách-hệ-thống-tính-công-trên-spark)
- [Cài đặt và triển khai](#cài-đặt-và-triển-khai)
- [Cấu trúc mã nguồn](#cấu-trúc-mã-nguồn)
- [Lưu ý khi vận hành](#lưu-ý-khi-vận-hành)
- [Kiểm thử hồi quy](#kiểm-thử-hồi-quy)

## Luồng hoạt động chung

```text
Admin tạo phòng ban, nhân viên, ca và lịch
  ├─ Tạo tài khoản đăng nhập cho nhân viên (tùy chọn)
  └─ Gửi lệnh đăng ký vân tay → ESP8266 lấy mẫu 2 lần → thiết bị hoàn tất trên Firestore
Nhân viên đăng nhập → đăng ký lịch tuần sau / gửi đơn / đăng ký tăng ca
Admin duyệt lịch, đơn từ, tăng ca
Admin tạo/giao công việc, chọn người thực hiện và ca liên quan (tùy chọn)
Nhân viên quét vân tay → ESP8266 nhận diện → lưu lượt SCAN/PENDING lên Firestore
Android ghép lượt quét với lịch, ca và quyết định duyệt → hiển thị công hiệu lực
Nhân viên mở Việc của tôi → bắt đầu → cập nhật tiến độ → gửi kết quả
Admin đối chiếu yêu cầu, báo cáo, có mặt trong ca → duyệt hoàn thành hoặc yêu cầu làm lại
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

Thanh dưới của Admin: **Tổng quan · Công việc · Tiện ích · Phân ca · Nhân viên**. Mục **Tiện ích** mở các màn hình vận hành, lương, báo cáo và quản trị. Thanh dưới của nhân viên: **Trang chủ · Việc của tôi · Lịch làm việc · Tiện ích · Cá nhân**. **Việc của tôi** mở danh sách công việc được giao. **Tiện ích** tập hợp đơn từ, đăng ký ca, bảng lương và các tiện ích theo nhóm; **Bảng lương** cũng mở được từ **Trang chủ**, **Chấm công của tôi** hoặc menu tài khoản.

Phản hồi kết quả thao tác và lỗi chung hiển thị khoảng 5 giây rồi tự ẩn. Thông báo đã gửi và lịch sử nghiệp vụ được lưu trong các mục tương ứng để xem lại.

## Công việc và kết quả

**Admin → Công việc → Tạo và giao việc** nhập tên, mô tả, yêu cầu kết quả, người thực hiện, thời gian bắt đầu, deadline, ưu tiên và ca tùy chọn. **Phân ca → Lịch → Giao công việc theo ca** điền sẵn người và ca đã chọn. Ngày/giờ công việc nhập theo `dd/MM/yyyy HH:mm`, giờ Việt Nam.

**Nhân viên → Việc của tôi → chi tiết** chọn **Bắt đầu thực hiện**, sau đó nhập **Tiến độ và báo cáo kết quả** để **Lưu tiến độ** hoặc **Gửi kết quả chờ duyệt**. **Admin** xem báo cáo rồi **Duyệt hoàn thành** hoặc **Yêu cầu làm lại** có lý do. Vòng đời: **Được giao → Đang thực hiện → Chờ duyệt → Hoàn thành**.

Danh sách có tìm kiếm và bộ lọc trạng thái, ưu tiên, thời hạn. Chi tiết có lịch sử ai thay đổi gì/lúc nào và lượt quét hợp lệ đúng ca. **Quét vân tay ghi nhận có mặt; kết quả và bước duyệt xác nhận hoàn thành.** Quá hạn đang làm/chờ duyệt và hoàn thành muộn được phân biệt; thời điểm hoàn thành là lúc quản lý duyệt. Khi hai người cập nhật cùng phiên bản, một thao tác thành công, thao tác còn lại báo xung đột và giữ nội dung form.

**Tiện ích → Hiệu suất/Báo cáo → Công việc** xem số việc có hạn trong kỳ, đã hoàn thành/đang làm/chờ duyệt, đúng hạn/muộn, quá hạn và làm lại. Tỷ lệ đúng hạn chỉ tính trên việc đã hoàn thành. Xem [thiết kế dữ liệu, Use Case, kiểm thử và triển khai](docs/QUAN_LY_CONG_VIEC.md).

## Chức năng Admin

### 1. Tổng quan và Tiện ích

**Tổng quan** hiển thị việc hôm nay, sắp đến hạn, quá hạn, chờ duyệt, cùng tình hình có mặt, người đi trễ, lượt quét, thiết bị và thông báo.

Nút chọn tuần nằm dưới tiêu đề **Tổng quan hệ thống**. Thẻ **Theo dõi công việc** nằm sau các thông tin chấm công hôm nay và trước biểu đồ tuần. Số người đã chấm, đi trễ, chưa chấm trong tuần được gộp vào thẻ biểu đồ; không còn khối Tổng quan tuần riêng.

| Nút/thao tác | Kết quả |
| --- | --- |
| `‹ Tuần trước`, `Tuần này`, `Tuần sau ›` | Đổi tuần của biểu đồ và số liệu tuần; các chỉ số có nhãn Hôm nay vẫn tính theo ngày hiện tại. |
| `Xem tất cả` ở “Chấm công mới nhất” | Mở màn hình **Chấm công** để lọc và xử lý chi tiết. |
| `Đã đọc` trên thông báo | Đánh dấu thông báo đã đọc. |
| Thẻ chức năng trong **Tiện ích** | Mở màn hình tương ứng: **Đơn từ, Chấm công, Có mặt, Thiết bị, Phân ca, Ca làm, Lịch, Lương, Hiệu suất, Báo cáo, Bảng công tháng, Nhật ký, Phòng ban, Thông báo, Tiện ích nhân viên**. |

**Phân ca** trên thanh dưới là trang điều hướng nhanh đến **Ca làm**, **Lịch** và **Duyệt đăng ký tuần sau**. Mục **Cài đặt** chưa có màn hình thao tác trong bản hiện tại.

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
| `Sửa hồ sơ` → `Lưu hồ sơ` | Cập nhật thông tin nhân viên, gồm chức vụ và **Ngày vào làm** dạng `yyyy-MM-dd`. Ngày này được dùng để tính thâm niên. |
| `Thiết lập lương` | Nhập đơn giá lương theo giờ và lưu. |
| `Đăng ký vân tay` → nhập `Mã thiết bị` → `Gửi lệnh` | Tạo lệnh trên Firestore. Đặt **cùng một ngón tay hai lần** trên cảm biến; theo dõi trạng thái lệnh ngay tại thẻ nhân viên. |
| `Xóa vân tay` → `Xác nhận xóa` | Gửi lệnh xóa mẫu trên thiết bị, giữ hồ sơ và lịch sử lương/công. Có thể đăng ký lại sau khi xóa thành công. |
| `Xóa nhân viên` → `Xác nhận xóa` | Chuyển hồ sơ sang **Đã nghỉ**, giữ dữ liệu lịch sử và yêu cầu thiết bị xóa mẫu vân tay. |
| `Hủy` trong hộp thoại | Đóng hộp thoại, không gửi thay đổi. |

Lệnh đăng ký/xóa có thể lần lượt ở trạng thái chờ, đang xử lý, hoàn tất hoặc lỗi. Thiết bị phải online và đúng `DEVICE_ID`. Việc hoàn tất đăng ký do **ESP8266** ghi vào Firestore; app Admin không phải chạy nền để kết thúc bước này.

### 3. Phòng ban

Vào **Tiện ích → Phòng ban**.

| Nút/thao tác | Kết quả |
| --- | --- |
| Bộ lọc `Tất cả` / đang hoạt động / đã ngừng | Chọn nhóm phòng ban muốn xem. |
| `Thêm phòng ban` → `Lưu` | Tạo phòng ban từ tên nhập vào. |
| Biểu tượng sửa → `Lưu` | Đổi tên phòng ban. |
| Công tắc `Đang hoạt động` | Bật/tắt khả năng dùng phòng ban cho thao tác mới; hồ sơ cũ vẫn được giữ. |
| `Hủy` | Đóng biểu mẫu mà không lưu. |

### 4. Lịch và ca làm

Vào **Phân ca → Lịch** hoặc **Tiện ích → Lịch** để xem lịch đã phân và phân ca. Màn hình này giữ lưới tuần/tháng và các thao tác phân lịch. Ca sáng và ca chiều là ca chính; tăng ca cố định **18:00–22:00**. Khu xem và duyệt đăng ký lịch tuần của Admin nằm tại **Đơn từ → Đăng ký tuần**.

| Nút/thao tác ở **Lịch** | Kết quả |
| --- | --- |
| `‹ Tuần trước`, `Tuần sau ›`, `Tuần này` | Chọn tuần của lịch đã phân. |
| `Tuần` / `Tháng` | Đổi cách xem lịch. |
| Chạm ô nhân viên/ngày trong lưới tuần hoặc một ngày trong lưới tháng | Mở biểu mẫu phân ca cho đúng ngày; chọn nhân viên hoặc phòng ban, ca và lưu. |
| `Phân cho nhân viên` → `Lưu phân ca` | Chọn nhân viên, ngày thứ Hai–thứ Bảy, ca chính và giờ nếu cần; lưu lịch cho những lựa chọn đó. |
| `Phân cho phòng ban` | Mở biểu mẫu phân ca theo phòng ban cho ngày và ca được chọn. |
| `Sao chép tuần trước` | Sao chép lịch tuần trước sang tuần đang chọn theo quy tắc của màn hình. |
| `Đóng` / `Hủy` | Rời hộp thoại không áp dụng thao tác đang nhập. |

Phân cho phòng ban cho phép chọn cả sáng và chiều. Mỗi lịch ngày được đọc và gộp trong transaction: phân thêm chiều giữ ca sáng; phân lại cùng loại thay ca đó, không tạo trùng. Giờ công điều chỉnh và ghi chú cũ được giữ, kể cả khi hai Admin phân hai loại ca đồng thời.

| Nút/thao tác ở **Ca làm** | Kết quả |
| --- | --- |
| `Thêm ca tăng ca` → chọn nhân viên, ngày, lý do → `Phân ca` | Admin phân trực tiếp một ca tăng ca cố định 18:00–22:00 cho nhân viên. |
| `Chỉnh sửa` → `Lưu ca` | Cập nhật **ca bổ sung**: tên, loại, giờ, thời gian cho phép, giờ nghỉ, thời gian áp dụng và cờ tính tăng ca. Ca sáng/chiều mặc định không có nút này. |
| `Hủy` | Đóng biểu mẫu. |

Đường **nhân viên đăng ký tăng ca rồi Admin duyệt** nằm ở **Đơn từ**; đường **Admin phân ca tăng ca trực tiếp** nằm ở **Ca làm**. Khi kiểm tra lương, hãy xem ca tăng ca và lượt vào/ra thực tế của đúng ngày.

### 5. Đơn từ: đăng ký tuần, đơn khác và tăng ca

Vào **Đơn từ** trên thanh dưới của Admin. Màn hình có ba nhóm **Đăng ký tuần**, **Đơn khác** và **Tăng ca**; khi mở, mặc định chọn đăng ký tuần sau theo ngày hiện tại ở Việt Nam. Lối tắt **Phân ca → Duyệt đăng ký tuần sau** cũng mở màn hình này và nhóm **Đăng ký tuần**.

Mỗi đơn chiếm một dòng gọn với tên, mã NV/phòng ban, ngày hoặc loại đơn và trạng thái; đơn chờ duyệt xếp trước. Dùng ô **Tìm nhân viên** với gợi ý **Tên, mã NV, phòng ban** để tìm có dấu hoặc không dấu, rồi chọn chip trạng thái. Số lượng và số dòng đang hiển thị giúp đối chiếu kết quả lọc. Chạm một dòng ở bất kỳ trạng thái nào để mở chi tiết cuộn được; lý do, ghi chú và người duyệt vẫn được giữ đầy đủ. Các nút xử lý chỉ có trên đơn chờ duyệt đủ điều kiện.

| Nút/thao tác | Kết quả |
| --- | --- |
| `Đăng ký tuần` / `Đơn khác` / `Tăng ca` | Chọn nhóm yêu cầu muốn xử lý. |
| Ô `Tìm nhân viên` và chip trạng thái | Lọc theo tên, mã NV, phòng ban và trạng thái; không cần nhập dấu tiếng Việt. |
| Biểu tượng `Xem tuần trước` / `Xem tuần sau`, nút `Tuần kế tiếp` trong **Đăng ký tuần** | Đổi tuần yêu cầu; nút cuối chọn tuần sau của ngày hiện tại theo giờ Việt Nam. Listener cập nhật đúng tuần đang chọn. |
| `Tất cả`, `Chờ duyệt`, `Đã duyệt`, `Cần sửa` trong **Đăng ký tuần** | Lọc yêu cầu của tuần đang chọn; có số lượng trên từng chip. |
| `Tổng hợp` / `Thu gọn` | Mở hoặc thu gọn tên nhân viên chưa gửi và số đăng ký theo ngày/ca; lịch sáu ngày của từng người nằm trong chi tiết đơn. |
| Chạm dòng đăng ký tuần → `Chi tiết đăng ký tuần` | Xem sáu ngày, ca đã chọn, ghi chú, trạng thái và thông tin duyệt. Đơn đã xử lý vẫn xem được. |
| `Duyệt` / `Yêu cầu sửa` trong chi tiết đăng ký tuần | Duyệt một đơn hoặc nhập phản hồi tối đa 1.000 ký tự để yêu cầu nhân viên sửa và gửi lại. |
| `Duyệt toàn bộ N đơn chờ của tuần` → `Duyệt toàn bộ` | Mở xác nhận ghi rõ tuần; duyệt toàn bộ đơn chờ đủ điều kiện của tuần đó, kể cả đơn đang bị ẩn bởi tìm kiếm/bộ lọc. `Hủy` không gửi quyết định. |
| `Tất cả`, `Chờ duyệt`, `Đã duyệt`, `Từ chối`, `Đã hủy` trong **Đơn khác** | Lọc đơn thông thường theo trạng thái. |
| Chạm dòng đơn khác → `Chi tiết đơn` | Xem ngày, lý do, ca nghỉ, giờ điều chỉnh/ca đề nghị, đính kèm nếu có và người duyệt/phản hồi. |
| `Duyệt` / `Từ chối` trong chi tiết đơn | Ghi quyết định cho đơn chờ đủ điều kiện; từ chối cần lý do. `Hủy` ở bước nhập lý do quay lại chi tiết. |
| Chạm dòng tăng ca → `Chi tiết đăng ký tăng ca` | Xem ngày/giờ 18:00–22:00, lý do đăng ký, thời điểm gửi/xử lý, người duyệt và lý do từ chối. |
| `Tất cả`, `Chờ duyệt`, `Đã duyệt`, `Từ chối` trong **Tăng ca** | Lọc đơn tăng ca theo trạng thái. |
| `Duyệt` / `Từ chối` trong chi tiết tăng ca | Xử lý đơn chờ đủ điều kiện; từ chối cần lý do, `Quay lại` trở về phần xem. Đơn đã duyệt trở thành ca tăng ca hiệu lực 18:00–22:00 trong Android. |
| `Đóng` | Đóng chi tiết; khi đang gửi/lưu, các thao tác và việc đóng bị khóa. |

**Chọn đúng tuần yêu cầu:** ngày 08/10/2026, vào **Đơn từ → Đăng ký tuần** sẽ chọn tuần bắt đầu **12/10**, với phạm vi đăng ký từ thứ Hai đến thứ Bảy, hiển thị **Tuần 12/10 – 17/10/2026**. Bộ chọn tuần dùng chung trạng thái tuần với lịch Admin; khi đổi tuần, listener đọc yêu cầu của đúng tuần đó. Chuyển giữa ba nhóm giữ tuần đã chọn; bấm **Tuần kế tiếp** để quay về tuần sau tính từ hôm nay.

Tóm tắt tuần hiển thị số đã gửi/chờ duyệt; khi đã biết danh sách nhân viên hoạt động, dòng này có dạng **N đã gửi • N chờ duyệt • N chưa gửi**. Có thêm số đăng ký đang hiển thị sau lọc. Mở **Tổng hợp** khi cần xem tên còn thiếu và số người theo ca. Nút duyệt toàn bộ áp dụng cho **cả tuần**, không chỉ các dòng sau lọc, nên cần đọc phạm vi trong hộp xác nhận trước khi bấm.

Danh sách duyệt có **Đang tải đăng ký lịch tuần…**, lỗi **Không tải được đăng ký lịch tuần.** và nút **Thử lại** riêng; chỉ báo chưa có đăng ký sau khi snapshot đúng tuần thành công. Yêu cầu vẫn được hiển thị khi danh sách nhân viên chưa tải xong, nhưng thao tác duyệt chỉ bật cho nhân viên đang hoạt động đã được tải và xác minh. Một kiểm tra chỉ đọc ngày 08/10 đã xác nhận yêu cầu tuần **12/10** ở trạng thái `PENDING`, cùng audit `SHIFT_UPDATE` lúc **09:44:56 giờ Việt Nam**; bản app cũ mặc định truy vấn tuần **05/10**, nên có thể không thấy yêu cầu đã lưu.

Khu duyệt phía Admin nằm trong **Đơn từ**, với danh sách gọn và chi tiết tách riêng. Nguồn `weeklyScheduleRequests`, trạng thái `PENDING`/`NEEDS_REVISION`/`APPROVED`, transaction duyệt và audit giữ nguyên; Employee tiếp tục gửi tại **Lịch làm việc → Đăng ký tuần sau**. Không cần deploy Rules/indexes cho thay đổi giao diện này.

Đơn tăng ca **chờ duyệt** có thể vẫn thấy lượt quét nhưng chưa được tính giờ/tiền tăng ca. Đơn đã duyệt chỉ có giờ tăng ca khi có lượt vào và ra hợp lệ.

### 6. Chấm công và xử lý ngoại lệ

Vào **Tiện ích → Chấm công**, hoặc **Tổng quan → Xem tất cả**.

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

**Tiện ích → Có mặt** là màn hình theo dõi, không sửa dữ liệu. `‹ Ngày trước` / `Ngày sau ›` đổi ngày; các chip trạng thái lọc danh sách và số lượng nhân viên đang có mặt, vắng, nghỉ hoặc bất thường.

**Tiện ích → Thiết bị** hiển thị kết nối, heartbeat, firmware, số mẫu, hàng đợi đồng bộ, lượt chấm mới nhất và trạng thái lệnh.

Trạng thái online dựa trên heartbeat mới trong vòng 2 phút và thông tin kết nối do thiết bị gửi. Thiết bị có nguồn hoặc nhận được vân tay tại chỗ chưa chứng minh đang kết nối Firebase; thời gian lượt chấm mới nhất có thể là dữ liệu cũ. Khi heartbeat đã cũ, kiểm tra thời gian cập nhật và kết nối mạng của thiết bị.

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
| **Tiện ích → Lương** → nhập `Tháng lương (yyyy-MM)` | Chọn kỳ lương; đối chiếu tổng giờ đã lưu trên phiếu với giờ công hiện tại của tháng, gồm ca chính và tăng ca. |
| `Lập phiếu lương / thiết lập lương` → chọn nhân viên → `Đặt lương` → `Lưu đơn giá giờ` | Lưu đơn giá cơ bản theo giờ cho nhân viên đang làm. |
| Chọn nhân viên → `Lập phiếu` | Số giờ hiển thị ngay trong danh sách chọn nhân viên và trong phiếu; xem trước giờ ca chính, giờ tăng ca, thưởng KPI, khoản thưởng/khấu trừ và thực lĩnh. |
| `Tải lại giờ công tháng` / `Xem chi tiết … ngày` | Tải đủ dữ liệu của tháng, xem giờ vào/ra và số giờ từng ngày; khi chưa tải đủ, app chưa hiển thị số giờ hay cho lưu phiếu. |
| `Lưu phiếu` | Chốt phiếu lương tháng thành bản lưu lịch sử; nhân viên có thể xem trên app. |
| Phiếu đã lưu → `Tính lại phiếu` | Tải mới giờ công của tháng, xem số cũ và số mới theo đơn giá hiện tại; mặc định giữ thưởng/khấu trừ cũ. Nhập lý do rồi `Lưu phiếu tính lại` để cập nhật và giữ lịch sử trước/sau. |
| **Tiện ích → Hiệu suất** → chọn tháng | Xem số việc có hạn trong tháng, đã hoàn thành, đang làm, chờ duyệt, đúng hạn/muộn, làm lại và tỷ lệ đúng hạn trên việc đã hoàn thành; chấm công/tăng ca là phần hỗ trợ bật riêng. |
| **Tiện ích → Bảng công tháng** → `Tháng trước` / `Tháng sau` | Xem tổng hợp công theo tháng báo cáo được chọn. |
| `Xem chi tiết theo ngày và ca` / `Ẩn chi tiết theo ca` | Mở hoặc thu gọn các dòng ngày và ca của từng nhân viên. |

Nhân viên đã nghỉ vẫn xuất hiện ở **tháng lịch sử phù hợp** và có thể lập phiếu đến **tháng nghỉ**; từ tháng sau không còn là đối tượng vận hành mới. Phiếu đã lưu giữ nguyên lịch sử khi dữ liệu sống thay đổi.

Admin xác nhận **Vào ca/Ra ca** giúp phân loại lượt quét hợp lệ để tính công. Giờ tính lương cần cặp vào/ra và được tính theo khung ca, hoặc theo điều chỉnh công của Admin; xác nhận một lượt quét không tự cộng đủ giờ cả ca. App cảnh báo ngày đã chấm vào nhưng chưa chấm ra. **Tải lại giờ công tháng** chỉ cập nhật giờ công để đối chiếu. Muốn cập nhật phiếu đã lưu, Admin dùng **Tính lại phiếu**, kiểm tra số tiền, nhập lý do và lưu; mỗi lần lưu giữ lịch sử trước/sau cùng nhật ký. Nếu phiếu, giờ công hoặc đơn giá đã thay đổi trong lúc xem, app yêu cầu mở lại để kiểm tra. Nhân viên đã có phiếu trong tháng hiện **Tính lại phiếu** thay cho **Lập phiếu**, vẫn có thể đặt đơn giá cho nhân viên đang làm.

Ví dụ: phiếu cũ có 0 giờ, đơn giá 0 đ/giờ, thưởng 0 đ và khấu trừ 25.000 đ nên thực lĩnh là −25.000 đ. Khi tính lại với 4 giờ và 26.000 đ/giờ, giữ thưởng/khấu trừ cũ, lương cơ bản là 104.000 đ và thực lĩnh là 79.000 đ. Chỉ bấm tải lại giờ công không thay số tiền của phiếu.

### 9. Báo cáo, Nhật ký và Thông báo

| Màn hình / nút | Kết quả |
| --- | --- |
| **Tiện ích → Báo cáo** → `Tuần đang chọn` / `Tháng này` | Điền nhanh khoảng ngày. Có thể tự nhập `Từ ngày`, `Đến ngày`, mã nhân viên và phòng ban. |
| Chip `Chấm công`, `Ngày công`, `Trễ/sớm`, `Nghỉ phép`, `Tăng ca`, `Thiết bị` | Chọn loại báo cáo. |
| `Xuất CSV và chia sẻ` | Tạo tệp CSV từ dữ liệu đã tải và mở bảng chia sẻ của Android. |
| `Thử lại` | Tải lại khi truy vấn lịch sử lỗi. Nếu app báo vượt giới hạn tải, thu hẹp khoảng ngày trước khi xuất. |
| **Tiện ích → Nhật ký** → ô `Lọc hành động/đối tượng` | Tìm theo thao tác, đối tượng, người thực hiện hoặc nội dung. Nhật ký chỉ đọc. |
| **Tiện ích → Thông báo** → chọn `Tất cả nhân viên` hoặc phòng ban → `Gửi thông báo` | Gửi thông báo có tiêu đề và nội dung; xem lại tại “Lịch sử đã gửi”. |

### 10. Tiện ích nhân viên

Vào **Tiện ích → Tiện ích nhân viên** để quản lý nội dung hiển thị ở **Lịch họp, Khen thưởng, Tài liệu** của nhân viên.

| Nút/thao tác | Kết quả |
| --- | --- |
| `Loại nội dung` | Chọn **Lịch họp**, **Khen thưởng** hoặc **Tài liệu**. |
| `Người nhận` | Với lịch họp và tài liệu, chọn **Tất cả nhân viên**, **Theo phòng ban** hoặc **Một nhân viên**; danh sách chọn dùng phòng ban/nhân viên đang hoạt động. |
| `Đăng nội dung` | Kiểm tra biểu mẫu rồi lưu nội dung mới lên Firestore. Tiêu đề tối đa 120 ký tự, nội dung tối đa 4.000 ký tự. |
| `Chỉnh sửa` → `Lưu thay đổi` | Sửa nội dung đã đăng; giữ người tạo và thời điểm tạo ban đầu. |
| `Hủy chỉnh sửa / Tạo mới` | Rời biểu mẫu sửa để tạo nội dung khác. |
| `Xóa` → xác nhận `Xóa` | Xóa nội dung đã chọn khỏi danh sách tiện ích. |
| `Thử lại` | Tải lại danh sách khi đọc dữ liệu bị lỗi. |

**Lịch họp** cần ngày `yyyy-MM-dd`, giờ bắt đầu/kết thúc `HH:mm` trong cùng ngày, giờ kết thúc sau giờ bắt đầu và địa điểm; liên kết họp HTTPS là tùy chọn. **Khen thưởng** cần chọn một nhân viên và ngày khen thưởng; đây là ghi nhận thành tích, khoản thưởng bằng tiền được nhập tại **Lương**. **Tài liệu** cần liên kết HTTPS tới tệp đã được lưu trên hệ thống của đơn vị; app mở liên kết bằng ứng dụng bên ngoài.

## Chức năng nhân viên

### 1. Trang chủ

Trang chủ cho biết ca hôm nay, giờ chấm vào/ra, số giờ làm, tăng ca, số lần đi trễ, ngày công và thông báo. Phần **Truy cập nhanh** chỉ giữ bốn lối tắt **Đăng ký ca, Đơn từ, Bảng lương, Hỗ trợ** để giảm số hàng trên màn hình.

Thẻ **Việc của tôi** nằm ngay dưới khối **Trạng thái chấm công**, trước **Truy cập nhanh**; bấm thẻ để mở danh sách việc được giao.

| Nút/thao tác | Kết quả |
| --- | --- |
| Dòng `Ca làm việc` | Mở **Lịch làm việc**. |
| `Xem thêm` ở “Ca làm hôm nay”, dòng `Trạng thái chấm công` | Mở **Chấm công của tôi**. |
| `Xem tất cả` ở “Truy cập nhanh” | Mở **Tiện ích** với toàn bộ chức năng nhân viên. |
| Biểu tượng chuông | Mở danh sách thông báo; badge đếm thông báo chưa đọc. `Xem nội dung` đánh dấu đúng thông báo đó đã đọc trên Firebase. |
| `Đơn từ` | Mở danh sách đơn để gửi và theo dõi; badge đếm đơn và đăng ký tăng ca đang chờ duyệt. |
| `Đăng ký ca` | Mở trực tiếp phần đăng ký ca tuần sau để chọn ca và gửi Admin duyệt. Cũng có thể vào **Lịch làm việc → Đăng ký tuần sau**. |
| `Bảng lương` | Xem bảng lương, giờ làm và tăng ca của bạn. |
| `Hỗ trợ` | Gửi yêu cầu hỗ trợ vân tay tới Admin và theo dõi trạng thái, phản hồi. |

Mục **Tiện ích** trên thanh dưới có ba nhóm:

| Nhóm | Chức năng |
| --- | --- |
| **Ca làm & chấm công** | Đơn từ, Đăng ký ca, Lịch làm việc, Chấm công của tôi. |
| **Thu nhập & hồ sơ** | Bảng lương, Cá nhân, Thâm niên, Khen thưởng. |
| **Thông tin & hỗ trợ** | Thông báo, Tin tức, Lịch họp, Tài liệu, Hỗ trợ. |

Danh sách hiển thị số đơn chờ duyệt, thông báo chưa đọc và yêu cầu hỗ trợ chờ phản hồi. Khi mở chức năng phụ, thanh dưới giữ **Tiện ích** được chọn; nút quay lại trở về màn hình trước đó.

Dữ liệu lịch họp, khen thưởng và tài liệu được lưu tại `employeeResources`, theo dõi realtime theo quyền người nhận. Listener tải tối đa **200 mục cập nhật mới nhất, tính chung cả ba loại nội dung**, rồi từng màn hình lọc theo loại. Khi Admin chưa tạo nội dung, màn hình hiển thị danh sách trống; khi tải lỗi, có nút **Thử lại**.

### 2. Lịch làm việc và đăng ký tuần

Vào **Lịch làm việc** để chọn tab **Lịch của tôi** hoặc **Đăng ký tuần sau**. Tab **Lịch của tôi** mặc định xem tháng, hiển thị lịch đã phân và trạng thái tăng ca. Tiện ích **Trang chủ → Đăng ký ca** mở trực tiếp tab đăng ký.

| Nút/thao tác | Kết quả |
| --- | --- |
| `Tháng` / `Tuần`, mũi tên `Kỳ trước` / `Kỳ sau`, `Hôm nay` | Đổi kỳ xem lịch đã phân hoặc trở về ngày hiện tại. |
| Chạm một ngày trên lịch | Xem ca của ngày đó; ở tab đăng ký, chọn ca cho ngày thứ Hai–thứ Bảy trong tuần sau. |
| `Sáng cả tuần`, `Chiều cả tuần`, `Cả ngày cả tuần` | Chọn nhanh ca cho sáu ngày thứ Hai–thứ Bảy của tuần đăng ký. |
| `Sao chép tuần này` | Lấy lựa chọn ca từ lịch tuần hiện tại làm bản nháp tuần sau. |
| `Xóa chọn` | Bỏ toàn bộ lựa chọn trong bản nháp. |
| Chip `Ca sáng` / `Ca chiều` của ngày đang chọn | Chọn hoặc bỏ ca của ngày đó. Chọn cả hai để làm cả ngày; bỏ cả hai để không đăng ký ca cho ngày đó. |
| `Ghi chú (không bắt buộc)` | Nhập tối đa 500 ký tự cho đơn đăng ký tuần. |
| `Gửi đăng ký` | Cuộn xuống cuối biểu mẫu để gửi lịch tuần sau cho Admin duyệt. |
| `Cập nhật đăng ký` | Sửa đơn đang chờ hoặc gửi lại sau khi Admin yêu cầu chỉnh sửa. |

Trạng thái hiển thị là **Chưa đăng ký**, **Chờ Admin duyệt**, **Cần chỉnh sửa** hoặc **Đã duyệt**, kèm phản hồi của Admin nếu có. Hạn gửi là **trước 12:00 thứ Bảy trước tuần đăng ký**, theo giờ Việt Nam. Đơn đã duyệt hiển thị để xem; các nút sửa/gửi được ẩn. Quá hạn, đang lưu hoặc ca đã chọn không còn hoạt động sẽ khóa thao tác tương ứng.

Khi gửi, app kiểm tra tài khoản, hồ sơ và ca từ server, rồi ghi đơn `weeklyScheduleRequests/{employeeId}_{weekStart}` cùng nhật ký `audit_logs` trong một transaction. Đơn được gửi ở trạng thái `PENDING`; đơn `PENDING`/`NEEDS_REVISION` có thể cập nhật trước hạn. Chỉ **đơn được duyệt** mới chuyển thành lịch làm việc. App tự chuyển listener khi tuần mục tiêu đổi.

### 3. Chấm công của tôi và Bảng lương

| Nút/thao tác | Kết quả |
| --- | --- |
| Mũi tên `Tháng trước` / `Tháng sau` ở **Chấm công của tôi** | Đổi tháng xem giờ vào/ra, ca, giờ làm, tăng ca, đi trễ/về sớm và trạng thái từng ngày. |
| `Gửi yêu cầu điều chỉnh` | Mở **Đơn từ** để tạo đơn loại **Sửa chấm công**. |
| `Xem phiếu lương` | Mở **Bảng lương**. |
| Chạm một phiếu lương → `Đóng` | Xem chi tiết phiếu đã được Admin lưu, rồi đóng hộp thoại. |

### 4. Đơn từ và đăng ký tăng ca

Vào **Tiện ích → Đơn từ** hoặc lối tắt **Đơn từ** trên Trang chủ. Các loại đơn hiện có: **Nghỉ phép, Đi muộn, Về sớm, Ngoài văn phòng, Sửa chấm công, Đổi ca**.

| Nút/thao tác | Kết quả |
| --- | --- |
| `Tạo đơn` / `Đóng form` | Mở/thu biểu mẫu. |
| Chọn loại, ngày, lý do → `Gửi đơn` | Gửi đơn ở trạng thái chờ Admin xử lý. **Sửa chấm công** cần thời gian đề xuất; **Đổi ca** cần ca muốn đổi. |
| `Chọn cả ngày` / `Bỏ chọn` trong đơn nghỉ phép | Chọn/bỏ các ca nghỉ theo ngày đã có lịch. |
| `Hủy đơn đang chờ duyệt` | Hủy đơn **nghỉ phép** còn chờ; đơn đã được xử lý không có nút này. |
| `Gửi đăng ký tăng ca` | Gửi đơn cho ngày hôm nay hoặc ngày tương lai, khung cố định **18:00–22:00**, kèm lý do. Đơn hôm nay phải gửi trước 18:00. |

Khu tăng ca hiển thị đơn chờ, đã duyệt và bị từ chối cùng phản hồi của Admin. Được duyệt chưa đồng nghĩa có giờ công: vẫn cần quét vào và ra hợp lệ.

### 5. Cá nhân

**Hoạt động gần đây** mặc định hiển thị 3 lượt chấm mới nhất. `Xem thêm` mở thêm 3 lượt mỗi lần; `Thu gọn` trở về 3 lượt. Khi đổi tài khoản hoặc hồ sơ nhân viên, danh sách trở về mức hiển thị ban đầu.

| Nút/thao tác | Kết quả |
| --- | --- |
| Sửa điện thoại/địa chỉ → `Lưu thông tin liên hệ` | Cập nhật phần liên hệ trong hồ sơ cá nhân. |
| Nhập `Nội dung cần hỗ trợ` → `Gửi yêu cầu hỗ trợ` | Gửi yêu cầu liên quan đến vân tay cho Admin. |
| `Tải thêm lịch sử` | Xuất hiện khi đã xem hết các lượt đang có; tải thêm lượt chấm công cũ trong hồ sơ. |
| `Đổi mật khẩu` | Mở hộp thoại đặt mật khẩu mới. |

Thông tin chức vụ, mã nhân viên, phòng ban và trạng thái vân tay là phần hiển thị; nhân viên không tự sửa các dữ liệu quản trị này.

**Ngày vào làm và thâm niên:** Admin vào **Nhân viên → Sửa hồ sơ → Ngày vào làm → Lưu hồ sơ**. Nhân viên mở **Thâm niên** để xem ngày vào làm và thời gian làm việc tính theo năm, tháng, ngày; hồ sơ đã nghỉ có ngày nghỉ hợp lệ thì dùng ngày nghỉ làm mốc kết thúc. Ngày trống hoặc sai định dạng sẽ có hướng dẫn nhờ Admin cập nhật.

### 6. Dữ liệu và quyền của tiện ích

| Tiện ích | Dữ liệu và cách xử lý |
| --- | --- |
| **Lịch họp** | Đọc `employeeResources` loại `MEETING` dành cho tất cả nhân viên, phòng ban của bạn hoặc riêng bạn; xem thời gian, địa điểm, nội dung và mở liên kết họp nếu có. |
| **Khen thưởng** | Đọc `employeeResources` loại `REWARD` dành cho riêng bạn; xem ngày trao và nội dung ghi nhận. |
| **Tài liệu** | Đọc `employeeResources` loại `DOCUMENT` trong phạm vi được chia sẻ; nút mở tài liệu dùng URL HTTPS. Tệp được lưu ngoài app. |
| **Tin tức / Thông báo** | Đọc thông báo của chính nhân viên tại `notifications`; **Tin tức** lọc loại `ANNOUNCEMENT`. `Xem nội dung` đánh dấu đúng thông báo vừa mở đã đọc; `Thu gọn` đóng phần nội dung. |
| **Thâm niên** | Đọc `hireDate`/`terminationDate` từ hồ sơ `employees`; Admin cập nhật hồ sơ, app tính khoảng thời gian làm việc. |
| **Hỗ trợ** | Nhập nội dung hỗ trợ vân tay từ 1 đến 4.000 ký tự, chọn `Gửi yêu cầu hỗ trợ`; lưu đơn `FINGERPRINT_SUPPORT` tại `leaveRequests`. Mục **Yêu cầu đã gửi** hiển thị trạng thái và phản hồi của Admin tại **Đơn từ**. |

Admin quản lý nội dung; nhân viên đọc theo người nhận khi tài khoản và hồ sơ nhân viên đang hoạt động. Phạm vi `employeeResources.audience` là `ALL`, `DEPARTMENT:<departmentId>` hoặc `EMPLOYEE:<employeeId>`. Khi hồ sơ đổi nhân viên/phòng ban hoặc đăng xuất, app cập nhật hoặc hủy listener và xóa danh sách cũ khỏi trạng thái giao diện. Truy vấn người nhận dùng composite index `audience` tăng dần và `updatedAt` giảm dần.

## Cách hệ thống tính công trên Spark

1. **Thiết bị nhận diện:** AS608 so mẫu đã đăng ký tại chỗ; cảm biến không tự tải mẫu từ Firebase. Khi Wi-Fi hoặc kết nối Firebase không khả dụng, firmware có thể **lưu lượt quét và mở cửa theo mẫu đang có trong AS608**, nếu đã có giờ NTP hợp lệ và lưu LittleFS thành công. Bản hiện tại `spark-anonymous-v16-tls-memory` giữ hành vi v12: chỉ mở cửa khi đúng mẫu lúc bật nguồn chưa có Wi-Fi; LCD báo `CHUA LUU CONG` và không tạo lượt chấm vì chưa có giờ. ESP8266 không lưu danh sách hay cache nhân viên; liên kết danh tính của lượt offline được tra khi kết nối trở lại. Lượt được lưu sẽ được gửi lại từng bản.
2. **Lưu lượt gốc:** ESP8266 dùng Firebase Anonymous Auth, ghi `attendance/{eventId}` dạng `SCAN/PENDING`. Thiết bị không tự quyết định đây là vào hay ra theo mốc 12 giờ.
3. **Android phân giải:** app ghép lượt quét với `workSchedules`, `shifts`, các điều chỉnh và quyết định duyệt. Nó chống quét trùng, xác định lượt vào/ra, phát hiện thiếu lượt ra và tính công theo ca.
4. **Ngoài lịch:** Admin tạo quyết định duyệt/từ chối. Trên Spark, quyết định có thể được lưu ban đầu ở trạng thái `PENDING`; Android đọc nó và áp dụng vào bản công hiệu lực. Duyệt sẽ bổ sung ca được chọn cho ngày đó để ghép các lượt quét; từ chối giữ ngoại lệ.
5. **Tăng ca:** đơn 18:00–22:00 được duyệt trở thành phiên tăng ca riêng trong Android. Chỉ cặp vào/ra hợp lệ mới tạo giờ tăng ca và đi vào bảng công, KPI, lương.
6. **Lịch sử:** báo cáo và bảng công dùng **tháng được chọn** để quyết định có hiển thị nhân viên đã nghỉ. Audit lưu người thao tác và lý do; các thao tác phù hợp ghi cùng giao dịch với thay đổi nghiệp vụ.

**Nghỉ phép và giờ công:** ca chính được duyệt nghỉ có 0 giờ làm thực tế, không tính đi trễ hoặc về sớm. Nghỉ sáng rồi làm chiều chỉ tính giờ và trễ/sớm của chiều. Ca tăng ca riêng vẫn tính khi được duyệt và có cặp vào/ra hợp lệ, kể cả ngày không có ca chính hoặc ngày nghỉ ca chính. Bảng công, báo cáo, lương và KPI dùng chung quy tắc; báo cáo và CSV lọc trễ/sớm bằng số phút nên giữ cả người vừa trễ vừa về sớm.

**Tải dữ liệu:** Dashboard giữ các cửa sổ lịch nhỏ theo tuần/tháng thay vì lắng nghe lịch mọi năm. Phiếu lương được đọc theo tháng. Khi xem kỳ cũ, app tải lượt quét theo trang và tải lịch, ca, nghỉ phép, tăng ca, điều chỉnh, phân loại và duyệt ngoài lịch của kỳ đó từ server. Có thêm ngày biên để ghép ca qua đêm; điều chỉnh được tìm theo ngày công, không theo ngày tạo. CSV và lưu lương chỉ được bật khi tải đủ đúng kỳ và phạm vi nhân viên; lỗi mạng hoặc vượt giới hạn 5.000 lượt quét sẽ yêu cầu tải lại hoặc thu hẹp kỳ.

**Phản hồi thiết bị:** “Đã lưu, chờ đồng bộ” khác với “đã xác nhận”, “bị từ chối” và “không lưu được”. Khi online, xác nhận của lượt đang xử lý trong thời hạn cho phép mở cửa. Khi kết nối không khả dụng và có giờ hợp lệ, chính sách AS608 cho phép lượt đang xử lý đã lưu bền vững mở cửa một lần; LCD báo `OFFLINE: DA LUU`, chưa phải xác nhận công từ server. Trường hợp chưa có giờ chỉ mở cửa và báo `CHUA LUU CONG`. Hàng đợi gửi từng bản và retry khi lỗi tạm thời. Lượt mới thay thế quyền mở cửa trong RAM của lượt cũ, kể cả khi lượt mới bị từ chối hoặc không lưu được; bản cũ vẫn nằm trong hàng đợi. Xác nhận muộn, sau khởi động lại hoặc của lượt đã mở offline chỉ đồng bộ dữ liệu, không mở cửa thêm. `SYNC_ATTENDANCE` giữ `PROCESSING` cho đến khi hàng đợi rỗng; timeout không xóa các bản đã lưu. Wi-Fi vừa kết nối lại cho hàng đợi một lần retry ngay khi cửa đã đóng; hàng đợi được ưu tiên trước đọc lệnh mới. Lượt quét đang xử lý được ưu tiên trước heartbeat; khi đồng bộ nền, heartbeat đến hạn và lần gửi FIFO luân phiên quyền thử để lỗi ở đầu hàng đợi không chặn heartbeat kéo dài. Hoãn HTTPS không đẩy lùi hạn retry thêm 30 giây. Lỗi gửi kết quả lệnh khi hàng đợi đã rỗng không làm LCD báo nhầm còn công chờ gửi.

**Heartbeat:** firmware v15 gửi cập nhật khoảng mỗi 30 giây khi điều kiện mạng và phần cứng cho phép, kể cả khi hàng đợi chưa rỗng hoặc chưa có giờ NTP. Firestore ghi `lastHeartbeat` bằng thời gian server (`REQUEST_TIME`) để đồng hồ thiết bị không làm sai trạng thái kết nối. Việc ghi lượt chấm mới vẫn cần giờ NTP hợp lệ như quy tắc bên dưới.

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
4. Khi thiết lập dự án mới hoặc thay đổi Rules/indexes, triển khai cấu hình trong thư mục `firebase/` (Firebase CLI đã đăng nhập đúng dự án):

   ```powershell
   firebase deploy --only firestore:rules,firestore:indexes --project chamcongiot-56ae5
   ```

   Nếu dùng dự án Firebase khác, sửa `.firebaserc`, `app/google-services.json` và `FIRESTORE_BASE_URL` trong cấu hình firmware cho cùng một project. Không chạy lệnh deploy Functions khi giữ gói Spark.

Rules của bản này bổ sung quyền đọc tiện ích theo người nhận, cho phép transaction đọc đơn tuần chưa tồn tại của chính nhân viên và sửa phép tính thời hạn đăng ký bằng timestamp. Hạn gửi giữ nguyên: trước 12:00 thứ Bảy trước tuần đăng ký, theo giờ Việt Nam. Index tiện ích phục vụ truy vấn `employeeResources` theo `audience` và `updatedAt`.

**Đã xác minh trên dự án hiện tại:** Rules được cập nhật ngày 08/10/2026 lúc **09:42:14 giờ Việt Nam** và khớp source; 22 indexes khớp cấu hình local, không thiếu/thừa index, composite index tiện ích ở trạng thái **READY**. Không cần chạy lại lệnh deploy cho bản sửa UI duyệt tuần sau; hãy build/cài APK mới. Với dự án mới hoặc lần thay đổi cấu hình sau này, chờ index sẵn sàng sau deploy rồi thử lại truy vấn.

| Loại thay đổi | Bước áp dụng |
| --- | --- |
| Giao diện hoặc logic Kotlin | Build APK và cài lên máy Admin/nhân viên cần cập nhật. |
| Firestore Rules hoặc indexes | Deploy `firestore:rules,firestore:indexes` lên đúng dự án Firebase. |
| Firmware ESP8266 | Biên dịch và nạp firmware lên thiết bị. |

Lần cập nhật tài liệu này chỉ kiểm tra trạng thái Firebase, không thực hiện deploy hoặc nạp firmware. Lệnh trên là hướng dẫn thiết lập và áp dụng những thay đổi cấu hình sau này.

### Android

Dự án dùng JDK 17, Gradle Wrapper 8.9 và Android SDK 35; app hỗ trợ từ Android 8.0 (API 26). Mở thư mục gốc bằng Android Studio, đồng bộ Gradle, sau đó chạy trên thiết bị Android hoặc tạo APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK debug nằm tại `app/build/outputs/apk/debug/app-debug.apk`. Cài APK mới trên **máy Admin** để có khu duyệt đăng ký tuần trong **Đơn từ** và trên **máy nhân viên** để có mục **Tiện ích** cùng Trang chủ gọn hơn. Khi thay đổi quy tắc phân giải công, cần cập nhật cả Admin và nhân viên để dùng cùng quy tắc Spark. Chỉ build/cài app không tự cập nhật Firestore Rules hay firmware trên ESP8266.

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

1. Nạp cả sketch v16, kiểm tra Serial 9600 baud hiện `FW: spark-anonymous-v16-tls-memory`. Giữ LittleFS khi nạp nếu còn lượt chờ gửi. Bản này giảm RAM thường trực bằng cách đặt chuỗi LCD/log trong Flash và kiểm tra lại heap/block ngay trước TLS; nếu thiếu RAM, thiết bị hoãn HTTPS và giữ dữ liệu để thử lại. Với lệnh đồng bộ, kiểm tra `Cap nhat lenh: HTTP 200` và trạng thái `COMPLETED` trên app; còi/đèn xanh chỉ báo thành công sau khi ghi được kết quả lệnh. Cờ `applied=true` của lệnh thông thường không thay thế trạng thái hoàn tất.
2. Cho thiết bị kết nối Wi-Fi **có Internet** để lấy giờ NTP, rồi đối chiếu ngày/giờ trên LCD với giờ Việt Nam. Chỉ thấy Wi-Fi hoặc Firebase đăng nhập thành công chưa đủ để kết luận đã có giờ hợp lệ.
3. Giữ nguồn ESP, ngắt Wi-Fi và quét mẫu đã đăng ký: lượt được lưu trước khi mở cửa; LCD báo `OFFLINE: DA LUU`.
4. Cho có mạng trở lại: hàng đợi gửi từng bản, giữ thời điểm quét ban đầu và không mở cửa thêm khi xác nhận muộn.

Nếu tắt nguồn/reset rồi bật lại khi chưa có Wi-Fi, ESP chưa biết giờ: đúng mẫu chỉ mở cửa, giữ 5 giây và báo `CHUA LUU CONG`. Lượt này không có bản để gửi lại; cần NTP thành công trước các lượt chấm tiếp theo. Các lượt đã lưu trước đó trong LittleFS vẫn được giữ và đồng bộ khi có mạng.

## Cấu trúc mã nguồn

| Đường dẫn | Vai trò |
| --- | --- |
| [`app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt`](app/src/main/java/vn/chamcong/iot/ui/ChamCongApp.kt) | Đăng nhập, điều hướng và các hộp thoại chung. |
| [`app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt`](app/src/main/java/vn/chamcong/iot/ui/MainViewModel.kt), [`MainViewModelSubscriptions.kt`](app/src/main/java/vn/chamcong/iot/ui/MainViewModelSubscriptions.kt) | Ý định nghiệp vụ và các listener Firestore theo vai trò/tuần. |
| [`MainViewModelWeeklyScheduleRequests.kt`](app/src/main/java/vn/chamcong/iot/ui/MainViewModelWeeklyScheduleRequests.kt) | Listener đăng ký tuần của Admin, trạng thái tải/lỗi và thử lại theo đúng tuần đang chọn. |
| [`AdminWeeklyScheduleRequests.kt`](app/src/main/java/vn/chamcong/iot/ui/requests/AdminWeeklyScheduleRequests.kt) | Danh sách đăng ký tuần gọn, tìm/lọc, tổng hợp thu gọn, chi tiết và xác nhận duyệt toàn tuần của Admin tại Đơn từ. |
| [`AdminOtherRequestsList.kt`](app/src/main/java/vn/chamcong/iot/ui/requests/AdminOtherRequestsList.kt), [`AdminRequestComponents.kt`](app/src/main/java/vn/chamcong/iot/ui/requests/AdminRequestComponents.kt) | Danh sách/chi tiết Đơn khác và thành phần tìm kiếm, lọc, dòng đơn gọn dùng chung ở các nhóm Admin. |
| [`app/src/main/java/vn/chamcong/iot/ui`](app/src/main/java/vn/chamcong/iot/ui) | Màn hình Admin và nhân viên theo thư mục chức năng. |
| [`app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt`](app/src/main/java/vn/chamcong/iot/domain/AttendanceResolutionRules.kt) | Quy tắc phân giải lượt chấm và áp dụng duyệt ngoài lịch trên Spark. |
| [`app/src/main/java/vn/chamcong/iot/data`](app/src/main/java/vn/chamcong/iot/data) | Repository đọc/ghi Firebase. |
| [`app/src/main/java/vn/chamcong/iot/data/FirebaseRepositoryWeeklyRequests.kt`](app/src/main/java/vn/chamcong/iot/data/FirebaseRepositoryWeeklyRequests.kt) | Transaction gửi/cập nhật/duyệt đăng ký ca tuần và ghi nhật ký. |
| [`app/src/main/java/vn/chamcong/iot/model/EmployeeResourceModels.kt`](app/src/main/java/vn/chamcong/iot/model/EmployeeResourceModels.kt), [`EmployeeResourceRules.kt`](app/src/main/java/vn/chamcong/iot/domain/EmployeeResourceRules.kt) | Mô hình lịch họp/khen thưởng/tài liệu và kiểm tra dữ liệu nhập. |
| [`app/src/main/java/vn/chamcong/iot/data/FirebaseRepositoryEmployeeResources.kt`](app/src/main/java/vn/chamcong/iot/data/FirebaseRepositoryEmployeeResources.kt) | Đọc nội dung theo phạm vi người nhận, lưu và xóa nội dung trên Firestore. |
| [`app/src/main/java/vn/chamcong/iot/ui/admin/EmployeeResourceManagementScreen.kt`](app/src/main/java/vn/chamcong/iot/ui/admin/EmployeeResourceManagementScreen.kt), [`EmployeeResourcesScreen.kt`](app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeResourcesScreen.kt), [`EmployeeUtilityScreens.kt`](app/src/main/java/vn/chamcong/iot/ui/employee/EmployeeUtilityScreens.kt) | Màn hình quản lý nội dung của Admin và các tiện ích nhân viên. |
| [`firebase/firestore.rules`](firebase/firestore.rules), [`firebase/firestore.indexes.json`](firebase/firestore.indexes.json) | Phân quyền và indexes Firestore. |
| [`firmware/esp8266_fingerprint`](firmware/esp8266_fingerprint) | Sketch, xử lý vân tay, lệnh thiết bị và hàng đợi chấm công. |

## Lưu ý khi vận hành

- **Không thấy công sau khi quét:** lượt báo `DA LUU` / `CHO DONG BO` cần có mạng trở lại để tra danh tính và đồng bộ, chưa hiện công ngay. Nếu LCD báo `CHUA LUU CONG`, ESP chưa có giờ hợp lệ nên lượt đó chỉ mở cửa, không được lưu hoặc gửi lại; cần đồng bộ giờ trước các lượt chấm tiếp theo. Kiểm tra đúng ngón tay đã đăng ký, hàng đợi chưa đầy, lịch/ca đã được duyệt và đã có đủ lượt vào/ra. Mở **Thiết bị** và **Chấm công** để xem trạng thái cụ thể.
- **Duyệt ngoài lịch hoặc tăng ca nhưng giờ vẫn bằng 0:** kiểm tra ca/ngày đã chọn và cặp quét vào/ra hợp lệ. Đơn chờ duyệt chưa tính thành giờ tăng ca.
- **Nhân viên đã gửi nhưng Admin không thấy đăng ký tuần:** vào **Đơn từ → Đăng ký tuần**, hoặc **Phân ca → Duyệt đăng ký tuần sau**. Ngày 08/10, màn hình chọn **Tuần 12/10 – 17/10/2026**; đối chiếu tuần nhân viên đã gửi, xóa ô tìm kiếm và chọn **Tất cả** để kiểm tra các dòng bị lọc. Chạm dòng để xem chi tiết, chờ tải xong hoặc **Thử lại** khi có lỗi. Cài APK mới trên Admin để có danh sách gọn; APK cũ có thể dùng giao diện duyệt trước đó, và bản trước khi chuyển màn hình vẫn xem yêu cầu tại **Lịch**. Yêu cầu `PENDING` và audit đã lưu ở server không cần gửi lại khi Admin đổi giao diện.
- **Gửi đăng ký ca báo chưa có quyền:** kiểm tra `users/{uid}` có `role: "EMPLOYEE"`, `active: true`, `employeeId` khớp hồ sơ nhân viên đang hoạt động và đã triển khai Rules cùng phiên bản app. Bản sửa xử lý quyền đọc đơn tuần chưa tồn tại của chính nhân viên và lỗi tính hạn gửi bằng chuỗi ngày. Khi thao tác gửi thất bại, thông báo có tiền tố **“Không gửi được đăng ký ca”**; nhập sai ghi chú/ca hoặc quá hạn có thông báo riêng theo nguyên nhân.
- **Khen thưởng, lịch họp hoặc tài liệu báo lỗi quyền:** kiểm tra tài khoản/hồ sơ đang hoạt động, người nhận đã chọn và `departmentId` trong hồ sơ. Rules và index tiện ích trên dự án hiện tại đã được xác minh áp dụng; không đổi vai trò nhân viên để khắc phục lỗi đọc. Admin kiểm tra nội dung chia sẻ tại **Tiện ích nhân viên**; nếu dùng project khác, đối chiếu cấu hình Firebase và Rules/indexes của project đó.
- **Tổng quan báo không tải được dữ liệu mới từ Firebase:** kiểm tra Internet rồi chọn **Tải lại dữ liệu**. Truy vấn tính công giữ nguồn `SERVER` để kiểm tra dữ liệu đầy đủ; khi tải chưa hoàn tất, số 0 trên màn hình chưa xác nhận rằng không có lượt chấm. App khóa lưu lương/xuất dữ liệu cần đủ kỳ cho đến khi tải thành công.
- **Không thấy nhân viên đã nghỉ:** bật công tắc ở **Nhân viên**; với báo cáo và bảng công, chọn đúng tháng lịch sử. Không tạo ca mới hoặc phiếu lương cho các tháng sau tháng nghỉ.
- **Báo cáo CSV thiếu dòng:** nếu màn hình cảnh báo đã chạm giới hạn tải lịch sử, thu hẹp khoảng ngày rồi xuất lại.

## Kiểm thử hồi quy

Bản Công việc ngày **09/10/2026**: Android **300/300** trong **48 bộ**, Firestore Emulator **56/56**, Functions **36/36**, firmware mô phỏng **79/79**. APK debug và firmware NodeMCU v2 đều biên dịch thành công. Chưa triển khai Rules/index mới lên online, cài APK hoặc nạp firmware trên thiết bị thật. Các kết quả dưới đây thuộc đợt trước; [tài liệu công việc](docs/QUAN_LY_CONG_VIEC.md) ghi phạm vi mới.

Kết quả đã ghi nhận ngày **08/10/2026**; Android được chạy lại sau khi làm gọn ba nhóm **Đơn từ** của Admin và thêm **Tiện ích** cho nhân viên:

| Nhóm kiểm chứng | Kết quả | Phạm vi |
| --- | --- | --- |
| Android | **284 kiểm thử / 45 bộ kiểm thử đạt**, không lỗi/thất bại/bỏ qua; build APK debug thành công sau cập nhật giao diện Admin và nhân viên | Chạy lại toàn bộ bộ kiểm thử hiện có, cập nhật kiểm tra thứ tự thanh điều hướng nhân viên; không bổ sung test mới cho lần đổi cách trình bày này. |
| Firestore Emulator | **49/49 kiểm thử đạt** trong đợt rà soát trước | Quyền đọc/ghi, transaction, phạm vi nhân viên; gồm 7 kiểm thử đăng ký tuần, 5 kiểm thử tiện ích và 3 kiểm thử thao tác Admin đồng thời. Rules không đổi; kiểm thử trên demo cục bộ. |
| Backend Functions tùy chọn | **32/32 kiểm thử đạt** trong đợt rà soát trước | Các bài kiểm thử dưới `firebase/functions`; phần cốt lõi Spark dùng Rules và Android. |
| Mô phỏng firmware | **69/69 kiểm thử đạt** | Trạng thái lệnh thiết bị, hàng đợi offline và runtime; không thay thế kiểm chứng cảm biến/cửa trên phần cứng thật. |

Firestore **49/49**, Functions **32/32** và firmware **69/69** là kết quả đợt rà soát trước; không chạy lại ba nhóm này trong lần cập nhật giao diện Admin và nhân viên, vì thay đổi chỉ ở UI.

APK debug được build lại từ mã nguồn hiện tại tại `app/build/outputs/apk/debug/app-debug.apk`. Đây là kết quả kiểm thử/build trên máy phát triển; lần thêm Tiện ích nhân viên chưa kiểm tra trực tiếp giao diện vì máy không có thiết bị hoặc emulator Android sẵn có. Việc chụp ảnh, đo phần cứng và thử end-to-end trên điện thoại/ESP8266 được theo dõi riêng trong báo cáo đồ án. [Checklist Admin/nhân viên](KIEM_TRA_CHUC_NANG_ADMIN_USER.txt) ghi từng nhóm đã rà soát, lỗi đã sửa, giới hạn và các kịch bản cần thử trực tiếp.

Chạy build và kiểm thử Android:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Các bài kiểm thử bao phủ gộp ca không trùng và bảo toàn điều chỉnh, ca chính 4 giờ + tăng ca 4 giờ, ngày chỉ tăng ca, nghỉ sáng/làm chiều, vừa trễ/về sớm, ca qua đêm ở cuối tháng, điều chỉnh tạo muộn và điều kiện tải đủ trước khi xuất/lưu. Kiểm thử transaction hai Admin trong `firebase/test/departmentScheduleTransactions.test.js` cần Firestore emulator với rules của dự án; chỉ dùng project demo, không chạy trên Firebase thật.

`firebase/test/weeklyScheduleSubmissionRules.test.js` kiểm tra transaction gửi đăng ký ca lần đầu kèm nhật ký, cập nhật đơn đang chờ, quyền đọc/truy vấn theo nhân viên và từ chối sửa đơn đã duyệt trên Firestore emulator.

`firebase/test/adminActionTransactions.test.js` kiểm tra sao chép lịch giữ dữ liệu Admin khác vừa phân, hoàn tác tài khoản giữ hồ sơ khi thiết bị đã nhận đăng ký, đổi tên phòng ban giữ trạng thái và hồ sơ vừa chuyển phòng, cùng điều kiện nhân viên còn hoạt động trước duyệt lịch. Các form được khóa khi gửi/lưu; lỗi hồ sơ đăng nhập có Thử lại/Đăng xuất. Nút điều chỉnh chấm công mở đúng loại đơn, đơn nghỉ tải ca theo khoảng ngày chọn và báo cáo nhận mã hiển thị như `NV0004`.

Chạy kiểm thử Firestore với Firebase CLI, Node.js và **Java 21** trên `PATH`. Từ thư mục gốc dự án, mở terminal thứ nhất:

```powershell
java -version
firebase emulators:start --only firestore --project demo-chamcong-rules --config firebase.json
```

Chờ Firestore Emulator sẵn sàng. `firebase.json` hiện dùng cổng CLI mặc định **8080**; mở terminal thứ hai để chạy lần lượt các tệp kiểm thử:

```powershell
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8080'
$rulesTests = (Get-ChildItem -LiteralPath 'firebase/test' -Filter '*.test.js').FullName
node --test --test-concurrency=1 @rulesTests
```

Các bài kiểm thử dùng dự án `demo-*` trên emulator. `employeeResourceRules.test.js` kiểm tra phạm vi toàn bộ/phòng ban/cá nhân, chặn nhân viên sửa nội dung, dữ liệu không hợp lệ và bảo toàn người/thời điểm tạo. Kiểm thử đăng ký tuần đã tái hiện hai lỗi trước sửa: GET đơn chưa tồn tại bị từ chối, sau đó commit bị từ chối do cộng chuỗi ngày với duration. Bản cuối cho phép gửi lần đầu kèm audit, cập nhật trước hạn và vẫn chặn đơn quá hạn/đã duyệt hoặc truy cập đơn người khác.

Kiểm thử backend tùy chọn:

```powershell
npm test --prefix firebase/functions
```

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
