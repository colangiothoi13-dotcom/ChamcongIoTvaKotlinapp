# Chấm công khuôn mặt và vân tay qua laptop

Dịch vụ dành cho một điểm chấm công và 3–4 người demo. Camera ESP32-S3-WROOM-1 N16R8 hiện có (hoặc AI Thinker ESP32-CAM cũ) cung cấp ảnh JPEG; laptop dùng YuNet và SFace nhận diện, đồng thời nhận kết quả vân tay từ ESP8266. Hai nguồn đi qua một bước chống trùng trước khi tạo lượt `SCAN/PENDING`; Android tính vào/ra theo lịch và ca hiện có. Thay board camera không đổi giao thức ảnh JPEG. Bản tích hợp mới có lệnh `station` để nhận yêu cầu đăng ký khuôn mặt từ app Admin.

**Chưa có camera vẫn chạy được phần mô phỏng chống trùng.** Nhận diện ảnh thật cần cài phụ thuộc, tải mô hình và đăng ký ảnh mẫu. Mô phỏng không có kết quả nhận diện thật và không ghi Firebase.

## Mở station và đăng ký khuôn mặt trên app hoặc web

Sau khi cấu hình camera/Python bên dưới, nhấp đúp **`services/face_attendance/mo-tram-khuon-mat.cmd`**. Giữ cửa sổ mở khi sử dụng. Launcher chạy một tiến trình gồm camera nhận diện, API vân tay và bộ nhận lệnh đăng ký từ app hoặc web Admin; không cần chạy đồng thời `serve` và `poll`. Ctrl+C dừng an toàn. Có thể chạy bằng PowerShell tại thư mục gốc:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 station
```

`mode: "demo"` vẫn chỉ dùng registry/lượt chấm trên laptop, không đọc hoặc ghi Firebase và không nhận lệnh đăng ký từ app. Màn hình camera trên web hoạt động riêng, không tự đổi cấu hình hoặc tự ghi công.

Để dùng **Admin → Nhân viên → Đăng ký khuôn mặt** trên APK mới hoặc web Admin:

1. Trong `config.json`, dùng `mode: "firebase"`, `projectId` đúng dự án của app, `stationId: "GATE-01"`, `faceDeviceId: "CAM-01"` và URL/token camera đúng. Token camera, token ESP8266 và khóa Admin SDK chỉ lưu trên laptop; không gửi chúng qua chat hoặc đưa vào app/web.
2. Trong Firebase Console của đúng dự án, mở bánh răng **Project settings → Service accounts → Generate New Private Key → Generate Key** để tải file JSON. Đây là các bước trong [hướng dẫn Admin SDK chính thức](https://firebase.google.com/docs/admin/setup#initialize_the_sdk_in_non-google_environments). Để dùng launcher bằng nhấp đúp, tạo thư mục `services/face_attendance/private/`, chép file vào đó và đổi tên thành **`firebase-admin.json`**. Thư mục này được Git bỏ qua; không gửi khóa qua chat hoặc chia sẻ cùng APK. Launcher chỉ chọn đúng file này khi biến `GOOGLE_APPLICATION_CREDENTIALS` chưa được đặt, không tự tìm trong Downloads và không tự tải khóa. Tài khoản cần quyền đọc/ghi Firestore của dự án.
3. Cập nhật Firestore rules của repository và cài APK có màn hình đăng ký mặt, hoặc tải lại web bằng Ctrl+F5. Chỉ triển khai `firestore:rules`; dịch vụ laptop không cần deploy Functions, Storage hoặc đổi gói Spark.
4. Dừng các cửa sổ `serve`/`poll` cũ, mở launcher station. Chờ app/web thấy **station online/camera sẵn sàng**, rồi chọn đúng nhân viên còn hoạt động và bấm đăng ký. Chỉ một người đứng trước camera, nhìn thẳng rồi xoay nhẹ mặt; ảnh phải đứng, đủ sáng và không bị cắt mặt. Web cần đăng nhập Admin thật, nút bị khóa trong chế độ dữ liệu mẫu.
5. Web mới hiển thị tiến độ **0/6 → 6/6**; yêu cầu 10 ảnh cũ và luồng tạo từ Android giữ **0/10 → 10/10**, sau đó **Đang lưu → Đã đăng ký**. Khi chụp mẫu, station tạm dừng chấm công bằng khuôn mặt; API vân tay vẫn hoạt động. Sau khi hoàn tất, rời khung hình khoảng ba giây trước lượt chấm đầu tiên.

Yêu cầu mới từ web dùng `captureMode: MANUAL`: Admin bấm **Chụp ảnh** cho từng mẫu. Laptop chờ thay đổi document lệnh, không liên tục đọc Firebase trong lúc chờ. Mỗi lần bấm lấy đúng một khung mới; ảnh không đạt hoặc lỗi camera cũng xác nhận lượt bấm nhưng không tăng số ảnh đã lưu. Web hiển thị lý do để chụp lại, không gửi thêm lượt trong lúc ảnh trước đang được xử lý. Tối đa 100 lần thử và hết hạn sau năm phút chờ không bấm. Yêu cầu Android cũ không có các trường này vẫn tự động thu 10 ảnh. Chụp thủ công cần Firestore Rules mới và mở lại dịch vụ laptop sau cập nhật.

Yêu cầu đăng ký mới từ web dùng `captureMode: MANUAL`, `sampleCount: 6`, `capturePlan: FRONT_LEFT_RIGHT_V1`: 2 ảnh nhìn thẳng, 2 quay nhẹ sang trái của nhân viên và 2 quay nhẹ sang phải. Chỉ ảnh đạt mới tăng tiến độ và đổi hướng; ảnh lỗi ở bước nào thì chụp lại bước đó. Đây là hướng dẫn để Admin xác nhận góc mặt trên hình trực tiếp, chưa phải phép đo góc tự động. Worker dùng tổng trên lệnh cho ACK, tiến độ, hoàn tất và metadata hồ sơ; ngưỡng chất lượng/đối chiếu giữ nguyên. Các lệnh cũ không có kế hoạch sáu ảnh vẫn dùng 10 ảnh, kể cả luồng tạo yêu cầu từ Android hiện tại.

Để sửa ảnh lộn đầu mà không nạp lại firmware, cấu hình `camera.rotation: 180` rồi mở lại dịch vụ. Chỉ chấp nhận số nguyên `0`, `90`, `180`, `270`; thiếu trường dùng `0`. `station`, `poll` và `capture` cùng xoay pixel trước kiểm tra mặt, lưu JPEG và nhận diện. Proxy web trả cùng chiều ảnh bằng metadata; nút xoay trên trình duyệt chỉ đổi góc xem thêm, không sửa ảnh mẫu.

Web có phần **Ảnh vừa chụp**: đúng khung ảnh của lần bấm được giữ tạm trong `CapturePreviewStore` trên RAM, một ảnh JPEG tối đa 5 MB và tự hết hạn sau 300 giây. `GET /v1/enrollment-preview/{requestId}/{sequence}` yêu cầu token Admin và chỉ trả đúng yêu cầu/lượt đang giữ. Node localhost gọi endpoint này bằng token riêng trên máy chủ; trình duyệt không nhận token. Ảnh này đã xoay trước kiểm tra, nên web không xoay lại. Lỗi camera, hủy/đăng ký thất bại hoặc dừng trạm xóa ảnh tạm; không cung cấp API duyệt thư mục ảnh hoặc vector, không ghi ảnh xem vào Firestore.

`low_quality` vẫn giữ tương thích, nhưng metadata `captureLastQualityIssue` phân biệt mặt bị cắt, mặt quá nhỏ, ảnh mờ và độ rõ chưa đạt. Mặt bị cắt được hướng dẫn **lùi lại và đưa toàn bộ mặt vào giữa ảnh**. Các ngưỡng kiểm tra chất lượng và đối chiếu khuôn mặt giữ nguyên.

Nếu đã quản lý khóa ở vị trí riêng, có thể đặt `GOOGLE_APPLICATION_CREDENTIALS` thay cho thư mục `private`. Biến chỉ đặt trong một cửa sổ PowerShell thì phải mở station **từ chính cửa sổ đó** bằng lệnh trên; nhấp đúp trong Explorer không nhận biến của cửa sổ PowerShell. Có khóa không tự chuyển `mode` sang Firebase: vẫn cần thực hiện bước 1.

App có thể yêu cầu hủy trong lúc chờ/chụp; worker xác nhận `CANCELLED`. Khi đã **Đang lưu** (`FINALIZING`), việc lưu phải hoàn tất trước thao tác tiếp theo. Đăng ký lại tự tăng version lớn hơn cả bản local và Firebase. **Xóa mẫu mặt** loại bỏ vector local và vô hiệu `faceProfiles`; ảnh mẫu local được giữ trong `data/photos/<employeeId>/` để Admin tự quản lý.

Mẫu luôn gắn với ID hồ sơ nhân viên hiện có; không tạo hồ sơ `demo-01` trên Firebase. Mỗi nhân viên và mỗi station chỉ có một yêu cầu đang xử lý. Laptop ngừng chạy thì app báo offline; station chỉ nhận yêu cầu thuộc đúng phiên đang chạy, tối đa năm phút kể từ khi gửi. Yêu cầu đang chụp/lưu bị gián đoạn không tự tiếp tục chụp khi mở lại laptop; Admin phải yêu cầu lại đúng người.

Ảnh/vector vẫn lưu trên laptop. Firestore chỉ chứa metadata `faceProfiles`, heartbeat `faceStations` (khoảng 30 giây), lệnh `faceEnrollmentCommands` và khóa `faceEnrollmentLocks`. Worker theo dõi một document station, không liên tục truy vấn rỗng; mỗi ảnh hợp lệ cập nhật tiến độ một lần. Không xóa thư mục dữ liệu/gallery khi đổi chế độ vì đây là hồ sơ nhận diện và hàng đợi chưa đồng bộ.

## Chạy mô phỏng ngay

Trong workspace đã chuẩn bị, có thể chạy từ thư mục gốc bằng PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 demo
```

Script dùng môi trường `.venv`, Python portable của workspace nếu có, hoặc Python đã cài. Nó không tự cài phần mềm và không bật gateway trên thiết bị.

Trong workspace này đã có Python portable, các thư viện và hai mô hình trong thư mục được Git bỏ qua. Cấu hình cục bộ đã tạo ở chế độ `demo`; chưa có hồ sơ mặt của nhân viên. Có thể dùng cùng script cho `doctor`, `capture`, `enroll`, `recognize`, `serve` và `poll`, ví dụ từ thư mục gốc:

```powershell
# Đặt ảnh vào services/face_attendance/data/photos/ trước khi đăng ký.
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 enroll --employee-id demo-01 --version 1 --images data/photos/an-1.jpg data/photos/an-2.jpg data/photos/an-3.jpg
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 recognize --image data/photos/an-test.jpg
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 poll --webcam 0
```

Các đường dẫn ảnh của script tính từ `services/face_attendance/`. Máy mới hoặc bản clone khác cần cài môi trường theo hướng dẫn dưới đây; Python portable và mô hình không nằm trong Git.

Nếu dùng file cấu hình riêng, truyền `-Config` cho script; đường dẫn file cấu hình tính từ thư mục đang mở PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 doctor -Config services/face_attendance/config.json
```

Dùng Python 3.12 trên Windows. Mở PowerShell trong thư mục này:

```powershell
python -m face_attendance demo
```

Lệnh mô phỏng bốn yêu cầu: người 1 chấm vân tay, người 1 chấm mặt, gửi lại lượt vân tay cũ, người 2 chấm mặt. Kết quả cuối phải có `attendanceCount: 2`; lượt mặt của người 1 liên kết với lượt vân tay đã có. Không cần camera, mô hình, token hoặc Firebase.

Mã `ACCEPTED_DEMO` và `DUPLICATE_DEMO` chỉ là kết quả mô phỏng. ESP8266 cố ý không xem chúng là xác nhận đã đồng bộ; không bật gateway của board với server ở chế độ `demo`.

## Cài môi trường nhận diện

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m face_attendance init
.\.venv\Scripts\python.exe download_models.py
```

`init` tạo `config.json` với token ngẫu nhiên riêng cho admin, board vân tay và camera, cùng bản sao registry demo. Lệnh không ghi đè cấu hình đã có. `config.json`, registry cá nhân, mô hình, gallery, hàng đợi và môi trường Python được bỏ qua bởi Git.

`download_models.py` chỉ tải hai mô hình chính thức từ OpenCV Zoo khi bạn chạy lệnh, kiểm tra kích thước/SHA256 và lưu vào `data/models`. Không tải mô hình trong mỗi lượt chấm. [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet), [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface)

## Đăng ký và thử ảnh khi chưa có ESP32-CAM

Chuẩn bị ít nhất ba ảnh rõ mặt cho mỗi người; nên lấy khoảng 10–15 ảnh ở các góc/ánh sáng demo. Đặt ảnh trong `data/photos/`. Registry mẫu có mã `demo-01` đến `demo-04`; các mã này chỉ dùng cục bộ.

Có webcam laptop thì thu ảnh trực tiếp từ thư mục gốc:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 doctor
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 capture --employee-id demo-01 --count 10 --interval 1 --webcam 0
```

Khi chụp, mỗi lần chỉ một người trong khung hình; đổi nhẹ góc nhìn và xem các ảnh đã lưu trong `services/face_attendance/data/photos/demo-01/`. Lệnh bỏ qua ảnh không có mặt, nhiều mặt, mặt nhỏ/mờ hoặc lỗi lấy ảnh; tối đa thử `count × 10` lần mặc định, có thể đặt `--max-attempts`. Ctrl+C dừng và trả các đường dẫn đã lưu. Mỗi ảnh có tên riêng, giữ nguyên ảnh cũ. `capture` không tự đăng ký, không đọc gallery cũ và không ghi công hoặc kết nối Firebase, kể cả khi `mode: firebase`.

Đăng ký các ảnh đã kiểm tra từ thư mục gốc bằng PowerShell:

```powershell
$taskPhotos = Get-ChildItem services/face_attendance/data/photos/demo-01/*.jpg | Select-Object -ExpandProperty FullName
& services/face_attendance/run.ps1 enroll --employee-id demo-01 --version 1 --images $taskPhotos
```

Chọn ảnh của một lần thu nếu thư mục chứa nhiều lần đăng ký. Giữ một số ảnh riêng để thử nhận diện sau đăng ký.

```powershell
.\.venv\Scripts\python.exe -m face_attendance enroll --employee-id demo-01 --version 1 --images data/photos/an-1.jpg data/photos/an-2.jpg data/photos/an-3.jpg
.\.venv\Scripts\python.exe -m face_attendance recognize --image data/photos/an-test.jpg
```

Lặp đăng ký cho những người còn lại. Ảnh thử phải khác ảnh đăng ký. `recognize` chỉ in kết quả và **không tạo lượt chấm công**, kể cả cấu hình đang ở chế độ Firebase. Độ tương đồng là điểm đối chiếu, không phải phần trăm chắc chắn.

Khi có webcam laptop, có thể thử luồng camera trước:

```powershell
.\.venv\Scripts\python.exe -m face_attendance poll --webcam 0
```

Trong `mode: demo`, các lượt từ webcam chỉ vào SQLite demo. Rời khung hình trước khi bắt đầu để hệ thống thấy các ảnh trống mới. Sau khi nhận một người, hệ thống chờ người đó rời khung; không chấm liên tục theo từng ảnh. Lỗi ảnh, mất mạng, ảnh cũ và nhiều mặt không được coi là khung trống.

Ngưỡng ban đầu trong `config.json`: cosine `0.5`, khoảng cách với ứng viên thứ hai `0.1`, kích thước mặt tối thiểu `80` pixel, độ nét Laplacian `50`, ba frame ổn định và ba frame trống. Các giá trị cần hiệu chuẩn bằng ảnh/camera thật. Bản này chưa có cơ chế chống ảnh in hoặc ảnh trên màn hình.

Đăng ký lại cần tăng `version`; đổi mô hình cần đăng ký lại. Dừng rồi khởi động lại `poll` sau khi sửa gallery hoặc registry demo. Nếu gallery đã lưu nhưng cập nhật metadata lỗi mạng, đăng ký lại với phiên bản lớn hơn cả phiên bản local và Firebase; hệ thống từ chối lượt có phiên bản không khớp trong thời gian đó.

## Kết nối camera ESP32-S3 N16R8 hoặc ESP32-CAM

1. Dùng firmware và [hướng dẫn nạp camera](../../firmware/esp32_cam/README.md). Board đã mua là ESP32-S3-WROOM-1 N16R8 hai Type-C: chọn **ESP32S3 Dev Module**, flash **QIO 16MB**, **OPI PSRAM**; cổng UART dùng **USB CDC On Boot Disabled**. Không chọn AI Thinker hoặc dùng đế Downloader cũ cho board này. Profile S3 được chọn tự động theo chip; pinout hiện đã lên ảnh trên board OV5640 của người dùng.
2. Điền Wi-Fi và token camera trong `secrets.h`. Token này phải khớp `camera.token` trong cấu hình laptop.
3. Điền địa chỉ `http://IP_CAMERA/capture` vào `camera.url` rồi chạy `python -m face_attendance poll` bằng môi trường đã cài.

Sản phẩm ghi OV2640 nhưng cảm biến thật đã được firmware dò là **OV5640, PID 0x5640**. Ngày 11/10/2026, Serial báo flash 16MB, PSRAM 8MB; laptop đã nhận `/health` HTTP 200 và JPEG **640×480** qua Wi-Fi từ board người dùng tại `192.168.0.110`. Sau sự cố mất điện/Wi-Fi, cần chờ mạng hoạt động lại và kiểm tra IP từ Serial; kết quả đã kiểm chứng trước đó không có nghĩa camera đang online. IP có thể thay đổi sau khi khởi động lại. Chưa đo độ chính xác nhận diện với 3–4 nhân viên hoặc độ ổn định dài hạn. Các nguồn thông số module/pinout/USB nằm trong hướng dẫn nạp camera.

Từ thư mục gốc, kiểm tra kết nối và thu ảnh với ESP32-CAM bằng các lệnh sau; lần này bỏ `--webcam`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 doctor --camera
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/run.ps1 capture --employee-id demo-01 --count 10 --interval 1
```

`doctor` kiểm tra cấu hình, token, thư viện, SHA256 hai mô hình, gallery và khả năng ghi SQLite bằng file thử riêng. `doctor --camera` kiểm tra thêm `/health` bằng token camera; không kết nối Firebase hoặc tạo lượt. `ok: true` có thể kèm cảnh báo chưa có hồ sơ hoặc chưa kiểm tra camera: xem `readyForRecognition` và `cameraReady` để biết phần còn cần chuẩn bị. Token và nội dung ảnh/đặc trưng không xuất hiện trong báo cáo.

Laptop yêu cầu `X-Frame-Id` mới trên từng ảnh và gửi `X-Camera-Token`. Không sử dụng ảnh cache hay URL chuyển hướng. Thời điểm của sự kiện mặt là UTC lúc laptop nhận ảnh mới; thời điểm này được giữ nguyên khi gửi lại, không thay bằng giờ đồng bộ. Đồng hồ laptop cần đúng giờ.

## Chuyển sang Firebase

Chỉ thực hiện bước này khi nhận diện riêng đã ổn và bạn sẵn sàng thử trên dữ liệu demo. Dịch vụ dùng Firebase Admin SDK; thông tin xác thực Admin chỉ ở laptop, không đưa vào firmware hoặc Git. Admin SDK bỏ qua Firestore Rules, nên dịch vụ kiểm tra nhân viên, liên kết nguồn và phiên bản hồ sơ trong giao dịch ghi lượt. [Ranh giới thư viện server và Firestore Rules](https://firebase.google.com/docs/firestore/security/rules-conditions)

1. Trong cấu hình cá nhân, đặt `mode` thành `firebase`, nhập đúng `projectId`, `stationId`, `fingerprintDeviceId` và `faceDeviceId`. Chọn gallery riêng cho project thật, ví dụ `recognition.gallery: data/gallery-firebase.json`, để không trộn hồ sơ `demo-01` với ID nhân viên thật của cùng một người. Giữ gallery demo để có thể quay lại thử cục bộ.
2. Đặt đường dẫn thông tin xác thực riêng bằng `GOOGLE_APPLICATION_CREDENTIALS`, nằm ngoài thư mục mã nguồn được theo dõi. Có thể dùng Application Default Credentials đã cấu hình trên laptop.
3. Đăng ký mặt với **ID tài liệu nhân viên thật** từ Firestore. Lệnh `enroll` kiểm tra nhân viên đang hoạt động và lưu metadata `faceProfiles/{employeeId}`; đặc trưng/ảnh vẫn chỉ ở laptop. Không dùng các ID `demo-01` mặc định cho Firebase thật.
4. Cho laptop lắng nghe IP trong LAN bằng `host: 0.0.0.0`, cổng mặc định `8765`; cho phép đúng ứng dụng/cổng qua Windows Firewall nếu mạng demo cần. Chạy `serve` trong một PowerShell và `poll` trong PowerShell khác.

```powershell
# Thay bằng đường dẫn riêng trên laptop; không đặt file này vào Git.
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\private\firebase-attendance.json'
.\.venv\Scripts\python.exe -m face_attendance serve
# Cửa sổ PowerShell thứ hai dùng cùng môi trường và cấu hình:
.\.venv\Scripts\python.exe -m face_attendance poll
```

`serve` nhận vân tay và tự gửi lại hàng đợi mỗi 5 giây; `poll` lấy ảnh và lưu/sync sự kiện mặt. Hai tiến trình dùng chung hàng đợi SQLite khi cùng cấu hình; hàng đợi được tách theo chế độ, project Firebase, điểm chấm và thiết bị vân tay. Đổi cấu hình tạo hàng đợi riêng, giữ file cũ để đồng bộ bằng cấu hình cũ. Giao dịch Firestore bảo vệ khi xử lý đồng thời. `python -m face_attendance flush` thử đồng bộ ngay.

Lượt chấm có thể chờ mạng hoặc chờ thử lại sau xung đột giao dịch trong SQLite. Thiết bị không nhận `ACCEPTED_SYNCED` trước khi giao dịch đã hoàn tất. Khi còn LAN nhưng mất Internet, báo chờ đồng bộ; khi laptop dừng thì khuôn mặt chưa được xử lý.

## Chuyển đường gửi ESP8266

Gateway mặc định **OFF**, để firmware vân tay đang dùng tiếp tục hoạt động trước khi camera sẵn sàng.

Trước khi bật: dừng nhánh mặt, đồng bộ hết outbox cũ qua đường trực tiếp và xác nhận không còn lượt đang gửi, sau đó chờ hơn 180 giây kể từ lượt cuối. Không xóa LittleFS. Lượt cũ đã ghi trực tiếp nhưng mất ACK chưa có biên nhận gateway; chuyển ngay có thể tạo hai lượt gốc. Giữ toàn bộ điểm chấm ở một chế độ trong lúc thử.

Điền cấu hình mẫu tại [secrets.h.example](../../firmware/esp8266_fingerprint/secrets.h.example): bật gateway, URL `http://IP_LAPTOP:8765/v1/fingerprint`, token khớp `deviceToken`, điểm chấm khớp `stationId`. Tham khảo các tên macro đúng trong file mẫu. Khi gateway ON, board giữ hàng đợi nếu không kết nối được laptop và không tự gửi trực tiếp Firestore.

## Giao thức và chống trùng

API `POST /v1/fingerprint` dùng `X-Device-Token` và dữ liệu:

```json
{"sourceEventId":"GATE-01-chip-123","deviceId":"GATE-01","stationId":"GATE-01","templateId":1,"confidence":100,"capturedAt":"2026-10-10T01:00:00Z"}
```

`POST /v1/face` dùng `X-Admin-Token`, dành cho bộ nhận diện tin cậy trên laptop. Cần các trường chung tương tự, cùng `employeeId`, `profileVersion`, `faceSimilarity`. Endpoint không nhận ảnh hoặc đặc trưng mặt. `/v1/flush` dùng token Admin; `/health` cho biết chế độ và số lượt đang chờ.

Phản hồi gắn `sourceEventId` đúng request:

| Trạng thái | Ý nghĩa |
| --- | --- |
| `ACCEPTED_SYNCED` | Một lượt mới đã ghi thành công trên Firebase |
| `DUPLICATE_SYNCED` | Liên kết với lượt đã có; không tạo lượt mới hoặc phát lại mở cửa |
| `QUEUED` | Giữ hàng đợi để thử lại; chưa xác nhận đồng bộ |
| `REJECTED` | Nguồn/hồ sơ/thời điểm không hợp lệ; có lý do để kiểm tra |
| `ACCEPTED_DEMO`, `DUPLICATE_DEMO` | Chỉ dữ liệu mô phỏng cục bộ |

Không dùng HTTP 2xx/409 đơn thuần làm xác nhận. Cùng mã sự kiện gửi lại trả kết quả cũ; cùng mã với nội dung khác bị từ chối. Hai sự kiện khác mã của cùng nhân viên/điểm chấm trong 180 giây được ghép vào một lượt. Nhân viên khác không bị khóa; lượt trùng không kéo dài cửa sổ và không khóa cả ngày. Lịch sử theo thời điểm nguồn xử lý được dữ liệu đến muộn, ngay cả sau khi đã có một lượt ra sau đó.

Các bộ sưu tập mới: `attendanceReceipts` và `attendanceGates` là dữ liệu riêng của dịch vụ; `faceProfiles` chứa metadata, chỉ admin đọc bằng client. Bản ghi chấm công gốc không bị sửa để gộp phương thức. Quyền direct fingerprint cũ vẫn giữ để firmware mặc định OFF chạy được.

## Kiểm tra

Kiểm chứng bản tích hợp đăng ký ngày **11/10/2026: 101/101 Python tests đạt**, không bỏ qua test. Trong đó có **13 tests dùng Firebase Admin SDK thật trên Firestore Emulator loopback**: 6 tests đăng ký/cập nhật/xóa/hủy/rollback và đồng hồ station, cùng 7 tests giao dịch chấm công/chống trùng. Bộ offline cũng đạt; khi không có Emulator, 13 tests SDK được bỏ qua có thông báo. Không dùng khóa Admin hoặc dự án Firebase thật để chạy kiểm thử.

Cùng bản tích hợp này, **Android đạt 377/377 unit tests** và tạo APK debug; **Firestore Rules đạt 15/15 tests trên Emulator**, gồm 11 tests đăng ký khuôn mặt và 4 tests chấm công. Các phép thử phần mềm này chưa xác minh thao tác đăng ký của người dùng trên camera thật sau khi Wi-Fi hoạt động lại.

Kiểm chứng ngày 10/10/2026: **73/73 Python tests đạt**, gồm 7 test giao dịch bằng SDK thật trên Firebase Emulator. OpenCV thực đã tải hai mô hình, thu và lưu JPEG, đăng ký/reload gallery và nhận diện ảnh công khai, từ chối khung trống và ảnh hai mặt. Thử camera giả lập qua HTTP với 9 ảnh mới liên tiếp tạo một lượt mặt; tín hiệu vân tay được ghép vào cùng lượt trong SQLite demo. Script PowerShell đã thử đăng ký nhiều đường dẫn ảnh chứa dấu cách. Đây là kiểm tra luồng xử lý, chưa đo độ chính xác với 3–4 nhân viên.

Các mốc kiểm chứng bản chuẩn bị ngày 10/10: Android đạt **369/369 unit tests**, tạo APK debug; Firestore Rules đạt **4/4 tests**. Firmware đạt **114/114 host tests**; ESP8266 biên dịch được cả gateway OFF và ON với core 3.1.2. ESP32-CAM biên dịch với core Espressif 3.3.12. Ngày 11/10, bản S3 đã biên dịch cả UART và native USB CDC, bản classic vẫn biên dịch; **118/118 firmware tests đạt**, camera thật OV5640 đã lên JPEG như ghi ở trên. Xem [script compile](../../firmware/esp32_cam/compile.ps1) và [kiểm chứng firmware](../../firmware/VALIDATION.md). Kiểm thử đăng ký mới chỉ dùng dữ liệu giả lập và project demo của Emulator; không ghi dự án Firebase thật.

```powershell
python -m unittest discover -s tests -v
```

Core, HTTP, registry và presence/source tests dùng Python tiêu chuẩn. Integration tests dùng SDK thật chỉ chạy khi có `FIRESTORE_EMULATOR_HOST` trỏ đến loopback; mỗi test dùng project demo riêng. Không dùng thông tin xác thực Firebase thật để chạy bộ kiểm thử.

```powershell
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8184'
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
Remove-Item Env:FIRESTORE_EMULATOR_HOST
```

Sau khi có camera, cần kiểm tra nguồn, khởi động lại, Wi-Fi, ảnh thực tế của cả 3–4 người, camera nhìn liên tục, hai phương thức đồng thời, mất ACK và gửi lại. Kết quả test phần mềm không thay thế các bước kiểm tra phần cứng đó.
