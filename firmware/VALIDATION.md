# Kiểm chứng firmware ESP8266 và camera

Ngày cập nhật: 11/10/2026. Firmware hiện tại là `spark-anonymous-v16-tls-memory`, giữ sửa kết quả lệnh của v14, heartbeat của v15 và hành vi ngoại tuyến của v12. Phép đo cửa trực tiếp ngày 04/10 thuộc bản v9; log người dùng cung cấp sau đó thuộc v10, v11, v14 và v15. Người dùng đã xác nhận cửa hoạt động trở lại; chưa coi một chu kỳ cửa là nghiệm thu toàn bộ thiết bị hoặc luồng mạng.

## Chuyển camera sang ESP32-S3 N16R8 — 11/10/2026

Người dùng đã mua board ESP32-S3-WROOM-1 N16R8 CAM hai Type-C, chưa thao tác phần cứng. Tên sản phẩm ghi OV2640 nhưng người dùng báo cáp camera ghi OV5640. Firmware để driver tự dò cảm biến, in tên/PID, dung lượng flash/PSRAM ra Serial và `/health`; không ép cảm biến thành OV2640. SDK S3 `qio_opi` của core 3.3.12 bật cả `CONFIG_OV2640_SUPPORT=1` và `CONFIG_OV5640_SUPPORT=1`.

`camera_board.h` tự chọn profile theo chip ESP-IDF và từ chối profile/chip không khớp khi biên dịch. S3 dùng pinout [S3N16R8 của tác giả esp32cam](https://github.com/yoursunny/esp32cam/blob/main/src/esp32cam/pins.hpp), không điều khiển đèn GPIO4 vì đây là SDA camera. Nếu thiếu PSRAM, S3 báo lỗi và không khởi tạo camera bằng DRAM; AI Thinker cũ giữ chế độ QVGA dự phòng. Giao thức JPEG/token/frame ID, dịch vụ laptop và cấu trúc dữ liệu Android/Firebase được giữ nguyên.

Biên dịch đầy đủ bằng `firmware/esp32_cam/compile.ps1`, core Espressif **3.3.12**:

| Cấu hình camera | Flash ứng dụng | RAM tĩnh | Kết quả |
| --- | --- | --- | --- |
| ESP32S3 Dev Module, flash QIO 16MB, OPI PSRAM, UART/CDC Disabled | 995.294 / 3.145.728 byte (31%) | 57.608 / 327.680 byte (17%) | Đạt |
| Cùng board S3, USB tích hợp/CDC Enabled, Hardware CDC and JTAG | 1.012.231 / 3.145.728 byte (32%) | 57.980 / 327.680 byte (17%) | Đạt |
| Hồi quy AI Thinker ESP32-CAM, flash QIO 4MB, PSRAM, ứng dụng 3MB | 1.051.406 / 3.145.728 byte (33%) | 60.292 / 327.680 byte (18%) | Đạt |

FQBN S3 dùng `FlashMode=qio,FlashSize=16M,PSRAM=opi,PartitionScheme=app3M_fat9M_16MB,USBMode=hwcdc`; `CDCOnBoot=default` cho UART và `cdc` cho USB tích hợp. Script giữ nguyên flags PSRAM/USB của Espressif, bổ sung thư viện S3 còn thiếu vào SDK riêng trong `.gradle/esp32-arduino` bằng archive có kích thước/SHA-256 đúng index chính thức. Không thay SDK Arduino IDE toàn máy. Output nằm trong `.gradle/esp32-camera-build-s3n16r8-uart` và `.gradle/esp32-camera-build-s3n16r8-native-cdc`.

Hồi quy classic dùng `esp32:esp32:esp32cam:CPUFreq=240,FlashMode=qio,PartitionScheme=huge_app`, output tại `.gradle/esp32-camera-build-ai-thinker`. SHA-256 của sketch và header trong cả ba thư mục staging khớp nguồn cuối cùng; sau biên dịch không còn ánh xạ ổ đĩa SUBST tạm.

Kiểm chứng phần mềm: **118/118** bài `node --test firmware/test/*.test.cjs` đạt, gồm 9 bài camera. Bốn bài mới chạy hàm khởi tạo thực với thiết bị giả: thiếu PSRAM, dò OV5640/cấu hình JPEG PSRAM và không ghi GPIO4, AI Thinker dùng flash/QVGA DRAM, lỗi dò cảm biến rồi phục hồi. Bốn phép biên dịch cú pháp C++ riêng xác nhận tự chọn hai profile và từ chối cả hai trường hợp profile/chip không khớp. **11/11** bài Python nguồn ảnh/chẩn đoán đạt; đây là phần kiểm tra liên quan camera, không phải chạy lại toàn bộ suite Python. Script PowerShell parse và `git diff --check` đạt.

**Giới hạn tại thời điểm biên dịch:** chưa nạp hoặc thử camera thật. Các bản biên dịch dùng sketch sao chép với Wi-Fi/token giả, không đọc bí mật thật, không truy cập COM và không dùng output để nạp trực tiếp. Chữ ESP32-S3 WROOM/N16R8 không xác nhận pinout của mọi PCB. Giữ `ATTENDANCE_GATEWAY_MODE=0` cho đến khi dịch vụ laptop và luồng Firebase thật đã sẵn sàng.

**Cập nhật thử camera thật ngày 11/10/2026:** người dùng tự nạp và cung cấp log nhận OV5640 (`PID 0x5640`), flash **16.777.216 byte**, PSRAM **8.388.608 byte**, JPEG VGA, IP LAN `192.168.0.110`. Sau lần khởi động lại, laptop đọc `/health` HTTP 200 với `cameraReady=true`, `cameraError=0` và dung lượng bộ nhớ khớp log. Đã lấy JPEG **640×480, 12.360 byte**, lưu tại `services/face_attendance/data/preview/camera-test.jpg` được Git bỏ qua và mở trong trình xem ảnh. Ảnh hiển thị được nhưng đang xoay ngang; cần chỉnh tư thế đặt board trước khi thu mẫu. `run.ps1 doctor --camera` báo `ok=true`, `cameraReady=true`, `readyForRecognition=false` vì chưa có hồ sơ mặt. Chưa thu/đăng ký mẫu nhân viên, tạo lượt công hoặc kiểm tra Wi-Fi/nguồn dài hạn; chưa chuyển ESP8266 sang gateway. Lần kết nối ban đầu đã timeout, chưa xác định nguyên nhân và không coi một ảnh thành công là đã xác nhận độ ổn định.

## Xóa vân tay bị kẹt đồng bộ — 11/10/2026

Log v16 có lệnh `DELETE_FINGERPRINT` ở `COMPLETED`, PATCH kết quả HTTP 200 nhưng commit hoàn tất HTTP 403. LCD hiện `CHO DONG BO` / `KIEM TRA MANG`, app hiện “Đã xong, đang đồng bộ”. Đọc trực tiếp Firebase xác nhận mẫu 14 đã được báo xóa, `applied=false`, hồ sơ còn `fingerprintTemplateId=14` và `pendingTemplateId=14` nhưng thiếu trường `fingerprintDeviceId`; mapping vẫn được giữ và tắt. Các Rules vân tay đang triển khai khớp mã nguồn.

Nguyên nhân: mô hình Kotlin có giá trị mặc định `GATE-01`, nên app vẫn tạo lệnh hợp lệ dù hồ sơ cũ thiếu trường trong Firestore. Rules đọc trường thực tế để kiểm tra đồng bộ nguyên tử giữa lệnh, hồ sơ và mapping. `removeEmployeeOrFingerprint()` nay lưu cả `fingerprintDeviceId=commandRef.id` và `pendingTemplateId` trong transaction tạo lệnh xóa, áp dụng cả trường hợp cho nhân viên nghỉ.

Đã sửa hồ sơ đang kẹt bằng transaction chỉ bổ sung mã thiết bị; trước khi ghi kiểm tra đúng request, nhân viên, mẫu, trạng thái lệnh và mapping. ESP tự thử lại và hoàn tất lúc **08:59:37 ngày 11/10/2026 (UTC+7)**: lệnh `COMPLETED/applied=true`, hai trường mẫu trong hồ sơ về null và mapping 14 không còn. Đây là bằng chứng hoàn tất đồng bộ trên Firebase; chưa quan sát trực tiếp LCD hoặc thử quét lại mẫu đã xóa.

Kiểm chứng: 4/4 kiểm thử Firestore emulator đạt, tái hiện hồ sơ thiếu/rỗng mã thiết bị trả 403 và xác nhận cùng lệnh hoàn tất sau khi Admin bổ sung trường; thiết bị Anonymous không được tự sửa mã thiết bị. 7/7 kiểm thử kết quả lệnh firmware và 369/369 kiểm thử Android đạt; `:app:assembleDebug` thành công. APK mới ở `app/build/outputs/apk/debug/app-debug.apk`, cần cài để tránh lỗi lặp lại khi tạo lệnh xóa cho hồ sơ cũ. Lần sửa này không thay đổi Rules hoặc firmware thực thi. Lỗi đọc AS608 mã 32 trong log đã được firmware kết nối lại; kết quả đồng bộ trên không xác nhận lỗi UART/nguồn đã được xử lý.

## Bản sửa bộ nhớ HTTPS v16

Log v15 dừng sau `DANG KY: dat ngon tay lan 1 (template 20)`, báo `Unhandled C++ exception: OOM` rồi reset Software Watchdog. Giải mã bằng ELF v15 đã biên dịch trên máy trỏ về `publishDeviceSnapshot()` → HTTPClient → BearSSL `_installClientX509Validator()` → `operator new`. Không có thời gian giữa các dòng để kết luận đăng ký bị timeout hay lỗi cảm biến. Khi đăng ký kết thúc, `pendingCommandExecution` đã tắt nhưng `pendingCommandResult` còn bật; heartbeat trước đây có thể chen vào trước khi báo kết quả lệnh.

- Chuyển chuỗi LCD/log sang Flash bằng `F()` và `printf_P(PSTR())`. RAM tĩnh giảm từ **39.552** byte trong ELF v15 sang **34.832** byte, tiết kiệm **4.720 byte**.
- Cả 8 request Auth, heartbeat, mapping, gửi công và lệnh đều kiểm tra lại heap/block sau khi đã tạo BearSSL client và header HTTP, ngay trước request. Giữ kiểm tra ban đầu 30.000/20.000 byte; kiểm tra trước TLS yêu cầu heap ≥28.000 và block ≥24.000 byte. BearSSL core 3.1.2 cấp phát stack 6.200 byte khi tạo client và khoảng 22.194 byte cho các context/buffer kết nối. Ngưỡng sau chuẩn bị dành thêm khoảng 5,8 KB cho TCP, header và phần phụ trợ; vẫn cần đối chiếu trên thiết bị thật.
- Giải phóng URL tạm sau `https.begin()`, kiểm tra reserve và độ dài serialize của body heartbeat. Hoãn khi thiếu RAM; giữ hàng đợi công, capabilities và kết quả lệnh để retry. Không thu nhỏ buffer nhận TLS 16 KB khi chưa xác nhận hỗ trợ MFLN.
- Hoãn heartbeat khi đăng ký còn hoạt động hoặc kết quả lệnh thường chưa gửi xong. Giữ cơ chế luân phiên heartbeat/đồng bộ nền của `SYNC_ATTENDANCE`.
- Chạy `node --test firmware/test/*.test.cjs` đạt **99/99**, không thất bại hoặc bỏ qua. Các bài mới thực thi guard thật với heap/block ở ranh giới, phân mảnh sau chuẩn bị request và cooldown qua millis rollover; kiểm tra reserve/serialize lỗi, dọn client khi hoãn và giữ dữ liệu chờ. Bài tích hợp chạy `loop()` cùng `serviceEnrollment()`/`finishEnrollment()` qua lưu mẫu thành công, lỗi nhận gói cảm biến và timeout; heartbeat chỉ tiếp tục sau khi kết quả lệnh được xác nhận.
- Biên dịch NodeMCU v2/core ESP8266 3.1.2 thành công: RAM **34.832/80.192 byte (43%)**, IRAM **64.103/65.536 byte (97%)**, flash **464.820/1.048.576 byte (44%)**. Bản build và log ở `firmware/build/esp8266-oom-fix`. Cảnh báo tương thích kiến trúc LiquidCrystal_I2C đã có từ trước.

Khi nạp, giữ LittleFS nếu còn công chờ gửi. Serial 9600 baud phải hiện `FW: spark-anonymous-v16-tls-memory`. Thử đăng ký thành công, để hết thời gian chờ và gửi lại khi mạng lỗi; xem `HTTPS ... truoc TLS: heap=..., block=..., frag=...`, kết quả lệnh và `HEARTBEAT HTTP 200`. Nếu RAM không đủ, phải thấy `HTTPS bo qua ...` và retry, không coi hoãn gửi là đã hoàn tất lệnh. Phiên sửa này chưa nạp hoặc thử trên bo thật; kiểm tra giả lập không thay thế TLS/UART/nguồn thực tế.

## Bản sửa kết quả lệnh v14

Log v11 có `OUTBOX dang cho: 0`, lệnh `SYNC_ATTENDANCE` liên tục ở `PROCESSING`, trong khi thiết bị kêu và sáng đèn xanh. Nguyên nhân trong mã: app tạo lệnh thông thường với `applied=true` vì không cần ghi liên kết vân tay, nhưng `updateDeviceCommandStatus()` dùng cờ này để trả thành công trước khi PATCH `COMPLETED`. Kết quả chỉ hoàn tất trong RAM; Firestore vẫn `PROCESSING` và lần đọc tiếp theo chạy lại lệnh.

- Bỏ nhánh trả thành công theo `applied` trong cập nhật trạng thái. Lệnh phải nhận HTTP 2xx từ PATCH có điều kiện `updateTime` trước khi xóa kết quả chờ và báo thành công. Lỗi/hoãn HTTPS giữ kết quả để gửi lại; bản công trong LittleFS vẫn dùng luồng hiện có.
- Giữ `applied=true` cho lệnh thông thường trong app để lệnh hoàn tất không bị coi là còn chờ ghi liên kết. Luồng commit vân tay vẫn tự kiểm tra `applied` riêng để tránh ghi lại liên kết đã áp dụng.
- Chạy `node --test firmware/test/*.test.cjs` đạt **69/69**, không thất bại hoặc bỏ qua. Bảy bài mới thực thi hàm refresh/cập nhật/hoàn tất thật với HTTP giả: lệnh có `applied=true` vẫn gửi PATCH, lỗi giữ kết quả để retry và không báo thành công/khởi động lại sớm, request mới không nhận kết quả cũ, commit vân tay đã áp dụng không ghi lại. Các bài này không thay thế thử TLS/UART/nguồn thật.
- Biên dịch NodeMCU v2/core ESP8266 3.1.2 thành công: RAM 39864/80192 byte (49%), IRAM 64091/65536 byte (97%), flash 453940/1048576 byte (43%). Build ở `firmware/build/esp8266-command-status`; phiên sửa này không nạp hoặc truy cập cổng COM.
- Khi thử trên thiết bị, xác nhận banner `FW: spark-anonymous-v14-command-status`, sau đó lệnh đang kẹt nhận `Cap nhat lenh: HTTP 200` và app chuyển sang `COMPLETED`. Thử lại lệnh mới và ngắt mạng ở bước gửi kết quả để kiểm tra retry. Lỗi AS608 mã 1, phân mảnh heap và heartbeat 403 cần kiểm tra riêng nếu vẫn tái diễn.

Log v14 mới do người dùng cung cấp ghi nhận nút LOW → HIGH được nhận, chuỗi lệnh mở servo 3647 ms, giữ mở 5000 ms và chuỗi lệnh đóng 3580 ms, không có reset trong đoạn log đó. Lỗi HTTPS HTTP -1 xuất hiện sau khi cửa đã đóng. Người dùng cho biết cửa đã hoạt động trở lại và nhận định Wi-Fi yếu; log này xác nhận thời hạn phần mềm của chu kỳ cửa, chưa chứng minh nguyên nhân mạng hoặc thời gian di chuyển cơ khí bằng đo vị trí/video.

## Bản mở cửa khi khởi động chưa có mạng v12

Người dùng xác nhận bật nguồn ESP khi chưa có Wi-Fi. Bản v11 giữ mẫu AS608 nhưng yêu cầu giờ hợp lệ trước khi lưu và mở cửa; đây là nguyên nhân phần mềm từ chối lượt trong tình huống này. `spark-anonymous-v12-coldboot-access` tách việc mở cửa khỏi ghi công khi chưa có giờ:

- Đúng mẫu AS608 và chính sách ngoại tuyến được bật: trả `ACCESS_ONLY`, mở cửa, LCD `DANG MO CUA` / `CHUA LUU CONG`, giữ cửa 5 giây sau khi mở hoàn toàn rồi đóng.
- Không cấp mã sự kiện, không thêm bản vào LittleFS, không báo có công chờ đồng bộ và không tạo lượt bằng giờ kết nối lại. Đây là mở cửa, không phải chấm công ngoại tuyến khi khởi động chưa có giờ.
- Vân tay sai và liên kết bị server từ chối vẫn không mở. Khi đã có giờ hợp lệ, lượt vẫn cần lưu bền vững trước khi được mở theo chính sách ngoại tuyến; lỗi ghi, hàng đợi đầy hoặc lỗi cấp mã sự kiện không dùng `ACCESS_ONLY`.
- AS608 giữ các mẫu đã đăng ký; không tự tải mẫu từ Firebase. ESP không thêm danh sách nhân viên hay cache danh tính.

Ghi công vẫn cần giờ NTP hợp lệ sau mỗi lần bật nguồn/reset. Nếu đã đồng bộ giờ rồi mất mạng, ESP tiếp tục giữ giờ khi còn nguồn và không reset, lưu lượt đúng thời điểm để gửi lại. Lượt `ACCESS_ONLY` chưa có giờ không có bản chấm công để đồng bộ sau đó.

Sửa thêm luồng tự gửi lại: Wi-Fi vừa kết nối lại cho hàng đợi một lần retry ngay khi cửa đóng; kết nối còn hoạt động nhưng HTTPS lỗi vẫn giữ cooldown. Hoãn chưa gửi không dời hạn retry. Gửi từng bản và ưu tiên hàng đợi trước đọc lệnh mới/heartbeat. Nếu công đã gửi hết nhưng PATCH kết quả `SYNC_ATTENDANCE` thất bại, Serial ghi `CHO GUI KET QUA LENH`; LCD trở về trạng thái thật của hàng đợi thay vì báo còn công chờ gửi.

Sau khi quay về v12, đã biên dịch lại cho NodeMCU v2 với core ESP8266 3.1.2 thành công: RAM 39880/80192 byte (49%), IRAM 64091/65536 byte (97%), flash 453972/1048576 byte (43%). Chạy lại `node --test firmware/test/runtime.test.cjs firmware/test/offlineAttendance.test.cjs` đạt **62/62** (47 điều khiển, 15 dữ liệu ngoại tuyến), không thất bại hoặc bỏ qua. Các bài kiểm thử bao phủ khởi động chưa có giờ, không tạo công giả sau kết nối lại, đóng cửa sau 5 giây, ranh giới từ chối/lỗi lưu, retry không trôi hạn, gửi hai bản liên tiếp khi kết nối lại và LCD khi gửi kết quả lệnh thất bại. Kiểm thử dùng đồng hồ/thiết bị giả, không thay thế UART/TLS/nguồn thật. Người dùng tự nạp bằng Arduino IDE; lần quay về v12 này không nạp hoặc truy cập COM3.

Giới hạn lịch sử của v12, được sửa từ v14: lệnh thông thường có `applied=true` có thể được coi là hoàn tất trong RAM trước khi ghi trạng thái cuối lên Firestore, khiến app vẫn thấy `PROCESSING` dù hàng đợi đã rỗng. Kết quả 62 bài kiểm thử của v12 không chứng minh lỗi này đã hết.

## Bản LCD v10

Phiên bản `spark-anonymous-v10-lcd-feedback` đã biên dịch thành công cho NodeMCU v2 (RAM khoảng 49%, IRAM khoảng 97%, flash khoảng 42%). Bộ kiểm thử `node --test firmware/test/runtime.test.cjs` đạt 24/24.

Lần nạp thử v10 bằng CLI chưa thành công: esptool báo không mở được COM3 (`PermissionError: Access denied`). Arduino CLI vẫn trả exit code 0, nên cần đọc kết quả esptool và xác minh hash để kết luận nạp thành công. Sau đó người dùng cung cấp log khởi động `FW: spark-anonymous-v10-lcd-feedback`, chứng minh thiết bị đã chạy v10. Log này cho thấy sự kiện `GATE-01-a76913-68` được giữ khi POST lỗi, khôi phục sau khởi động rồi nhận `ATTENDANCE HTTP 200`; không có lệnh mở cửa cho bản khôi phục. Có các lỗi đọc AS608 lặp lại; chưa xác định nguyên nhân vật lý và chưa coi cảm biến là đã ổn định. Các log đo chu kỳ cửa bên dưới vẫn là bằng chứng v9.

Các hồi quy mới đọc và chạy hàm điều khiển thực tế với đồng hồ/thiết bị giả:

- Thông báo vân tay sai, nhân viên không được phép và các lỗi lượt quét giữ 1000 ms, kể cả nhấc ngón tay ngay và tràn bộ đếm `millis()`.
- Trong thời gian giữ thông báo, `loop()` vẫn phục vụ nút cửa, servo và hiệu ứng; mốc đóng cửa sau 5 giây không bị trì hoãn.
- Lượt đã lưu chưa xác nhận giữ tên nhân viên cùng `DANG XU LY` sau khi nhấc ngón tay, không mở cửa.
- Xác nhận đúng lượt hiện tại đổi thông báo sang `DANG MO CUA` và bắt đầu mở cửa; phản hồi từ chối giữ thông báo đủ 1 giây.

## Bản ngoại tuyến v11

Người dùng đã chọn mở cửa theo mẫu còn lưu trong AS608 khi không xác thực được server, không lưu danh sách nhân viên trên ESP. `spark-anonymous-v11-offline-as608` triển khai chính sách này bằng `OFFLINE_AS608_ACCESS_ENABLED=true`.

Biên dịch cuối cùng cho NodeMCU v2 thành công: RAM 39704/80192 byte (49%), IRAM 64091/65536 byte (97%), flash 453620/1048576 byte (43%). Chạy `node --test firmware/test/runtime.test.cjs firmware/test/offlineAttendance.test.cjs` đạt **55/55**, không có kiểm thử bỏ qua hoặc thất bại. Đây là kiểm thử điều khiển với đồng hồ/thiết bị giả, không thay thế UART, mạng và nguồn trên phần cứng thật.

- Mẫu vân tay nằm trong AS608. ESP chỉ ghi từng lượt gồm mã thiết bị, vị trí mẫu, độ khớp và thời gian vào LittleFS; hàng đợi giới hạn 12 KB và được đọc từng bản. Không ghi cache liên kết nhân viên.
- Chỉ mở cửa ngoại tuyến sau khi lượt vừa khớp đã được ghi thành công và còn trong thời hạn 15 giây. Xác nhận/từ chối của server là trạng thái riêng với quyết định mở cửa theo mẫu cục bộ.
- Khi có mạng, firmware đọc liên kết hiện tại cho từng lượt thô, giữ nguyên mã sự kiện/thời gian rồi gửi payload Firestore như luồng online. Mapping bị khóa/xóa bị từ chối; lỗi kết nối giữ lượt để retry. Đồng bộ bản cũ, xác nhận muộn hoặc bản khôi phục sau khởi động không mở lại cửa.
- Đăng ký/xóa mẫu chờ hàng đợi rỗng trước khi thay đổi chủ sở hữu vị trí mẫu, tránh gán lượt cũ sang nhân viên mới. `SYNC_ATTENDANCE` chạy nền và không giữ quyền sử dụng cảm biến.
- Một lần quét mới hủy quyền mở cửa của lượt trước trong RAM, kể cả lần mới sai, bị từ chối hoặc không lưu được; các bản cũ trong LittleFS vẫn được giữ.
- Cảm biến được thăm dò mỗi 80 ms. Lỗi ảnh/chất lượng vân tay không vô hiệu hóa cả cảm biến; lỗi giao tiếp vẫn có phục hồi và log mã lỗi để kiểm tra trên thiết bị.

Giới hạn của v11: cần giờ hợp lệ đã đồng bộ NTP trước đó; khởi động hoàn toàn offline chưa có giờ thì không tạo lượt và không mở cửa bằng vân tay. Khi hàng đợi đầy hoặc không ghi được LittleFS cũng không mở. Nhân viên đã nghỉ vẫn có thể mở offline nếu mẫu chưa được xóa khỏi AS608. Luồng cho nghỉ thành công trong app tạo lệnh xóa cho thiết bị; chỉ sửa `active=false` trên Console không tạo lệnh, và lệnh `FAILED` cần Admin gửi lại.

Thử nạp v11 lên COM3 bằng CLI chưa thành công: esptool báo `PermissionError: Access denied`; không coi exit code 0 của Arduino CLI là nạp thành công. Người dùng chọn tự nạp bằng Arduino IDE, sau đó cung cấp log `FW: spark-anonymous-v11-offline-as608`. Hai lượt `GATE-01-a76913-69` và `...-70` được lưu và mở theo mẫu AS608 khi HTTPS đang tạm hoãn; log bắt đầu đóng sau 5004 ms và 5012 ms mở hoàn toàn. Tiếp đó bản 69 nhận `ATTENDANCE HTTP 200` và được xóa khỏi hàng đợi. Log dừng ở đây, chưa chứng minh bản 70 đã gửi hoặc LCD đã trở về sẵn sàng. Cần thử lại các điểm này bằng v12 và kiểm tra xóa mẫu nhân viên đã nghỉ trên thiết bị.

## Bằng chứng bản v9

```powershell
node --test firmware/test/runtime.test.cjs
arduino-cli compile --fqbn esp8266:esp8266:nodemcuv2 --build-path firmware/build/esp8266 firmware/esp8266_fingerprint
arduino-cli board list
```

Các kiểm thử Node đọc và chạy phần điều khiển trong mã `.ino` bằng bộ chuyển cú pháp vô hướng, đồng hồ và thiết bị giả. Chúng kiểm tra bộ đếm cửa, từng bước đăng ký, hàng đợi một bản mỗi lượt, giữ bản khi lỗi mạng/ghi bộ nhớ, phân biệt xác nhận/từ chối, chống mở cửa do xác nhận bản cũ hoặc quá muộn, và thời hạn lệnh SYNC. Chúng không mô phỏng UART, TLS, ghi flash vật lý hoặc chuyển động servo.

Biên dịch đã thành công với core ESP8266 3.1.2, board NodeMCU v2, Adafruit Fingerprint 2.1.4, ArduinoJson 7.4.3 và LiquidCrystal_I2C 2.0.0. Công cụ có cảnh báo tương thích kiến trúc của LiquidCrystal_I2C; cần xác nhận LCD trên thiết bị thực. Bộ nhớ của bản build: RAM khoảng 49%, IRAM khoảng 97%, flash khoảng 42%.

Sau khi kết nối thiết bị, `arduino-cli board list` thấy COM3. Người dùng xác nhận NodeMCU; đã nạp bản build `nodemcuv2` lên COM3, công cụ nhận ESP8266EX với flash 4 MB và xác minh hash thành công.

Serial 9600 baud đã ghi được chu kỳ cửa trên thiết bị:

```text
CUA: OPEN hoan toan; servo di chuyen 3580 ms
CUA: bat dau dong sau 5004 ms mo hoan toan
CUA: CLOSED hoan toan; servo di chuyen 3576 ms
```

Một chu kỳ khác ghi thời gian giữ cửa 5000 ms. Khi nhấn lại lúc đang đóng, cửa chuyển về mở rồi giữ thêm 5004 ms trước khi đóng. Đây là thời gian chương trình gửi lệnh servo trên thiết bị thật; chưa có video/phản hồi vị trí để xác nhận thời gian chuyển động cơ khí. Mô hình kiểm thử trên máy cho 1,8 giây lý tưởng, còn lịch chạy thực tế ghi khoảng 3,58 giây cho chuỗi lệnh servo đầy đủ.

Đã chạy 16/16 kiểm thử hồi quy trên máy và biên dịch thành công. Serial cũng có `Heartbeat HTTP 403`, và một lần AS608 lỗi khi đọc số mẫu rồi kết nối lại thành công. Một lượt quét mới đã lưu, nhận HTTP 200 rồi mở cửa và bắt đầu đóng sau 5003 ms; một bản khôi phục sau khởi động đã đồng bộ mà không mở cửa. Người dùng báo thiết bị khởi động lại sau đăng ký; chưa ghi được reset reason/exception trong lần đó, nên chưa xác định nguyên nhân và chưa coi lỗi này là đã sửa. Kiểm thử hàm hoàn tất cho thấy chỉ lệnh `RESTART_DEVICE` yêu cầu `ESP.restart()`; đăng ký thành công/thất bại đều không gọi nó. Các luồng đăng ký đang chờ và mất mạng/hàng đợi vẫn cần bằng chứng trực tiếp; không coi chu kỳ cửa đơn lẻ là đã nghiệm thu toàn bộ firmware.

## Trạng thái và thời hạn

- `QUEUED`: đã ghi sự kiện vào LittleFS, chưa được Firestore xác nhận.
- `CONFIRMED`: Firestore đã chấp nhận đúng mã sự kiện (HTTP 2xx hoặc 409 idempotent).
- `REJECTED`: phản hồi từ chối vĩnh viễn; bỏ bản khỏi hàng đợi để các bản sau tiếp tục, thông báo từ chối và không mở cửa.
- `NOT_STORED`: không tạo/lưu được sự kiện; phân biệt với bản đã lưu đang chờ gửi.
- `LOCAL_ACCEPTED` (v11): đúng mẫu AS608, lượt đã lưu và server không thể xác thực; mở cửa theo chính sách ngoại tuyến đã chọn, dữ liệu vẫn chờ đồng bộ. Đây không phải xác nhận chấm công từ Firestore.
- `ACCESS_ONLY` (v12): đúng mẫu, chưa có giờ hợp lệ; chỉ mở cửa, LCD `CHUA LUU CONG`, không tạo bản công hoặc hàng đợi cho lượt này.

Đối với lượt đã lưu, chỉ lượt hiện tại trong RAM, dưới 15 giây từ lúc lưu, được mở cửa một lần: theo xác nhận server hoặc theo quyết định ngoại tuyến. `ACCESS_ONLY` mở ngay từ lần khớp hiện tại và không tạo bản chờ xác nhận. Bản khôi phục sau khởi động và xác nhận muộn chỉ đồng bộ dữ liệu. Hàng đợi gửi một bản mỗi lượt, nghỉ 250 ms sau khi có tiến triển và retry khoảng 30 giây khi lỗi. `SYNC_ATTENDANCE` giữ `PROCESSING` cho tới khi hàng đợi rỗng; sau 180 giây chưa xong thì báo `FAILED` và giữ các bản chưa gửi. Các bản bị từ chối được ghi vào Serial/`lastError`, và kết quả SYNC có thông báo nếu có bản bị từ chối trong lệnh đó.

HTTPS, truy cập cảm biến và các hiệu ứng chặn được hoãn khi cửa đang mở/chuyển động. Một HTTPS đã bắt đầu khi cửa đóng vẫn có thể làm chậm việc nhận nút cửa cho tới khi trả về; bộ đếm 5 giây bắt đầu sau chuỗi lệnh servo mở hoàn tất. Servo không có cảm biến phản hồi vị trí, nên mốc `OPEN` là kết thúc lệnh chuyển góc; độ mở cơ khí phải đo trực tiếp.

## Thử trên thiết bị trước khi nghiệm thu

Dùng đúng board/cổng COM được xác nhận, nguồn servo riêng ổn định và Serial 9600 baud. Ghi cả log và video có mốc thời gian; không coi thời gian mô phỏng là bằng chứng phần cứng.

| Thao tác | Bằng chứng cần ghi |
| --- | --- |
| Nhấn nút cửa lúc đăng ký đang chờ lần 1, nhấc ngón tay hoặc chờ lần 2 | Log `OPEN` rồi `bat dau dong sau ... ms` khoảng 5000 ms; đăng ký tiếp tục sau khi cửa đóng |
| Mở cửa trong lúc mạng chậm/mất phản hồi | Không bắt đầu HTTPS khi `OPENING`, `OPEN`, `CLOSING`; bộ đếm cửa vẫn chạy |
| Đo servo mở và đóng riêng | Log `servo di chuyen ... ms` cùng video; 180 bước x 10 ms là khoảng 1,8 giây theo lệnh, đo thời gian cơ khí riêng |
| Lưu nhiều bản, cắt mạng rồi kết nối lại | Mỗi lượt chỉ gửi một mã sự kiện; bản lỗi vẫn còn LittleFS; SYNC không hoàn tất sau bản đầu |
| Có bản cũ rồi quét lượt mới | Xác nhận bản cũ không mở cửa; v11 chỉ mở một lần cho lượt mới đã lưu, còn hạn, theo xác nhận server hoặc quyết định AS608 ngoại tuyến |
| Đã đồng bộ NTP rồi quét hai lượt khi mất mạng, giữ nguồn ESP (nhấc ngón tay, chờ cửa đóng giữa hai lượt) | Hai bản được lưu; mỗi lượt đúng mẫu mở cửa một lần; SYNC chạy nền không khóa cảm biến |
| Bật nguồn/reset khi chưa có Wi-Fi và chưa đồng bộ NTP | Đúng mẫu chỉ mở cửa, giữ 5 giây và báo `CHUA LUU CONG`; không tăng hàng đợi hoặc tạo công bằng giờ kết nối lại |
| Hàng đợi rỗng nhưng lỗi gửi kết quả lệnh SYNC | LCD không báo còn công chờ đồng bộ; Serial phân biệt chờ gửi kết quả lệnh |
| Retry kéo dài trên 15 giây, hoặc khởi động lại có hàng đợi | Dữ liệu đồng bộ nhưng không mở cửa |
| SYNC còn bản chưa gửi quá 180 giây | Lệnh báo thất bại do hết hạn; hàng đợi giữ các bản chưa gửi và tiếp tục retry nền |

V12 chỉ ghi công khi đã có giờ NTP hợp lệ. Khởi động hoàn toàn offline chưa có giờ chỉ mở cửa theo mẫu và báo `CHUA LUU CONG`; không lưu lượt này để gửi lại. Các bài thử ở bảng trên vẫn cần log và đo trực tiếp trên phần cứng.

## Chuẩn bị ESP32-CAM và gateway laptop — 10/10/2026

Chưa có ESP32-CAM thực tế; lần chuẩn bị này không nạp hoặc truy cập cổng COM. Đã tạo firmware `firmware/esp32_cam/` cho AI Thinker/OV2640: JPEG `/capture`, `/health`, token qua `X-Camera-Token`, thử lại Wi-Fi/camera, bỏ framebuffer cũ để lấy ảnh mới. Header `X-Frame-Id` kết hợp nonce mỗi lần khởi động và bộ đếm; ảnh có `Cache-Control: no-store`, thời điểm UTC khi NTP sẵn sàng. Firmware không nhận diện trên board và không đưa ảnh lên Firestore.

ESP8266 có đường gửi laptop trong `35_attendance_gateway.ino`. `ATTENDANCE_GATEWAY_MODE` mặc định **0** khi thiếu cấu hình trong `secrets.h`, giữ luồng vân tay hiện tại. Đặt thành **1** chỉ khi cả điểm chấm chuyển sang dịch vụ laptop: endpoint `http://<IP-laptop>:8765/v1/fingerprint`, token `X-Device-Token`, cùng `stationId` trong cấu hình laptop. Lệnh thiết bị và heartbeat vẫn dùng Firebase; riêng dữ liệu chấm công trong chế độ gateway không fallback về ghi Firestore trực tiếp. Mất Internet gây backoff TLS không chặn lần gửi gateway còn liên lạc được qua LAN.

Gateway bảo toàn LittleFS, bộ đếm, mã sự kiện và giờ quét gốc. Chỉ biên nhận JSON đúng `sourceEventId` cùng `ACCEPTED_SYNCED`/`DUPLICATE_SYNCED` và `attendanceId` mới xác nhận đồng bộ. `DUPLICATE_SYNCED` không phát còi thành công hoặc mở cửa. `QUEUED`, lỗi body/ID hoặc mất phản hồi giữ bản gốc cho retry; từ chối nghiệp vụ `REJECTED` phải có biên nhận đúng mã sự kiện. Mã HTTP 2xx/409 riêng lẻ không đủ để xóa hàng đợi gateway. Payload cũ `offlineScan` và Firestore có thể chuyển sang schema nguồn mà không đổi giờ/mã, nhưng trước khi đổi chế độ nên xả hết hàng đợi bằng firmware cũ để tránh trường hợp bản direct đã được ghi mà mất ACK. Không format/xóa LittleFS để đổi chế độ.

Chính sách cửa ngoại tuyến AS608 đã chọn được giữ riêng: mất kết nối laptop có thể mở một lần cho lượt hiện tại đã lưu, còn hạn; ACK muộn và dữ liệu khôi phục không mở lại. Laptop trả `QUEUED` chưa được coi là đã đồng bộ và không kích hoạt mở cửa theo ACK. Chưa có giờ NTP vẫn là `ACCESS_ONLY`, không tạo lượt công bằng giờ gửi lại.

Chạy `node --test firmware/test/*.test.cjs`: **114/114 đạt**, không thất bại hoặc bỏ qua (99 bài hồi quy hiện có + 10 bài gateway + 5 bài camera). Các bài mới chạy quyết định của mã firmware với HTTP, JSON, thời gian và thiết bị giả: auth camera, sở hữu/độ mới framebuffer, mất ảnh, đổi ID qua khởi động, gateway không fallback, chuyển payload cũ, giữ timestamp, body ACK sai, QUEUED/retry, duplicate không mở cửa, lỗi cập nhật LittleFS, từ chối và khôi phục sau reset.

Sau khi tìm được Arduino CLI tại `D:/IoT/Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe`, đã biên dịch ESP8266 core **3.1.2**, FQBN `esp8266:esp8266:nodemcuv2:eesz=4M2M,wipe=none` cho cả hai chế độ. Dùng bản sao sketch trong `.gradle/face-firmware-validation*` với `secrets.h.example`/token giả dùng riêng cho biên dịch; không đọc, in hoặc sửa bí mật thiết bị thực tế, không nạp board. Lần biên dịch đầu phát hiện Arduino sinh prototype `GatewayReceipt` trước include; đã bổ sung khai báo tường minh trong main sketch rồi biên dịch lại thành công.

| Chế độ kiểm tra | RAM | IRAM | Flash | Kết quả |
| --- | --- | --- | --- | --- |
| Gateway OFF, cấu hình mẫu | 34.880 / 80.192 byte (43%) | 64.103 / 65.536 byte (97%) | 464.976 / 1.048.576 byte (44%) | Đạt |
| Gateway ON, token giả phục vụ biên dịch | 34.864 / 80.192 byte (43%) | 64.103 / 65.536 byte (97%) | 464.048 / 1.048.576 byte (44%) | Đạt |

Thư viện sử dụng: ESP8266WiFi 1.0, ESP8266HTTPClient 1.2, Adafruit Fingerprint 2.1.4, EspSoftwareSerial 8.0.1, ArduinoJson 7.4.3, Wire 1.0, LiquidCrystal_I2C 2.0.0, Servo 1.0.2, LittleFS 0.1.0. Công cụ vẫn cảnh báo tương thích kiến trúc LiquidCrystal_I2C như bản cũ; cần kiểm tra LCD thực tế. Đây là số bộ nhớ của cấu hình mẫu, không thay cho đo bộ nhớ runtime khi TLS/mất mạng. Output nằm trong `.gradle/face-gateway-build-off` và `.gradle/face-gateway-build-on`.

Đã cài ESP32 core **3.3.12** từ [index stable chính thức Espressif](https://espressif.github.io/arduino-esp32/package_esp32_index.json) vào `.gradle/esp32-arduino`; chỉ tải toolchain/thư viện cho classic ESP32. Các archive giữ nguyên URL, phiên bản và checksum SHA-256 của index; Arduino CLI kiểm checksum khi cài, đồng thời đã đối chiếu SHA-256 của compiler, core, ESP32 libs và esptool đã tải. SDK Arduino toàn máy không thay đổi. Script `firmware/esp32_cam/compile.ps1` tái lập việc cài bằng `-InstallCore`, các lần sau dùng SDK đã có. Trên Windows, script ánh xạ tạm một ổ đĩa trống vào đúng workspace để GCC không vượt giới hạn đường dẫn, rồi gỡ ánh xạ khi kết thúc, kể cả khi biên dịch lỗi.

Firmware camera đã **biên dịch thành công** với FQBN `esp32:esp32:esp32cam`: cấu hình AI Thinker của core bật PSRAM, flash 4 MB, phân vùng ứng dụng 3 MB. Flash **1.050.554 / 3.145.728 byte (33%)**, RAM tĩnh **60.284 / 327.680 byte (18%)**. Bản sao sketch tại `.gradle/esp32-camera-validation/esp32_cam` dùng Wi-Fi/token giả từ `secrets.h.example`; output ở `.gradle/esp32-camera-build`, không dùng để nạp trực tiếp. Không đọc/sửa `secrets.h` thật, không truy cập COM hay nạp board.

**Chưa thử trên phần cứng camera.** Kết quả biên dịch và 5 bài kiểm thử host của camera chưa xác nhận OV2640, PSRAM, nguồn, chất lượng ảnh hoặc Wi-Fi thực tế. Khi có camera, giữ bản firmware vân tay đã chạy, cấu hình camera và kiểm tra camera/nguồn/LAN/đăng ký 3–4 người theo hướng dẫn `firmware/esp32_cam/README.md` và dịch vụ laptop.
