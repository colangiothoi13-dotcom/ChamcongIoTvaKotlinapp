# Web admin Chấm công IoT

Giao diện quản trị dành cho máy tính, dùng chung Firebase Auth và Firestore với ứng dụng Android. Chữ, bảng và lịch tuần được bố trí cho màn hình lớn; vẫn dùng được ở màn hình nhỏ.

## Mở trên máy tính

Nhấp đúp **`mo-web-admin.cmd`**, giữ cửa sổ máy chủ mở, rồi mở **http://localhost:4173** trong Chrome hoặc Edge.

Hoặc mở terminal trong thư mục `web-admin`:

```powershell
npm start
```

Yêu cầu Node.js 20 trở lên; **không cần `npm install`**. Trình duyệt cần Internet để tải Firebase SDK và kết nối dữ liệu thật. Có thể bấm **Xem thử với dữ liệu mẫu** mà không đăng nhập. Thao tác trong chế độ mẫu chỉ thay đổi bộ nhớ của phiên xem thử.

## Thao tác quản lý trên web

Đăng nhập tài khoản admin của app, rồi dùng mục **Thao tác quản lý** trên Tổng quan để thêm nhân viên, giao việc, phân ca, điều chỉnh công, duyệt đơn hoặc quản lý lương. Nút **Lưu** trong biểu mẫu ghi thay đổi vào dữ liệu dùng chung với app.

Trong **Nhân viên**, từng dòng có **Sửa hồ sơ**, **Đơn giá giờ**, **Tài khoản / vân tay** và cột **Khuôn mặt**. Cột thao tác luôn hiện khi cuộn ngang bảng. Trong **Chấm công**, có thể mở điều chỉnh trực tiếp từ lượt chấm của nhân viên. Trong **Bảng lương**, chọn nhân viên để đặt đơn giá, lập phiếu mới hoặc tính lại phiếu đã có.

**Báo cáo & bảng công → Bảng công tháng → Chi tiết ngày / ca** cho xem giờ quét và giờ được tính công của từng ca. Khi lập/tính lại lương, có phần chi tiết ngày/ca để đối chiếu trước khi lưu. **Công việc → Chi tiết** có lượt chấm hợp lệ của ca liên quan và tải tệp kết quả từ từng phiên bản lịch sử. **Đơn từ → Đăng ký tuần** có tổng số nhân viên theo ngày/ca, tính các đăng ký đã gửi hoặc đã duyệt.

Danh sách nhân viên trong **Điều chỉnh công** theo tháng của **Ngày ca**: người đã nghỉ từ tháng trước không xuất hiện ở tháng hiện tại. Đổi sang ngày công cũ để điều chỉnh kỳ trước hoặc tháng nghỉ việc; những người đã nghỉ có nhãn **Đã nghỉ**. Công và phiếu lương đã lưu vẫn giữ nguyên để tra cứu.

Sau khi cập nhật mã nguồn, bấm **Ctrl + F5** trong trình duyệt để tải giao diện mới. Máy chủ local đang chạy sẽ phục vụ các tệp mới ngay; không cần khởi động lại.

## Xem camera trực tiếp trên laptop

App Admin cũng có **Tiện ích → Camera & khuôn mặt**. Để kết nối điện thoại, dừng web cũ bằng Ctrl+C rồi mở **`mo-web-admin-camera-app.cmd`** trên laptop. Nhập địa chỉ HTTPS và mã xác minh hiển thị trong cửa sổ đó vào app; laptop/điện thoại cần cùng mạng. Hướng dẫn và đối chiếu chức năng nằm tại [APP_WEB_PARITY.md](../docs/APP_WEB_PARITY.md).

Khi cập nhật phần máy chủ camera lần đầu, **dừng cửa sổ Node cũ bằng Ctrl+C rồi mở lại `mo-web-admin.cmd`** (hoặc `npm start`). Mở http://localhost:4173, đăng nhập Admin hoặc bấm **Xem thử với dữ liệu mẫu**, rồi chọn **Camera & khuôn mặt → Bắt đầu xem**.

Camera và laptop cần cùng mạng, camera vẫn được cấp nguồn. Máy chủ đọc `../services/face_attendance/config.json`: `camera.url` là `http://IP_CAMERA/capture`, `camera.token` khớp với firmware. Không nhập token vào trình duyệt hoặc đưa cấu hình dịch vụ vào `web-admin`.

Ảnh JPEG mới được cập nhật tối đa 2–3 ảnh/giây. Có **Dừng xem**, **Thử lại** khi lỗi và **Xoay 90°** khi camera đặt ngang. Chuyển trang, đăng xuất hoặc ẩn tab sẽ dừng lấy ảnh; quay lại cần bấm Bắt đầu xem. Camera sử dụng thiết bị thật kể cả trong phiên dữ liệu mẫu. Xem ảnh không đăng ký mẫu và không tạo lượt chấm công.

Nút xoay chỉ thay đổi góc xem trên web. Khi đăng ký khuôn mặt trên web hoặc app, đặt board để ảnh gốc đứng thẳng. Đăng ký cần dịch vụ laptop chạy theo [hướng dẫn nhận diện](../services/face_attendance/README.md).

Trang camera hoạt động qua máy chủ Node local. Gói static `dist` hoặc Firebase Hosting không kèm proxy camera; xem trên bản hosted sẽ báo cần mở web trên localhost. Phần quản trị Firebase hiện có vẫn hoạt động bình thường trên Hosting.

Khi một thao tác đang tải dữ liệu, chỉ nút tương ứng tạm chờ; các nút khác vẫn dùng được. Nếu tải quá 20 giây, web báo để bạn kiểm tra kết nối và thử lại. Lỗi trong cửa sổ nhập liệu hiển thị ngay trong cửa sổ đó.

## Thêm nhân viên và đăng ký chấm công

1. Vào **Nhân viên → Thêm nhân viên**. Cửa sổ có đủ **Hồ sơ, Tài khoản, Vân tay, Khuôn mặt**.
2. Nhập thông tin rồi bấm **Lưu hồ sơ & tiếp tục** để được cấp mã nhân viên. Cửa sổ vẫn mở; chọn **Tạo tài khoản**, **Đăng ký vân tay** hoặc **Đăng ký khuôn mặt** ngay trong đó. Có thể đăng ký lần lượt cả hai phương thức cho cùng hồ sơ.
3. Với nhân viên đã có, bấm tên để mở **Chi tiết nhân viên**, hoặc bấm **Sửa hồ sơ** để vào form gộp. Các cột vân tay/khuôn mặt hiển thị trạng thái; thao tác nằm trong hồ sơ.

Bấm lưu chỉ tạo hồ sơ. Web gửi yêu cầu lấy mẫu khi bạn chọn phương thức và xác nhận trong cửa sổ tiếp theo. Vân tay cần ESP8266/cảm biến kết nối đúng thiết bị; khuôn mặt cần dịch vụ laptop và Firebase như phần dưới. Nếu vân tay đang chờ kết quả, web hiển thị trạng thái chờ; nếu đã có mẫu, dùng thao tác xóa/làm lại khi cần.

Nếu đang sửa thông tin chưa lưu, các nút tài khoản/đăng ký mẫu tạm khóa và hiện hướng dẫn. Bấm **Lưu hồ sơ** hoặc hoàn nguyên thay đổi trước khi tiếp tục, để thao tác không làm mất nội dung đang nhập.

Mục **Tài khoản** đọc tài khoản liên kết theo đúng nhân viên khi mở hoặc lưu hồ sơ. Khi đã có tài khoản, kể cả tài khoản đã khóa, web hiển thị thông tin đăng nhập và ẩn **Tạo tài khoản**. Nếu chưa tải được tài khoản, web báo lỗi đọc và chưa hiển thị nút tạo; mở lại hồ sơ để thử lại. Email liên hệ trong hồ sơ không quyết định liên kết tài khoản đăng nhập.

## Lịch và phân ca theo app

**Lịch làm việc → Xem lịch** chọn **Tuần** hoặc **Tháng**. Bấm ô của nhân viên/ngày để sửa; web điền sẵn ca hiện tại. Trong **Phân ca**, chọn một người, **Nhiều nhân viên** hoặc phòng ban; đánh dấu từng ngày thứ Hai–thứ Bảy trong khoảng tối đa 31 ngày. **Cách áp dụng** cho phép thêm/thay ca cùng loại hoặc chỉ giữ ca đã chọn. **Điều chỉnh giờ công** trong ô lịch mở đúng nhân viên/ngày, tối đa 24 giờ và bắt buộc lý do.

Web chỉ hiển thị một ca sáng và một ca chiều mặc định như app. Các cấu hình cũ vẫn được giữ để lịch sử công/lương dùng đúng ID ca. Mốc `1970-01-01` của mẫu chuẩn hiển thị **Ca mặc định**. Khởi tạo hoặc phân ca chỉ tạo mẫu chuẩn còn thiếu, không ghi đè cấu hình đã lưu. Trạng thái chấm riêng từng ca chỉ hiện khi tải đủ dữ liệu cho tuần/tháng đang xem.

## Đăng ký khuôn mặt trên web

Web có các thao tác **Đăng ký khuôn mặt**, **Đăng ký lại**, **Xóa mẫu mặt**, **Xem yêu cầu** và **Hủy yêu cầu** như app Admin. Web và app dùng cùng hồ sơ mẫu trên Firebase, cùng dịch vụ laptop và khóa chống yêu cầu đồng thời. Không cần nạp lại firmware camera cho thay đổi giao diện này.

Yêu cầu đăng ký mới trên web dùng **6 ảnh chụp thủ công**: **2 nhìn thẳng → 2 quay nhẹ sang trái → 2 quay nhẹ sang phải**, trái/phải tính theo phía của nhân viên. Bấm **Mở camera để chụp 6 ảnh** rồi làm theo hướng dẫn đang sáng trên màn hình. Mỗi bước có bộ đếm 0/2–2/2; chỉ ảnh đạt yêu cầu mới tăng số mẫu và chuyển bước. Admin kiểm tra nhân viên quay đúng hướng trên hình trực tiếp; dịch vụ kiểm tra chất lượng ảnh, chưa đo góc quay tự động. Giữ cả hai mắt và toàn bộ mặt rõ trong ảnh, tránh quay nghiêng hoàn toàn. Một lần bấm yêu cầu một ảnh mới, nút tạm khóa đến khi laptop trả kết quả. Đủ 6 ảnh mới lưu mẫu; không bấm trong năm phút thì yêu cầu hết hạn. Các yêu cầu cũ và luồng tạo từ Android hiện có giữ tổng 10 ảnh. Mở yêu cầu đăng ký mới để dùng kế hoạch sáu ảnh; mẫu đã đăng ký vẫn sử dụng được.

Với camera đang lộn đầu, đặt `camera.rotation` trong cấu hình laptop thành `180` và mở lại dịch vụ. Các góc hợp lệ là `0`, `90`, `180`, `270`; cấu hình thiếu thì dùng `0`. Laptop xoay ảnh trước khi kiểm tra chất lượng, lưu mẫu và nhận diện; web xem cùng chiều. Nút **Xoay 90°** vẫn chỉ đổi góc xem thêm. Không cần nạp lại ESP32 cho cách chỉnh này.

Camera đăng ký có **khung oval hướng dẫn xa → gần** cho mỗi hướng: ảnh 1/2 dùng khung nhỏ để lấy đủ mặt; khi ảnh đầu đạt, ảnh 2/2 dùng khung lớn hơn và yêu cầu tiến lại gần. Ảnh lỗi giữ nguyên khung. Hướng tiếp theo bắt đầu lại với khung nhỏ. Canh toàn bộ trán và cằm trong oval, giữ yên rồi bấm **Chụp ảnh**. Khung chỉ hướng dẫn vị trí trên hình trực tiếp, không tự xác nhận khoảng cách và không được vẽ/cắt vào JPEG lưu mẫu. Cập nhật giao diện này chỉ cần **Ctrl+F5**, không cần nạp firmware hoặc mở lại trạm.

Trong cửa sổ **Yêu cầu khuôn mặt**, hình camera nằm ngay cạnh tiến độ 0/10–10/10 và tự mở khi đang chờ/thu mẫu. Có nút **Dừng xem**, **Bắt đầu xem/Thử lại** và **Xoay 90°**. Hình lấy từ đúng trạm đã chọn, cập nhật khoảng một lần mỗi giây; lỗi xem ảnh không hủy đăng ký. Khi hoàn tất, đóng cửa sổ, đăng xuất hoặc ẩn trang, hình dừng và được giải phóng. Nếu quay lại tab đã ẩn, bấm **Bắt đầu xem** để xem tiếp. Với yêu cầu cũ đã kết thúc, có thể mở **Xem yêu cầu → Bắt đầu xem** để kiểm tra góc camera. Xoay ở đây chỉ đổi góc nhìn, ảnh gốc dùng đăng ký cần đứng thẳng.

Chuẩn bị một lần theo [hướng dẫn dịch vụ laptop](../services/face_attendance/README.md): lấy khóa Firebase Admin vào `services/face_attendance/private/firebase-admin.json`, đặt `mode` trong cấu hình dịch vụ thành `firebase` và triển khai Firestore Rules mới. Khóa Admin chỉ nằm trên laptop; không đưa vào web, app hoặc firmware. Xem ảnh camera không cần khóa này.

1. Cấp nguồn camera, nối laptop cùng mạng và mở `services/face_attendance/mo-tram-khuon-mat.cmd`; giữ cửa sổ dịch vụ chạy.
2. Mở web và **đăng nhập Admin thật**. Trong chế độ dữ liệu mẫu, nút đăng ký bị khóa để tránh báo đã đăng ký khi chưa lưu mẫu thật.
3. Vào **Nhân viên**, tìm đúng người đang làm việc, bấm tên hoặc **Sửa hồ sơ**, rồi đến mục **Khuôn mặt → Đăng ký khuôn mặt**. Nhân viên mới cũng có mục này trong form thêm; cần lưu hồ sơ trước.
4. Chọn trạm camera, đặt đúng nhân viên trước camera và đủ sáng; đánh dấu xác nhận đúng người, rồi bấm **Mở camera để chụp 6 ảnh**.
5. Nhìn thẳng và chụp 2 ảnh đạt; tiếp theo quay nhẹ đầu về tay trái của nhân viên và chụp 2 ảnh, rồi quay nhẹ sang phải và chụp 2 ảnh. Canh đủ cả cằm; giữ yên và vẫn để camera thấy cả hai mắt. Phần **Ảnh vừa chụp** hiện đúng ảnh được kiểm tra, kể cả ảnh bị từ chối. Ảnh đạt mới tăng tiến độ **0/6 → 6/6**; ảnh lỗi giữ nguyên bước để chụp lại. Web chỉ xác nhận đã đăng ký khi laptop hoàn tất và hồ sơ mẫu trên Firebase được cập nhật.

Ảnh xem lại của lần bấm chỉ giữ tạm trên RAM của laptop tối đa năm phút, thay bằng ảnh ở lần chụp tiếp theo và xóa khi hủy/đăng ký lỗi/dừng trạm. Đóng cửa sổ hoặc ẩn trang giải phóng ảnh xem trên trình duyệt. Ảnh đạt lưu để đăng ký vẫn theo cơ chế dữ liệu local hiện có; không tải ảnh lên Firestore. Cập nhật tính năng này cần mở lại cả web Node và dịch vụ laptop, sau đó **Ctrl+F5** trên web; không cần nạp firmware. Kế hoạch sáu ảnh cần bản Rules mới chấp nhận đúng `MANUAL` + tổng 6 + `FRONT_LEFT_RIGHT_V1`.

**Đóng** cửa sổ tiến độ không hủy yêu cầu. Dùng **Xem yêu cầu** để mở lại, hoặc **Hủy yêu cầu** khi đang chờ/thu ảnh; laptop xác nhận hủy. Khi đang lưu mẫu, không thể hủy. Nếu mất kết nối theo dõi, dùng **Thử theo dõi lại**; nút này không gửi yêu cầu đăng ký mới. Nếu cột khuôn mặt báo lỗi dữ liệu/quyền, kiểm tra kết nối và Rules rồi dùng **Tải lại dữ liệu mặt**.

Trạm chỉ được chọn khi chạy chế độ Firebase, camera sẵn sàng và có heartbeat mới trong 90 giây. Một trạm chỉ xử lý một yêu cầu; cùng một nhân viên cũng chỉ có một yêu cầu đang chạy, kể cả khi bấm từ web và app cùng lúc. **Xóa mẫu mặt** xóa vector nhận diện và tắt hồ sơ mẫu; ảnh đã thu trên laptop vẫn được giữ theo cơ chế hiện tại của dịch vụ.

## Kết nối với app

Khi chạy lần đầu, máy chủ tự tạo `firebase-config.js` từ cấu hình client trong `../app/google-services.json`. Tệp cấu hình và thư mục build đã được bỏ qua trong Git.

Để dùng cấu hình riêng cho web, đăng ký ứng dụng Web trong Firebase Console → Project settings → Your apps, sao chép `firebase-config.example.js` thành `firebase-config.js`, rồi điền đối tượng cấu hình Web. Nếu API key của Android bị giới hạn chỉ dành cho Android, sử dụng cấu hình Web này. Chỉ cần Firebase client config; không dùng service-account hoặc khóa thiết bị.

Đăng nhập bằng **tài khoản Admin hiện có trên app**. Hồ sơ `users/{uid}` phải có `role: "ADMIN"`, `active: true`. Tài khoản nhân viên và tài khoản thiết bị không được mở web admin. Nếu quyền bị thu hồi khi đang dùng, web đóng phiên dữ liệu và hủy các listener. Phiên Auth chỉ lưu trong phiên trình duyệt hiện tại.

## Các màn hình

| Mục | Thao tác |
| --- | --- |
| Tổng quan | Chấm công, công việc cần theo dõi, biểu đồ 7 ngày và kết nối thiết bị |
| Công việc | Tạo/giao/sửa việc, xem báo cáo và tệp đính kèm, duyệt hoặc yêu cầu làm lại, lịch sử phiên bản |
| Nhân viên | Thêm/sửa hồ sơ, đơn giá theo giờ, tạo tài khoản đăng nhập riêng, đăng ký/xóa vân tay và khuôn mặt, theo dõi/hủy yêu cầu mặt, chuyển nhân viên sang đã nghỉ |
| Phòng ban | Thêm/đổi tên, đồng bộ tên trên hồ sơ thành viên, ngừng hoặc kích hoạt lại |
| Lịch làm việc | Lịch tuần/tháng, trạng thái riêng từng ca, phân nhiều nhân viên/phòng ban và từng ngày, gộp/thay ca, sao chép tuần trước |
| Ca làm | Ca chính chuẩn, cấu hình ca bổ sung, gán tăng ca cố định |
| Đơn từ | Duyệt đăng ký tuần, đơn nghỉ/đổi ca/điều chỉnh công và tăng ca; phản hồi cho nhân viên |
| Chấm công | Lọc ngày/nhân viên/phòng ban, xem nguồn gốc, sửa phân loại, điều chỉnh công, xử lý ngoài lịch, xuất CSV |
| Có mặt | Trạng thái từng nhân viên theo ca đang theo dõi |
| Bảng lương | Tạo phiếu, xem trước tính lại, lưu cả phiếu trước/sau vào lịch sử, xuất CSV |
| Báo cáo & bảng công | Công theo ngày/tháng, chi tiết ngày/ca, nghỉ phép, tăng ca, lương, thiết bị, công việc và tổng hợp kết quả |
| Hiệu suất | Chỉ số công việc, công/thưởng theo kỳ, lưu điểm đánh giá và xuất CSV |
| Thiết bị IoT | Heartbeat, trạng thái cảm biến/Wi-Fi/đồng bộ, hàng đợi, sửa vị trí/tên và gửi lệnh thiết bị |
| Camera & khuôn mặt | Xem ảnh camera liên tục, bật/dừng/thử lại, xoay góc xem và kiểm tra cảm biến |
| Thông báo | Xem/đánh dấu đã đọc, gửi theo phòng ban hoặc toàn bộ nhân viên, lịch sử gửi |
| Tiện ích nhân viên | Tạo/sửa/xóa lịch họp, khen thưởng và tài liệu theo đối tượng nhận |
| Nhật ký | Tìm kiếm 200 thao tác gần nhất |

Các thao tác dùng schema, giao dịch và Firestore Rules của app. Chuyển nhân viên sang đã nghỉ khóa hồ sơ tài khoản liên kết và giữ lịch sử. Ca chính và thao tác xóa công việc tuân theo giới hạn của app/rules; ca bổ sung và phòng ban dùng ngừng hoạt động để giữ dữ liệu cũ.

Phạm vi web là tài khoản **Admin**. Các luồng nhân viên tự gửi đơn, đăng ký tuần, cập nhật tiến độ và nộp kết quả vẫn nằm trong app của nhân viên.

## Công và lương

Web phân giải `SCAN/PENDING` trong bộ nhớ như Android trên Firebase Spark. Lượt gốc không bị sửa. Sửa phân loại, điều chỉnh công và duyệt ngoài lịch tạo bản ghi riêng kèm nhật ký nguyên tử. Tính theo `Asia/Ho_Chi_Minh`, từng ca sáng/chiều độc lập, xử lý quét trùng, nghỉ theo ca và khung tăng ca cố định.

Trước khi tính hoặc xuất công, web đọc đủ các trang dữ liệu trong khoảng đã chọn và các ngày giáp ranh. Một lần tải tối đa 93 ngày/20.000 bản ghi mỗi nguồn. Listener giữ tối đa 500 bản ghi; nếu thay đổi gây mất chắc chắn về độ đầy đủ, web yêu cầu tải lại trước khi tính. Lỗi kết nối hoặc lỗi index hiện rõ, không biến thành báo cáo trống thành công.

Phiếu lương đã lưu giữ nguyên khi xem dữ liệu mới. **Tính lại** mở bản xem trước và yêu cầu lý do; xác nhận ghi phiên bản kế tiếp cùng bản trước/sau trong một transaction. Web phát hiện phiếu hoặc đơn giá đã thay đổi và giữ nội dung form để kiểm tra lại.

## Build và triển khai

```powershell
npm test
npm run build
```

Lần kiểm tra ngày **12/10/2026**: **179/179 kiểm thử web đạt**, gồm 13 kịch bản giao dịch/quyền Firestore Emulator; build thành công. Bao gồm form nhân viên gộp, giữ nội dung chưa lưu, ca mặc định, phân ca nhiều người/từng ngày và lịch tuần/tháng. Không ghi Firebase production. Để chạy lại đầy đủ, bật Firestore Emulator và đặt `WEB_FIRESTORE_EMULATOR_HOST` đúng cổng loopback trước `npm test`; nếu không bật emulator, 13 kịch bản này được bỏ qua.

Để kiểm tra thao tác chuột và biểu mẫu trên Edge, giữ máy chủ local đang chạy, mở terminal thứ hai tại `web-admin` bằng Node.js 22 trở lên rồi chạy:

```powershell
$env:WEB_BROWSER_EXECUTABLE = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
npm run test:browser
```

Kiểm tra này mở một hồ sơ trình duyệt riêng, thay Firebase bằng dữ liệu trong bộ nhớ và chỉ truy cập máy chủ local. Nó kiểm tra mở/lưu biểu mẫu, nút đang chờ, bấm sau khi lọc và thông báo lỗi; không dùng tài khoản hay ghi dữ liệu Firebase thật. Có thể đặt đường dẫn Chrome thay cho Edge, hoặc `WEB_BROWSER_URL` nếu dùng cổng local khác.

Build tạo `web-admin/dist`. Đã thêm cấu hình Firebase Hosting vào `../firebase.json`; cấu hình Functions/Firestore hiện có được giữ.

Sau khi chọn đúng Firebase project và kiểm tra domain được cho phép trong Authentication → Settings → Authorized domains, từ **thư mục gốc dự án** chạy:

```powershell
firebase deploy --only hosting
```

Đây là bước triển khai riêng; việc chạy local/build không tự deploy hoặc thay Firestore Rules. Các rules và indexes đang dùng phải phù hợp phiên bản của app. Có thể đặt `PORT` trước `npm start` nếu cổng 4173 đã được sử dụng.

Chi tiết kiểm tra, phạm vi và đối chiếu nằm tại [docs/WEB_ADMIN.md](../docs/WEB_ADMIN.md).

SDK CDN và phiên Auth theo tài liệu chính thức: [Firebase Web](https://firebase.google.com/docs/web/alt-setup), [Auth persistence](https://firebase.google.com/docs/auth/web/auth-state-persistence).
