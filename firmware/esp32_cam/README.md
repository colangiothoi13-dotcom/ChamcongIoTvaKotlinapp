# Camera chấm công: ESP32-S3-WROOM-1 N16R8

Board hiện có là **ESP32-S3-WROOM-1 N16R8 CAM, hai cổng USB Type-C**. Firmware tự chọn profile S3 N16R8 khi biên dịch cho ESP32-S3; vẫn hỗ trợ AI Thinker ESP32-CAM cũ khi biên dịch cho ESP32 thường. Board S3 có cổng nạp trên bo mạch, không dùng đế ESP32-CAM-MB/Downloader của AI Thinker.

Module N16R8 có **16 MB Quad SPI flash và 8 MB Octal SPI PSRAM**. Trong Arduino IDE dùng QIO cho flash, OPI cho PSRAM. [Datasheet Espressif, bảng 1-1](https://www.espressif.com/sites/default/files/documentation/esp32-s3-wroom-1_wroom-1u_datasheet_en.pdf)

Tên sản phẩm người dùng mua ghi OV2640, còn chữ trên cáp camera được báo là OV5640. Driver tự dò cảm biến; firmware ghi tên/PID vào Serial và `/health`. Cả OV2640 và OV5640 được driver hỗ trợ; chưa xác nhận cảm biến thực tế chỉ từ ảnh sản phẩm. [Driver Espressif](https://github.com/espressif/esp32-camera#supported-sensors)

**Đã thử ảnh trên board hiện có ngày 11/10/2026:** người dùng tự nạp, log và `/health` nhận OV5640, flash 16 MB, PSRAM 8 MB; laptop đã lấy và mở JPEG VGA. Chưa đăng ký nhân viên hoặc thử độ ổn định dài hạn. Ánh xạ chân bên dưới dựa trên họ board S3 N16R8 thông dụng và nguồn của tác giả thư viện; tên module không xác định sơ đồ cho mọi PCB. Nếu camera báo lỗi, đối chiếu sơ đồ hoặc tài liệu của nơi bán, thay vì tự đổi GPIO ngẫu nhiên.

## Nạp lần đầu bằng Arduino IDE

1. Giữ camera gắn sẵn trên board. Dùng **một cáp USB Type-C có truyền dữ liệu**, cắm laptop vào cổng có nhãn `UART`/`USB-UART` nếu có. Không cần cắm cả hai Type-C; ESP8266 dùng dây riêng. Nếu nhãn không rõ, xem Device Manager: USB-UART thường hiện tên CH340/CH343/CP210x; USB tích hợp thường hiện Espressif USB JTAG/serial. Không suy chức năng chỉ từ vị trí cổng trong ảnh.
2. Cài **esp32 by Espressif Systems**, phiên bản **3.3.12**, trong Arduino IDE rồi mở [esp32_cam.ino](esp32_cam.ino). Toolchain kiểm tra trong `.gradle` không tự cài board vào Arduino IDE.
3. Sao chép [secrets.h.example](secrets.h.example) thành `secrets.h` trong cùng thư mục sketch. Điền Wi-Fi 2,4 GHz; giữ `CAMERA_DEVICE_ID "CAM-01"`. Sao chép `camera.token` trong `services/face_attendance/config.json` vào `CAMERA_ACCESS_TOKEN`. Token phải dài 16–120 ký tự, khớp laptop; đây không phải API key Firebase. `secrets.h` được Git bỏ qua.
4. Chọn các mục sau trong **Tools** khi dùng cổng UART:

   | Mục | Giá trị |
   | --- | --- |
   | Board | **ESP32S3 Dev Module** |
   | Port | Cổng COM của board S3 đang cắm |
   | CPU Frequency | 240 MHz |
   | Flash Mode | **QIO 80MHz** |
   | Flash Size | **16MB (128Mb)** |
   | PSRAM | **OPI PSRAM** |
   | Partition Scheme | **16M Flash (3MB APP/9.9MB FATFS)** |
   | USB Mode | Hardware CDC and JTAG |
   | USB CDC On Boot | **Disabled** |
   | Upload Mode | UART0 / Hardware CDC |
   | Upload Speed | 115200 khi nạp lần đầu |

   **OPI PSRAM không có nghĩa flash là OPI.** Dùng cổng UART thì tắt USB CDC để `Serial` in ra đúng cổng. [Espressif hướng dẫn Flash/PSRAM và USB](https://docs.espressif.com/projects/arduino-esp32/en/latest/troubleshooting.html)

5. Bấm **Upload**. Nếu dừng ở `Connecting...`: giữ **BOOT**, bấm rồi thả **EN/RST**, sau đó thả BOOT khi công cụ bắt đầu nạp. Nạp xong bấm EN/RST để chạy lại. Đóng Serial Monitor trước khi nạp nếu cổng đang bị chiếm. [Chế độ nạp ESP32-S3](https://docs.espressif.com/projects/esptool/en/latest/esp32s3/advanced-topics/boot-mode-selection.html)
6. Mở Serial Monitor **115200**, bấm EN/RST. Các dòng cần kiểm tra: `Camera board: ESP32-S3 WROOM N16R8`, `Flash bytes: ...; PSRAM bytes: ...`, `Camera ready: OV5640 (PID 0x5640), JPEG VGA` hoặc cảm biến driver nhận được. Khi nối Wi-Fi sẽ có `Camera LAN address: http://...`. S3 yêu cầu PSRAM hoạt động; nếu không phát hiện PSRAM, sửa mục OPI PSRAM trước khi kiểm tra ảnh.

Có thể dùng cổng USB tích hợp thay UART: đặt **USB CDC On Boot: Enabled**, **USB Mode: Hardware CDC and JTAG**, **Upload Mode: UART0 / Hardware CDC**, rồi chọn lại COM của cổng này. Lần đầu có thể cần BOOT/EN và reset sau nạp. Không trộn cấu hình CDC với cổng UART khi tìm log. [USB CDC của Arduino ESP32](https://docs.espressif.com/projects/arduino-esp32/en/latest/tutorials/cdc_dfu_flash.html)

## Kết nối laptop và thu ảnh

Laptop và camera cần truy cập nhau trong cùng mạng. Giữ dịch vụ ở `mode: demo` để thử riêng trước khi chuyển vân tay sang gateway.

1. Sửa `camera.url` trong `services/face_attendance/config.json` thành `http://IP_CAMERA/capture`, dùng IP vừa thấy trong Serial.
2. Từ thư mục gốc dự án chạy:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 doctor --camera
   ```

   Xem `cameraReady: true`. Chưa đăng ký ảnh thì `readyForRecognition: false` là bình thường. Lệnh không kết nối Firebase hoặc tạo lượt công.

3. Thu ảnh cho người đầu tiên:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 capture --employee-id demo-01 --count 10 --interval 1
   ```

   Chỉ một người trong khung hình, đủ sáng và đổi nhẹ góc mặt. Xem ảnh tại `services/face_attendance/data/photos/demo-01/`. Lệnh chỉ lưu ảnh hợp lệ, chưa đăng ký hoặc chấm công. Lặp với `demo-02`, `demo-03`, `demo-04` cho 3–4 người.

4. Đăng ký ảnh và chạy thử theo [hướng dẫn dịch vụ laptop](../../services/face_attendance/README.md). Nếu ảnh lộn chiều, chỉnh theo ảnh thật rồi thu lại mẫu; chưa đặt mặc định lật/gương vì cách lắp camera có thể khác giữa PCB.

Khi chuyển Firestore thật, dùng ID nhân viên thật đã liên kết vân tay thay cho `demo-01`. ESP8266 nhận vân tay; laptop nhận diện mặt, chống trùng và gửi công lên Firestore. Thay board camera không đổi kiến trúc này, không cần nạp Python vào board. Xác minh lấy ảnh 10 phút và nối lại Wi-Fi trước khi bật gateway.

## Cấu hình chân camera

Profile S3 N16R8 dùng ánh xạ từ [nguồn `S3N16R8` của tác giả esp32cam](https://github.com/yoursunny/esp32cam/blob/main/src/esp32cam/pins.hpp). Ánh xạ cũng trùng phần camera của [CameraWebServer ESP32S3_EYE chính thức](https://github.com/espressif/arduino-esp32/blob/master/libraries/ESP32/examples/Camera/CameraWebServer/camera_pins.h); điều này không xác định PCB là board EYE.

| Tín hiệu | GPIO S3 N16R8 |
| --- | --- |
| D0, D1, D2, D3 | 11, 9, 8, 10 |
| D4, D5, D6, D7 | 12, 18, 17, 16 |
| XCLK, PCLK | 15, 13 |
| VSYNC, HREF | 6, 7 |
| SCCB SDA, SCL | 4, 5 |
| PWDN, RESET | -1, -1 — không điều khiển bằng GPIO |

Profile tự chọn theo chip; các tên trong [camera_board.h](camera_board.h) là `CAMERA_BOARD_S3_N16R8` và `CAMERA_BOARD_AI_THINKER`. S3 không điều khiển đèn flash trên GPIO4: chân này là **SDA camera**. AI Thinker cũ có ánh xạ riêng và đèn GPIO4. Không dùng profile AI Thinker để nạp S3.

## Giao thức và chẩn đoán

`GET /capture` trả JPEG; `GET /health` có `board`, `sensor`, `sensorPid`, `psramBytes`, `flashBytes`, `cameraError` và các cờ trạng thái. Cả hai yêu cầu header `X-Camera-Token`, không đặt token trong URL. Mở URL trực tiếp bằng trình duyệt có thể nhận **401**; dùng `doctor --camera`/`capture` để kiểm tra.

Ảnh có `Cache-Control: no-store`, `X-Frame-Id` mới theo nonce khởi động/bộ đếm và `X-Camera-Id`. Khi có NTP thêm `X-Frame-Captured-At` UTC; dịch vụ vẫn dùng lúc laptop nhận ảnh mới làm thời điểm sự kiện. Mỗi request bỏ framebuffer cũ trước khi lấy ảnh mới. Mất ảnh/lỗi 503 không được hiểu là người đã rời khung.

Mặc định VGA 640×480 với PSRAM. S3 không lùi xuống DRAM nếu PSRAM thiếu; AI Thinker cũ có QVGA dự phòng. Không dùng SD, nhận diện trên board hoặc tải ảnh lên Firebase. Wi-Fi/camera lỗi được thử lại. HTTP/token dùng trong mạng demo riêng.

| Hiện tượng | Kiểm tra tiếp |
| --- | --- |
| Không có COM | Cáp truyền dữ liệu, đúng cổng, tên chip USB trong Device Manager |
| Nạp được nhưng Serial trống | UART với CDC Disabled; USB tích hợp với CDC Enabled; COM và 115200 |
| PSRAM thiếu/khởi động lặp | ESP32S3 Dev Module, flash QIO 16MB, PSRAM OPI |
| `Camera init failed` | Cáp camera, PID/log; pinout đúng PCB từ nơi bán |
| Có IP nhưng không tới camera | `camera.url`, cùng mạng, token khớp, mạng cho thiết bị liên lạc |
| Ảnh rõ nhưng chưa nhận diện | Chụp/đăng ký ảnh mẫu và xem `readyForRecognition` |

## Kiểm tra biên dịch trong dự án

[compile.ps1](compile.ps1) ghim core **3.3.12** từ [index Espressif](https://espressif.github.io/arduino-esp32/package_esp32_index.json). Chạy tại thư mục gốc:

```powershell
# Lần đầu: tải phụ thuộc S3 và kiểm tra firmware.
.\firmware\esp32_cam\compile.ps1 -BoardProfile S3N16R8 -InstallCore
# Dùng lại toolchain đã tải.
.\firmware\esp32_cam\compile.ps1 -BoardProfile S3N16R8
# Chỉ dành cho AI Thinker cũ.
.\firmware\esp32_cam\compile.ps1 -BoardProfile AiThinker
```

`S3N16R8` là profile mặc định. Nếu đã có SDK chỉ dành cho ESP32 cũ, dùng `-BoardProfile S3N16R8 -InstallTools` để bổ sung công cụ S3; `-InstallCore` cũng có thể cài mới hoặc bổ sung. Thêm `-NativeUsbCdc` khi kiểm tra bản dành cho cổng USB tích hợp. CLI ở nơi khác thì thêm `-ArduinoCli 'C:/duong-dan/arduino-cli.exe'`. SDK/download/cache nằm trong `.gradle/esp32-arduino`; không thay SDK toàn máy. Script giữ URL/phiên bản/SHA-256 chính thức và chọn phụ thuộc theo chip; Windows ánh xạ tạm workspace vào ổ đĩa trống để tránh giới hạn đường dẫn GCC rồi tự gỡ.

Script biên dịch bản sao bằng Wi-Fi/token giả, **không đọc `secrets.h` thật, không nạp board**. Output kiểm tra không dùng để nạp trực tiếp; cấu hình `secrets.h` thật và Upload từ Arduino IDE. Kết quả ghi tại [VALIDATION.md](../VALIDATION.md); biên dịch thành công chưa xác nhận GPIO, cảm biến, ảnh, PSRAM, nguồn và Wi-Fi trên board đang có.
