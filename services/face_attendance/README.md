# Chấm công khuôn mặt và vân tay qua laptop

## Chặn khẩu trang và kính

Khi `accessories.enabled: true`, laptop kiểm tra **cả lúc thu ảnh/đăng ký lại lẫn trước mỗi lần nhận diện để chấm công**. Bộ kiểm tra dùng FaceAttribNet cho khẩu trang, kính cận/kính trong và kính râm. Theo cấu hình hiện tại `accessories.blockHat: false`, kiểm tra mũ được bỏ hoàn toàn: không nạp/chạy mô hình đầu hoặc mũ, không dùng bộ kiểm tra mũ để từ chối tóc và không yêu cầu phải thấy đỉnh đầu để kiểm tra mũ. Mặt vẫn cần nằm trọn trong ảnh. Không gửi ảnh cho dịch vụ AI bên ngoài. Vân tay tiếp tục hoạt động độc lập.

Trong bản này, `config.example.json` bật sẵn kiểm tra khẩu trang/kính và tắt kiểm tra mũ. File cấu hình cũ chưa có mục `accessories` giữ chế độ cũ; thêm nguyên mục từ file mẫu để bật. Chạy `download_models.py` bằng Python của dịch vụ để tải các mô hình đã ghim SHA256, rồi chạy `doctor` và mở lại `mo-tram-khuon-mat.cmd`. Không nạp lại ESP32-S3/ESP8266 hoặc đổi Firestore Rules cho bộ kiểm tra này. Cấu hình trên laptop đang dùng đã được cập nhật.

Ảnh có phụ kiện bị từ chối, không tăng số ảnh đã lưu. Thông báo nêu món cần tháo; kính cận cũng cần tháo. Khi chấm bằng mặt, ảnh bị chặn không tạo sự kiện chấm công. Trên web, phần **Camera & khuôn mặt → Kiểm tra khi chấm công** hiển thị phản hồi từ station đang chạy; màn hình xem ảnh riêng không tự kiểm tra khi station đã dừng. Android hiển thị lý do từ laptop khi đang đăng ký trong APK mới.

Khi bật chính sách, endpoint `POST /v1/face` từ chối sự kiện chỉ chứa metadata vì không có ảnh để kiểm tra. `station` và `poll` ghi sự kiện sau khi kiểm tra ảnh cục bộ. Đồng bộ các sự kiện đã được nhận trước đó và API vân tay vẫn hoạt động.

Giữ trọn khuôn mặt, rõ trán và cằm. Với ngưỡng mặc định `threshold: 0.5`, `uncertaintyMargin: 0.1`, điểm khẩu trang/kính từ 0.6 được báo là có phụ kiện; vùng 0.4–0.6 chưa chắc chắn cũng bị chặn. Chỉ tiếp tục khi các kiểm tra đang bật đều dưới 0.4. Đây là điểm phân loại của mô hình, không phải xác suất nhân viên đúng danh tính. Mô hình đang dùng thiếu, sai hash, lỗi suy luận hoặc đầu ra không hợp lệ sẽ chặn luồng mặt; dịch vụ không bỏ qua bước này để ghi công. `blockHat` thiếu cũng mặc định tắt trong bộ tạo recognizer từ cấu hình.

Cần kiểm tra thực tế trên OV5640 với đủ trường hợp: không phụ kiện, khẩu trang, kính cận, kính râm; thử cả đứng gần và xa. Mô hình có thể nhận nhầm trong bóng tối, góc nghiêng hoặc phụ kiện nhỏ. Kiểm thử ảnh công khai xác nhận các mô hình chạy được và chặn các ví dụ rõ; chưa đo độ chính xác trên nhóm nhân viên này. Không coi chức năng này là xác thực sống hay khả năng chống ảnh/video giả như ngân hàng. Khung bầu dục/hướng dẫn góc chụp vẫn chỉ hỗ trợ đặt mặt.

Nguồn và giấy phép: [FaceAttribNet của Qualcomm](https://github.com/quic/ai-hub-models/tree/main/qai_hub_models/models/face_attrib_net), [bản ONNX Face Attribute](https://github.com/yakhyo/face-attribute), [HHC](https://github.com/PINTO0309/HHC), [YOLO MIT Wholebody](https://github.com/PINTO0309/YOLO). Thông báo bản quyền đầy đủ nằm trong `licenses/`; mô hình nằm ở `data/models/` được Git bỏ qua. SHA256/kích thước trong `download_models.py` ngăn thay đổi trọng số ngoài ý muốn. Gallery YuNet/SFace hiện có giữ nguyên; không cần đăng ký lại chỉ vì bật bộ chặn phụ kiện.

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

Trong form **Yêu cầu khuôn mặt**, bấm **X**, **Đóng**, Esc hoặc ngoài form sẽ gửi `cancelRequested` nếu đang chờ/đang chụp, rồi đóng sau khi Firebase nhận yêu cầu. Laptop xác nhận `CANCELLED` và dừng lấy mẫu. Mất mạng hoặc thiếu quyền thì form giữ mở và hiện lỗi để tránh hiểu nhầm đã hủy. Khi đã `FINALIZING`, việc lưu tiếp tục và web thông báo không thể hủy ở bước này. Đăng xuất/thay form không tự gửi thao tác hủy.

Để sửa ảnh lộn đầu mà không nạp lại firmware, cấu hình `camera.rotation: 180` rồi mở lại dịch vụ. Chỉ chấp nhận số nguyên `0`, `90`, `180`, `270`; thiếu trường dùng `0`. `station`, `poll` và `capture` cùng xoay pixel trước kiểm tra mặt, lưu JPEG và nhận diện. Proxy web trả cùng chiều ảnh bằng metadata; nút xoay trên trình duyệt chỉ đổi góc xem thêm, không sửa ảnh mẫu.

Web có phần **Ảnh vừa chụp**: đúng khung ảnh của lần bấm được giữ tạm trong `CapturePreviewStore` trên RAM, một ảnh JPEG tối đa 5 MB và tự hết hạn sau 300 giây. `GET /v1/enrollment-preview/{requestId}/{sequence}` yêu cầu token Admin và chỉ trả đúng yêu cầu/lượt đang giữ. Node localhost gọi endpoint này bằng token riêng trên máy chủ; trình duyệt không nhận token. Ảnh này đã xoay trước kiểm tra, nên web không xoay lại. Lỗi camera, hủy/đăng ký thất bại hoặc dừng trạm xóa ảnh tạm; không cung cấp API duyệt thư mục ảnh hoặc vector, không ghi ảnh xem vào Firestore.

`low_quality` vẫn giữ tương thích, nhưng metadata `captureLastQualityIssue` phân biệt mặt bị cắt, mặt quá nhỏ, ảnh mờ và độ rõ chưa đạt. Mặt bị cắt được hướng dẫn **lùi lại và đưa toàn bộ mặt vào giữa ảnh**. Các ngưỡng kiểm tra chất lượng và đối chiếu khuôn mặt giữ nguyên.

Vào oval chưa xác nhận ảnh đạt: laptop còn kiểm tra độ nét và phụ kiện. Với `recognition.sharpnessMaxSize: 128`, điểm nét được đo trên vùng mặt thu nhỏ theo đúng tỉ lệ, cạnh dài tối đa 128 pixel (`FACE_MAX128_LAPLACIAN_V1`); vùng nhỏ không được phóng lớn. Cách này giảm việc cùng nội dung bị đánh điểm khác nhau chỉ vì kích thước mặt. Ảnh gốc, ảnh xem và đầu vào kiểm tra phụ kiện/SFace giữ nguyên; không làm nét nhân tạo. `minSharpness` vẫn là 50. File cấu hình cũ thiếu `sharpnessMaxSize` giữ phép đo cũ (`FACE_LAPLACIAN_RAW_V1`). Thông báo ảnh mờ nêu điểm đo và mức cần đạt; điểm này không phải phần trăm hay bảo đảm nhận đúng người. Metadata `captureLastSharpness`, `captureSharpnessRequired`, `captureSharpnessMetric` thuộc worker, không được dùng để client tự tăng tiến độ.

Nếu đầu và mặt đã vừa khung thì giữ vị trí, không cần lùi thêm. Đủ sáng phía trước, giữ yên lúc bấm và kiểm tra ống kính nếu ảnh gốc vẫn mềm/mờ. Việc chuẩn hóa được kiểm thử với ảnh pixel ở nhiều kích thước và ảnh làm mờ; cần thử thêm với camera thật để đánh giá điều kiện ánh sáng và ống kính.

Nếu đã quản lý khóa ở vị trí riêng, có thể đặt `GOOGLE_APPLICATION_CREDENTIALS` thay cho thư mục `private`. Biến chỉ đặt trong một cửa sổ PowerShell thì phải mở station **từ chính cửa sổ đó** bằng lệnh trên; nhấp đúp trong Explorer không nhận biến của cửa sổ PowerShell. Có khóa không tự chuyển `mode` sang Firebase: vẫn cần thực hiện bước 1.

App có thể yêu cầu hủy trong lúc chờ/chụp; worker xác nhận `CANCELLED`. Khi đã **Đang lưu** (`FINALIZING`), việc lưu phải hoàn tất trước thao tác tiếp theo. Đăng ký lại tự tăng version lớn hơn cả bản local và Firebase. **Xóa mẫu mặt** loại bỏ vector local và vô hiệu `faceProfiles`; ảnh mẫu local được giữ trong `data/photos/<employeeId>/` để Admin tự quản lý.

## Chấm công tự động sau khi đăng ký

Khi form báo **Đã đăng ký khuôn mặt**, mẫu local đã lưu và hồ sơ Firebase đã được xác nhận. Chỉ đủ 6/6 nhưng còn **Đang lưu** hoặc **Đang chờ xác nhận hồ sơ** thì chờ trạng thái hoàn tất. Nút Chụp ảnh chỉ dùng để đăng ký; sau khi đủ ảnh hoặc yêu cầu đã kết thúc, web không còn hiển thị nút này.

Giữ camera có nguồn, laptop cùng Wi-Fi với camera và `mo-tram-khuon-mat.cmd` đang chạy ở chế độ Firebase. Sau đăng ký, rời khung hình vài giây rồi quay lại, nhìn thẳng và giữ yên. Station tự đọc ảnh, kiểm tra độ rõ/khẩu trang/kính, nhận diện nhân viên và gửi lượt chấm công. Không cần Admin bấm Chụp ảnh, không cần mở trang Camera hoặc app để kích hoạt lượt quét, và không cần khởi động lại station sau đăng ký thành công. Nhận diện phải ổn định qua 3 khung liên tiếp; trước lượt mới cần 3 khung trống thật. Thời gian thực tế còn phụ thuộc camera, Wi-Fi và tốc độ laptop.

Xem kết quả ở **Chấm công**, phương thức **Khuôn mặt/FACE**. Trạng thái **Đạt kiểm tra phụ kiện** trên trang camera chưa phải xác nhận đã ghi công. Lượt quét mới được gửi ban đầu là `SCAN/PENDING`, rồi web/app xử lý vào/ra theo lịch.

### Mở cửa servo sau khi chấm công bằng mặt

ESP8266 phải báo firmware hỗ trợ TTL và đang online, có khả năng `door`. Bản mới `spark-anonymous-v18-face-name-door-ttl` hoặc `spark-gateway-v19-face-name-door-ttl` còn hiển thị tên nhân viên trên LCD khi mở cửa bằng mặt. `config.json` phải bật `faceDoor`; dịch vụ station Firebase và camera cũng phải chạy. Sau một lần nhận diện mặt mới được Firestore chấp nhận, laptop ghi lệnh `OPEN_DOOR` trong cùng giao dịch với lượt chấm. ESP8266 lấy lệnh qua Wi-Fi và tự mở servo; không cần Admin chụp ảnh hoặc bấm nút mở cửa. Servo giữ cửa mở 5 giây sau khi chạy hết hành trình rồi tự đóng.

Camera nhận diện liên tục, cần ba khung hình trống để bắt đầu lượt mới và ba khung hình liên tiếp cùng khớp hồ sơ trước khi ghi công. Với camera đang chạy ổn, hướng mặt vào camera và giữ yên khoảng 5 giây là mục tiêu; tốc độ mạng/laptop có thể làm lâu hơn. Khi Firestore xác nhận, web hiện tên và kết quả gần nhất trong 60 giây. Nếu mặt và vân tay của cùng nhân viên cùng điểm chấm đến trong 180 giây, kết quả sau sẽ được báo là đã gộp và không tạo lượt thứ hai.

Lệnh mở cửa chỉ sống tối đa 15 giây và firmware kiểm tra thời hạn lần nữa ngay trước khi mở. Lượt trùng, lượt gửi lại, ảnh đăng ký, camera offline, thiết bị offline hoặc một lệnh khác đang chạy không mở cửa. Lượt chấm công hợp lệ vẫn được ghi nếu mở cửa bị bỏ qua; xem `doorCommandStatus` trong bản ghi để biết lý do. Cắm camera vào nguồn, để ESP32-S3 và laptop cùng Wi-Fi, và giữ ESP8266 kết nối Internet/Firebase.

Chống trùng giữa mặt và vân tay hoạt động khi cả hai lượt được gửi qua laptop `Gateway` với cùng `stationId`. Nếu `ATTENDANCE_GATEWAY_MODE` vẫn là `0`, vân tay ghi thẳng lên Firestore và không thể được gộp với lượt mặt. Khi chuyển từ chế độ trực tiếp sang Gateway, trước hết đồng bộ hết hàng đợi cũ và chờ hơn 180 giây sau lượt cuối; không đổi chế độ hoặc xóa LittleFS giữa lúc lượt cũ còn đang gửi.

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

Để chống trùng giữa khuôn mặt và vân tay, cả hai phải đi qua Gateway với cùng `stationId`. Bản firmware `spark-gateway-v19-face-name-door-ttl` giữ nguyên ID và giờ quét trong LittleFS rồi gửi sự kiện vân tay tới `POST /v1/fingerprint`; laptop dùng cùng `attendanceGates` cho cả hai phương thức. Hai lượt cùng nhân viên trong 180 giây thành một lượt. Nếu vân tay được chấp nhận trước, khuôn mặt sau đó sẽ là lượt trùng; nếu khuôn mặt được chấp nhận trước, cửa mở theo lệnh khuôn mặt và vân tay sau đó bị gộp. Trang Camera & khuôn mặt hiện tên và kết quả lượt gần nhất sau khi hệ thống xác nhận; LCD ESP8266 cũng hiện tên nếu đã nạp bản mới. Hồ sơ `fingerprintMappings/{templateId}` cần trỏ đúng nhân viên đang hoạt động.

Đặt `config.json.host` thành địa chỉ Wi-Fi cụ thể của laptop (ví dụ `192.168.0.104`), và đặt `ATTENDANCE_GATEWAY_URL` trong `secrets.h` thành đúng địa chỉ đó cùng cổng `8765`. Không dùng `127.0.0.1` cho URL firmware. Giữ token khớp chính xác giữa `deviceToken` và `ATTENDANCE_DEVICE_TOKEN`; script nạp bên dưới tự kiểm tra mà không in token. Laptop, ESP8266 và camera cần cùng mạng Wi-Fi. Nếu DHCP đổi IP laptop, sửa cả hai cấu hình và khởi động lại station.

Windows cần một rule inbound chỉ cho chương trình Python của dịch vụ, TCP 8765 và `RemoteAddress LocalSubnet`. Mạng Public ở nhà vẫn có thể dùng với phạm vi LocalSubnet này. Mở PowerShell bằng **Run as administrator** rồi chạy `services/face_attendance/mo-cong-firewall-gateway.ps1`. Không mở cổng tới mọi địa chỉ Internet. Khi đã cập nhật `secrets.h`, đóng Serial Monitor/Plotter, cắm ESP8266 bằng USB rồi chạy từ thư mục gốc:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File services/face_attendance/cap-nhat-esp8266-gateway.ps1 -Port COM3
```

Script kiểm tra token/URL/điểm chấm có khớp trước khi nạp, biên dịch đúng NodeMCU và dùng `wipe=none` để giữ nguyên LittleFS. Cần thấy `Hash of data verified.`; sau khởi động, thiết bị phải báo firmware Gateway mới trên Firebase. Dịch vụ laptop cần chạy trong suốt thời gian chấm công. Khi đường Gateway lỗi, bản quét mới được giữ ở ESP8266 để thử lại, không ghi trực tiếp Firestore qua đường cũ.

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
