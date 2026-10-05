# Kiểm chứng firmware ESP8266

Ngày cập nhật: 05/10/2026. Firmware hiện tại là `spark-anonymous-v14-command-status`, giữ sửa kết quả lệnh và hành vi ngoại tuyến của v12. Phép đo cửa trực tiếp ngày 04/10 thuộc bản v9; log người dùng cung cấp sau đó thuộc v10, v11 và v14. Người dùng đã xác nhận cửa hoạt động trở lại; chưa coi một chu kỳ cửa là nghiệm thu toàn bộ thiết bị hoặc luồng mạng.

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
