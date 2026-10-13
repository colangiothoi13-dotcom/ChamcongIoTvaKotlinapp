# Web quản trị dùng chung dữ liệu với Android

Mã nguồn: `web-admin/`. Web sử dụng ES modules, HTML/CSS và Firebase Web SDK 13.0.0 từ CDN chính thức. Không thêm phụ thuộc build vào ứng dụng Android. Server Node cục bộ phục vụ tài sản web đã cho phép và proxy xem camera; không công bố thư mục gốc, firmware hoặc cấu hình dịch vụ.

## Kiến trúc

```text
Trình duyệt máy tính
  ├─ Firebase Auth Email/Password
  ├─ Theo dõi users/{uid}: ADMIN và active == true
  ├─ Firestore Web SDK → cùng collections với Android
  ├─ /api/camera/health và /api/camera/frame → Node local → ESP32-S3
  ├─ Phân giải công hiệu lực trong bộ nhớ
  └─ Transaction/batch theo Firestore Rules hiện có
```

`src/app.js` quản lý phiên đăng nhập, điều hướng, tải dữ liệu, cửa sổ nhập liệu và thông báo. `src/firebase.js` cung cấp Web SDK adapter; `src/demo.js` giữ chế độ thử độc lập. Các nhóm thao tác nằm ở `organization.js`, `operations.js`, `attendance.js`. `domain.js` đối chiếu quy tắc công của Kotlin; `live-range.js` giữ tính đầy đủ của dữ liệu phân trang khi ghép listener giới hạn.

## Camera local

**Camera & khuôn mặt** dùng `src/camera-preview.js` để lấy ảnh JPEG mới nối tiếp, cách nhau ít nhất 400ms sau khi hoàn tất ảnh trước (tối đa 2,5 ảnh/giây). Không tạo yêu cầu chồng lấp. Người dùng chủ động Bắt đầu/Dừng; trang có trạng thái kết nối, thời gian ảnh mới nhất, nút Thử lại và xoay góc xem 90°. Chuyển mục, đăng xuất, ẩn tab hoặc đóng trang hủy yêu cầu và thu hồi blob URL. Firestore cập nhật giao diện không tự bắt đầu lại camera. Xem ảnh không ghi công và không đăng ký mẫu.

`scripts/camera-proxy.mjs` đọc cố định `services/face_attendance/config.json` trên máy chủ. Trình duyệt không nhận token hoặc chọn URL camera. `/api/camera/frame` yêu cầu GET, Host loopback đúng cổng, Origin cùng nguồn nếu có, `Sec-Fetch-Site` cùng nguồn và header `X-Camera-Preview: 1`; không bật CORS. `/api/camera/health` có cùng kiểm tra và chỉ trả về trạng thái/cảm biến/bộ nhớ đã cho phép. Node vẫn bind `127.0.0.1`. Quyền xem thuộc phiên máy chủ local trên laptop, độc lập với thông tin đăng nhập Firebase; UI chỉ mở mục camera trong phiên Admin hoặc xem dữ liệu mẫu. Máy chủ không cung cấp quyền đăng ký hoặc ghi chấm công từ endpoint xem ảnh.

Proxy không theo redirect, giới hạn thời gian upstream 6 giây, ảnh JPEG tối đa 5MB, health tối đa 16KB, một yêu cầu camera mỗi thời điểm và hủy upstream khi trình duyệt ngắt kết nối. Ảnh không lưu thành tệp và các phản hồi đều `no-store`. Lỗi chỉ hiện thông tin đã lọc, không in token hoặc nội dung phản hồi của camera.

Khi máy chủ Node đã chạy trước bản cập nhật này, cần Ctrl+C và mở lại launcher. Thay IP trong cấu hình dịch vụ không cần khởi động lại vì proxy đọc lại cấu hình mỗi lần. Camera chỉ dùng trên `localhost:4173` qua Node; static `dist`/Firebase Hosting không bao gồm proxy. Nút xoay ảnh không thay đổi mẫu Python: đặt board để ảnh gốc đứng thẳng trước khi đăng ký trên web hoặc app.

## Đăng ký khuôn mặt dùng chung với app

Khung oval xa/gần dùng `src/face-framing.js`, được giữ trong DOM camera riêng với progress. Với lệnh sáu ảnh hợp lệ, `samplesCaptured` 0/2/4 chọn FAR (khung nhỏ); 1/3/5 chọn NEAR (khung lớn hơn 35%). Reject hoặc pending click không đổi khung; hoàn tất/hủy/trạng thái không lấy mẫu/không có ảnh live ẩn overlay. SVG tạo vùng ngoài mờ, viền xanh và các đoạn cung xám, dùng `pointer-events:none`, không xoay theo `<img>` và không thay ảnh JPEG. Hướng dẫn có nhãn xa/gần rõ dưới hình, cập nhật live không đổi focus; hiệu ứng lớn khung tắt với reduced-motion. Bộ kiểm thử web không cần Emulator đạt **259/259**, gồm chuyển xa→gần theo ảnh được chấp nhận, từ chối giữ bước và lifecycle camera. GUI đã xem được cả hai kích thước trên camera thật với tiến độ giả lập; không gửi lệnh chụp hoặc lưu mẫu người thật. Backend, Rules và ngưỡng chất lượng không đổi trong bản giao diện này.

Kế hoạch mới trên web dùng sáu ảnh (`capturePlan: FRONT_LEFT_RIGHT_V1`, chỉ `MANUAL`): 2 nhìn thẳng, 2 quay nhẹ về tay trái của nhân viên, 2 về tay phải. `src/face-capture-plan.js` tạo hướng dẫn ba bước và bộ đếm 2 ảnh/góc từ **số ảnh được laptop chấp nhận**, nên ảnh lỗi không chuyển bước. Web/laptop/Rules chấp nhận đúng cặp kế hoạch+tổng 6; giữ lệnh AUTO/MANUAL 10 ảnh cũ. Mẫu số trên progress và ảnh xem dùng tổng của lệnh; HTTP preview có `X-Capture-Total: 6/10` và web kiểm tra khớp tổng trước khi hiện ảnh. Admin xem hình để xác nhận đúng góc; bộ hiện tại chưa đo góc quay đầu. Không thay gallery đã đăng ký hoặc ngưỡng nhận diện khi cập nhật UI.

Kiểm chứng kế hoạch sáu ảnh ngày 13/10/2026: **274 web**, **159 Python**, **105 Firebase Rules** đạt (mọi bài cần SDK/Rules đều chạy trên Firestore Emulator loopback). GUI dùng nhân viên/ảnh chụp mô phỏng để kiểm tra nhìn thẳng → trái → phải và ảnh lỗi giữ bước, không tạo mẫu người thật. Kiểm thử không đánh giá độ chính xác nhận diện với ảnh mới của nhân viên.

Bản phản hồi chụp ngày 13/10/2026 thêm `src/capture-result.js` và proxy `scripts/enrollment-preview-proxy.mjs`: chỉ tải ảnh sau khi laptop xác nhận đúng sequence; ảnh bị từ chối vẫn hiển thị nhưng không tăng số mẫu. Ảnh xem lấy từ RAM của worker, cùng pixel đã kiểm tra/lưu; không lấy một frame mới để giả làm ảnh vừa chụp. Token Admin chỉ ở Node, upstream cố định loopback theo cổng cấu hình, các header request/sequence/reason/count phải khớp và JPEG tối đa 5 MB. Ẩn/đóng/thay yêu cầu thu hồi blob và hủy request; lỗi preview không đổi lệnh hoặc gallery. Khung xem giữ toàn bộ ảnh 4:3. Thông báo `captureLastQualityIssue` giải thích riêng mặt bị cắt/quá nhỏ/ảnh mờ mà không giảm ngưỡng.

Kiểm chứng bản này: **263/263 web** và **150/150 Python** đạt, không bỏ qua test; gồm giao dịch SDK thật trên Firestore Emulator loopback và worker → RAM → HTTP với ảnh tổng hợp. GUI dùng tiến độ/ảnh chụp mô phỏng, camera trực tiếp thật; không tạo yêu cầu đăng ký Firebase hoặc mẫu nhân viên thật. Rules đã triển khai theo xác nhận người dùng lúc 09:44 ngày 13/10/2026 (giờ Việt Nam); bản phản hồi ảnh không thay Rules.

`src/face-enrollment.js` cung cấp thao tác trong phần Khuôn mặt của hồ sơ nhân viên. Admin đang hoạt động có thể đăng ký/đăng ký lại/xóa mẫu cho nhân viên đang hoạt động, theo dõi tiến độ và yêu cầu hủy. Chế độ dữ liệu mẫu chỉ hiển thị nút bị khóa và hướng dẫn kết nối thật; không tạo mẫu hoặc lệnh giả.

Web theo dõi `faceProfiles`, `faceStations` và 100 yêu cầu mới nhất trong `faceEnrollmentCommands`. Một transaction đọc lại quyền Admin, nhân viên, trạm, yêu cầu trước và khóa nhân viên, rồi ghi đúng 13 trường lệnh ban đầu cùng khóa trạm/nhân viên. Trạm phải chạy Firebase, camera sẵn sàng, cùng phiên dịch vụ và heartbeat trong 90 giây; Rules kiểm tra thời gian máy chủ. Đăng ký yêu cầu 10 ảnh, xóa mẫu yêu cầu 0 ảnh. Hai Admin hoặc web/app cùng bấm không tạo yêu cầu trùng cho một trạm hoặc một nhân viên.

Cửa sổ tiến độ theo dõi trực tiếp document lệnh và hồ sơ mẫu, độc lập với giới hạn 100 lệnh của bảng. Thành công chờ `faceProfiles.enabled/version` phù hợp phiên bản laptop đã lưu. Web chỉ gửi cờ hủy khi PENDING/CAPTURING; worker xác nhận CANCELLED. FINALIZING không được hủy. Đóng cửa sổ/đăng xuất tháo listener; mở lại hoặc thử theo dõi lại không tạo lệnh mới. Các lỗi listener có nút tải/theo dõi lại sau khi khôi phục mạng hoặc Rules.

Camera được nhúng ngay trong cửa sổ tiến độ bằng `src/enrollment-camera.js`, dùng controller JPEG hiện có với nhịp một giây và không chồng request. Camera DOM nằm riêng với nội dung tiến độ nên snapshot Firebase không thay thế ảnh hoặc nút xem. Khi mở yêu cầu ENROLL đang PENDING/CAPTURING, hình tự chạy; dừng ở FINALIZING/trạng thái kết thúc, đóng/thay cửa sổ, đăng xuất, ẩn tab và pagehide. Dừng hình không hủy lệnh, lỗi xem không thay đổi kết quả đăng ký. Controller chờ thao tác cũ kết thúc, bỏ qua frame trả muộn và thu hồi blob URL. Trang camera riêng được dừng trước khi mở viewer trong modal.

Proxy đọc `stationId` từ cấu hình laptop và trả metadata này trong `/api/camera/health`; viewer gửi `X-Camera-Station` trên health và từng frame. Proxy kiểm tra lại trạm trước mỗi request, từ chối camera khác trạm mà không liên hệ upstream; token vẫn chỉ nằm trên laptop. Cầu nối camera Android và viewer trang riêng giữ hành vi cũ.

Yêu cầu ENROLL mới từ web bổ sung `captureMode: MANUAL`, `captureRequestSeq: 0`, `captureConsumedSeq: 0`; lệnh Android không có các trường này vẫn dùng tự động. Nút **Chụp ảnh** tăng duy nhất sequence yêu cầu và timestamp trong transaction kiểm tra lại Admin, người mở yêu cầu, nhân viên, trạm/phiên, heartbeat và khóa. Chỉ laptop cập nhật sequence đã xử lý, số ảnh được lưu và thông báo. Rules cấm client giả tiến độ/hoàn tất hoặc gửi nhiều lượt trước xác nhận ảnh trước. Worker chờ qua document listener, lấy đúng một frame cho mỗi sequence; ảnh lỗi cũng xác nhận sequence, chỉ ảnh đạt mới tăng số mẫu. Có giới hạn 100 lần thử và năm phút chờ.

`camera.rotation` được kiểm tra nghiêm ngặt ở Python và proxy: số nguyên 0/90/180/270, mặc định 0. Python xoay pixel của nguồn JPEG trước quality/capture/recognition; proxy trả `rotation` trong health và `X-Camera-Rotation` trên mỗi frame, từ cùng cấu hình request. Viewer kết hợp chiều nguồn với góc xoay chỉ để xem, không xoay hai lần ảnh mẫu. Kiểm chứng ngày 13/10/2026: web **235/235**, Python **125/125**, Firestore Rules **99/99** đạt trên emulator, không bỏ qua integration tests; chụp bằng dữ liệu giả để kiểm tra giao diện, không tạo yêu cầu hay lưu ảnh nhân viên thật.

Các ảnh và vector do dịch vụ laptop xử lý; trình duyệt không giữ khóa Firebase Admin hoặc camera token. Xóa mẫu tắt metadata và xóa vector nhận diện; không xóa các ảnh thu trên laptop. Đăng ký thật cần khóa Admin riêng trong `services/face_attendance/private/firebase-admin.json`, cấu hình dịch vụ `mode: firebase`, Firestore Rules mới và launcher trạm đang chạy. Xem [hướng dẫn dịch vụ](../services/face_attendance/README.md). Không cần Functions hoặc thay đổi firmware để bổ sung UI này.

Tài khoản nhân viên được tạo bằng Firebase Auth instance thứ hai với persistence trong bộ nhớ, để không làm mất phiên admin. Transaction tạo profile đọc lại trạng thái nhân viên và tăng `accountVersion`; chuyển sang đã nghỉ kiểm tra token này cùng các tài khoản liên kết. Khi tạo profile thất bại, helper cố gắng dọn Auth user vừa tạo và báo rõ nếu chưa dọn được.

## Đối chiếu nghiệp vụ

| Web | Nguồn Android / rules |
| --- | --- |
| Vai trò, hồ sơ, nghỉ việc, vân tay | FirebaseRepositoryEmployees.kt, EmployeeAccountRules.kt, firestore.rules |
| Đăng ký/xóa mẫu mặt, tiến độ, hủy và khóa đồng thời | FirebaseRepositoryFaceEnrollment.kt, FaceEnrollmentModels.kt, FaceEnrollmentControls.kt, services/face_attendance, firestore.rules |
| Phòng ban, thiết bị, thông báo | FirebaseRepositoryOrganization.kt |
| Công việc + history cùng phiên bản | FirebaseRepositoryWorkItems.kt, WorkItemRules.kt |
| Phân ca và duyệt tuần | FirebaseRepositoryShiftAssignments.kt, FirebaseRepositoryWeeklyRequests.kt |
| Duyệt đơn và tăng ca | FirebaseRepositoryRequests.kt |
| Công hiệu lực, phân loại/điều chỉnh | AttendanceResolutionRules.kt, SchedulingRules.kt, PresenceRules.kt |
| Thưởng và báo cáo công | KpiBonusRules.kt, ReportRules.kt |
| Phiếu lương và lưu bản trước/sau | FirebaseRepositoryPayrollRevisions.kt |

Quy tắc quan trọng được kiểm thử: ghép nhiều ca, trùng trong 180 giây, không có lịch giữ raw pending, phân loại sửa làm điểm bắt đầu phiên, nghỉ theo ca, tăng ca đúng 18:00–22:00, thiếu ra ca, lượt ra đơn lẻ có điều chỉnh, quyết định ngoài lịch, tính lương một lần, thứ hạng có quy tắc phá hòa, xung đột phiên bản và ghi nhật ký cùng dữ liệu.

Đã đối chiếu các nhóm quản trị: tổng quan, công việc, nhân viên, phòng ban, lịch/ca, duyệt đơn và lịch tuần, chấm công/có mặt, lương, báo cáo/bảng công, hiệu suất, thiết bị, thông báo, tiện ích và nhật ký. Web dùng bố cục máy tính, cùng nghiệp vụ và dữ liệu với app Admin. Phần nhân viên tự đăng ký lịch, gửi đơn, cập nhật tiến độ và nộp kết quả thuộc giao diện Employee trên Android.

Các phần bổ sung sau đối chiếu: lịch sử công việc tải được tệp trong `history.after.resultAttachments` dù kết quả hiện tại đã bị thay khi giao lại; helper đọc đúng version, metadata và chunks Firestore rồi kiểm tra SHA256. Chi tiết công việc có bằng chứng chấm công đã phân giải của đúng ca liên kết, loại lượt thất bại và không gán một lượt legacy cho nhiều ca. Tổng đăng ký tuần đếm PENDING/APPROVED theo ngày/ca, loại NEEDS_REVISION và khử trùng cùng nhân viên/ca. Nhật ký tìm theo người, thao tác, nội dung, đối tượng và lý do.

Bảng công tháng, chi tiết và CSV dùng `monthlyTimesheetRows`/`monthlyTimesheetEmployees`: giữ ngày nghỉ việc hợp lệ, loại ngày/tháng sau đó kể cả trạng thái còn active, và giữ hồ sơ đã nghỉ thiếu/sai ngày nghỉ khi có bằng chứng công/lịch trong kỳ đến tháng hiện tại theo giờ Việt Nam. Helper không đổi danh sách điều chỉnh công, phạm vi tính phiếu lương hoặc lịch sử công gốc. Nút Chi tiết ngày / ca mở bảng giờ quét so với giờ tính công từng ca và điều chỉnh hiệu lực. Tạo/tính lại lương dùng chính `monthlyTotals.rows` để mở chi tiết trước khi xác nhận, giữ nguyên phiếu đã lưu cho đến transaction lưu phiên bản mới.

## Kiểm chứng

Từ `web-admin`, `npm test` chạy các kiểm thử Node cho công/lương, thao tác quản trị, đầy đủ dữ liệu phân trang và các trang hiển thị. `npm run build` xác minh/tạo gói static. Kiểm thử Firestore integration chỉ chạy với emulator loopback khi được bật; không sử dụng dữ liệu production.

Có thêm kiểm tra JavaScript entry point (đăng nhập → dữ liệu mẫu → toàn bộ mục → đăng xuất) và HTTP server (tài sản web hợp lệ, MIME, chặn tệp dự án và đường dẫn thoát thư mục). Các bài này kiểm tra kết nối mã và máy chủ, không thay thế kiểm tra trực quan trên trình duyệt.

Bảy kịch bản desktop đã đạt trên Edge headless với profile thử riêng, dùng dữ liệu mẫu trong bộ nhớ để thay các lời gọi Firebase: mở dialog native; lưu biểu mẫu; mở các thao tác công việc, phân ca và điều chỉnh công; Edit đang chờ dữ liệu không chặn Add hoặc thay dialog mới hơn; bấm nút sau khi bộ lọc mất focus vẫn mở form dù trang cần render lại; lỗi hiển thị ngay trong dialog; danh sách điều chỉnh công ẩn người nghỉ trước tháng đang chọn, cập nhật khi đổi ngày và giữ nội dung đã nhập. Lần kiểm tra này không sử dụng trình duyệt/phiên đăng nhập của người dùng và không đọc hoặc ghi Firebase thật.

Lần kiểm tra local ngày **12/10/2026**: **179/179 kiểm thử đạt, không bỏ qua**, với Firestore Emulator `127.0.0.1:8192`; `npm run build` thành công. Bao gồm 22 bài đơn vị đăng ký mặt, 5 bài giao dịch/quyền mặt chạy emulator, 11 bài đối chiếu phần Admin còn thiếu, 7 bài bảng công tháng/nghỉ việc, 13 bài proxy/lifecycle camera và 7 bài danh sách nhân viên điều chỉnh công theo tháng nghỉ việc; thêm hồi quy form gộp, ca mặc định, phân ca nhiều người/từng ngày và lịch tuần/tháng. Entry point xác minh lưu tiếp tục trong form gộp sau lỗi thao tác con, khóa hủy khi lưu, trạng thái dữ liệu mẫu và chuyển lịch tuần/tháng. Proxy được kiểm tra với camera giả: chặn Host/Origin/header không hợp lệ, không lộ token, từ chối redirect/ảnh lỗi/quá lớn, hủy khi timeout/ngắt trình duyệt. Không gọi Firebase production.

Cả 13 kịch bản integration đã đạt trong lần chạy này. Bảy bài quản trị gọi chính helper/thao tác web rồi gửi commit thực tới rules hiện tại: nhân viên/phòng ban, bảy lệnh thiết bị và vân tay, history công việc, phân ca/duyệt đơn, điều chỉnh công, lưu phiên bản lương và khóa tài khoản khi nghỉ việc; đồng thời kiểm tra từ chối quyền nhân viên, sửa lượt chấm gốc và sửa lịch sử lương. Năm bài mặt kiểm tra đúng 13 trường, hai khóa nguyên tử, DELETE 0 ảnh, cờ hủy, hai transaction đồng thời chỉ một thành công, trạm cũ/demo/offline/sai phiên và quyền Admin. Một bài phân ca tạo mẫu chuẩn còn thiếu cùng transaction và giữ cấu hình/ID ca cũ. Harness có bài chặn host ngoài loopback; không dùng production.

REST harness mô phỏng cơ chế retry của `runTransaction`: tối đa 5 lần cho HTTP 409 có mã ABORTED, rollback và đọc lại dữ liệu mỗi lần, không thử lại lỗi quyền hoặc lỗi nghiệp vụ. Đã kiểm tra riêng việc retry sau tranh khóa, đọc dữ liệu mới và không retry PERMISSION_DENIED/ALREADY_EXISTS. Adapter web thật vẫn dùng Firebase SDK `runTransaction`.

Giao diện có mục Thao tác quản lý trên Tổng quan và cột thao tác cố định ở mép phải bảng. Kiểm thử entry point đi qua nút thêm nhân viên, lưu, sửa hồ sơ, khóa hủy khi đang lưu, chuyển trang về đầu và tự tải tháng lương. Kiểm thử chấm công xác nhận điều chỉnh từ từng dòng giữ đúng nhân viên/ngày, cặp giờ theo ca, độ chính xác thời gian và bản ghi gốc; phần lương cho phép quản lý cả nhân viên đã có phiếu và đặt đơn giá qua luồng nhân sự hiện có.

Thêm/sửa nhân viên dùng một cửa sổ gồm **Hồ sơ, Tài khoản, Vân tay, Khuôn mặt**. Trước khi lưu nhân viên mới, nút lấy mẫu/tạo tài khoản chưa hoạt động; transaction thành công mở lại cùng editor với đúng snapshot/mã đã commit. Lưu không tạo lệnh thiết bị, yêu cầu mặt hoặc liên kết mẫu. Bấm tên nhân viên mở chi tiết có các tác vụ cùng editor. Khi có thay đổi chưa lưu, giữ draft và khóa thao tác con; việc đọc cũ không thay cửa sổ đang sửa. Kiểm thử giữ các chặn nhân viên đã nghỉ, yêu cầu đang chờ, dữ liệu mẫu, lỗi quyền và danh sách realtime cập nhật chậm.

Phân ca dùng hai mẫu chính có ID ổn định như Android; chỉ gom trong danh sách/lựa chọn, giữ mọi ca legacy cho tính công lịch sử. Có chọn nhiều nhân viên, từng ngày làm trong khoảng 31 ngày, điền sẵn ca và mặc định thay bộ ca khi sửa một ngày. Điều chỉnh giờ mở helper hiện có với đúng nhân viên/ngày. Lịch tuần/tháng dùng `daySummary` và các lượt đã phân giải để hiển thị trạng thái riêng từng ca; chưa đủ dữ liệu thì không báo thiếu chấm. Chọn tháng khác mở phân ca theo ngày đang hiển thị, không lấy tuần cũ. Trình duyệt dữ liệu mẫu đã xác minh form gộp, dirty guard, bấm tên mở chi tiết, checkbox nhiều người, hai lựa chọn ca chính, lịch tháng 11/2026 mở đúng ngày 02/11 và nút camera ở trên khung ảnh. Không gửi yêu cầu lấy mẫu hay ghi dữ liệu thật.

Danh sách tạo điều chỉnh công dùng cùng quy tắc tháng nghỉ việc với Android: `active != false` hoặc `terminationDate` hợp lệ nằm trong tháng đang sửa hay tháng sau. Đổi Ngày ca cập nhật riêng ô chọn nhân viên và bỏ lựa chọn nếu người đó không còn phù hợp. Trước khi lưu, web đọc lại hồ sơ để phát hiện việc cho nghỉ sau khi mở biểu mẫu. Điều chỉnh từ lượt chấm cụ thể vẫn hỗ trợ lịch sử; không xóa hồ sơ, lượt quét hoặc phiếu lương cũ.

Để chạy lại nhóm emulator, mở Firestore Emulator với project thử `demo-web-admin-integration`, rồi trong terminal tại `web-admin`:

```powershell
$env:WEB_FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080"
npm test
```

Đổi cổng cho đúng emulator đang chạy. Helper chỉ chấp nhận địa chỉ loopback. Khi không đặt biến này, `npm test` bỏ qua 17 kịch bản cần emulator và vẫn chạy toàn bộ bài thường.

Kiểm tra ban đầu trước các bản tích hợp và triển khai Rules ngày 13/10/2026: Đã kiểm tra trực tiếp trong trình duyệt Codex với dữ liệu mẫu tại `localhost:4183`: cột Khuôn mặt và lý do khóa trong chế độ mẫu; bảng công tháng mở được chi tiết ngày/ca và phân biệt giờ quét 07:55 với giờ tính công 08:00. Camera đã trả ảnh OV5640 qua proxy local trong phiên kiểm tra trước, nhưng ảnh rất tối và chưa đánh giá nhận diện trên nhân viên. Chưa kiểm tra điện thoại thật, đăng nhập Admin thật hoặc dữ liệu Firebase thật. Cần mở http://localhost:4173 để kiểm tra với tài khoản admin của dự án. Chưa triển khai Firebase Hosting/Rules và chưa gửi lệnh đăng ký tới phần cứng thật. Emulator xác minh Firestore; tạo tài khoản Firebase Auth thứ hai và hoàn tất lệnh trên cảm biến cần xác minh trong môi trường thật.

## Quy trình kiểm tra trực tiếp

1. Mở `web-admin/mo-web-admin.cmd` rồi truy cập http://localhost:4173.
2. Xem dữ liệu mẫu, chuyển qua các trang, tìm tên có dấu/không dấu, mở form và hủy.
3. Đăng nhập tài khoản admin của app; đối chiếu nhân viên, công việc và ca đã có.
4. Chọn một khoảng công nhỏ; so sánh giờ vào/ra, giờ thường và tăng ca với app.
5. Kiểm tra bản xem trước lương và lịch sử; chỉ xác nhận khi muốn đổi phiếu thật.
6. Kiểm tra thao tác cần thiết trên dữ liệu thử được quản trị viên chủ động tạo.

Không gửi lệnh thiết bị trong bước kiểm thử tự động. Đăng ký/xóa vân tay và điều khiển cửa cần thiết bị online đúng ID; lệnh được firmware hoàn tất trên Firestore như trong app.
