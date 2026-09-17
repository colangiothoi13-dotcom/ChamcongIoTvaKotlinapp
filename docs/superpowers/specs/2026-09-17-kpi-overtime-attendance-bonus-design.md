# KPI thưởng theo tăng ca và đi muộn

## Mục tiêu

Tự động tính phần thưởng trong phiếu lương theo dữ liệu chấm công đã được resolver xác nhận. Nhân viên tăng ca nhiều nhưng không đi muộn được ưu tiên thưởng Top 3; mỗi ca tăng ca có thêm thưởng cố định; mỗi lần đi muộn làm giảm tiền thưởng.

## Phạm vi

- Áp dụng theo từng tháng lương.
- Ca chính vẫn do Admin phân theo tuần; ca bổ sung không được phân trước trong lịch tuần.
- Nhân viên tự gửi một đơn xin ca bổ sung cho từng ngày; Admin có thể duyệt sau.
- Tái sử dụng các cặp chấm công đã resolve và adjustment hiện có, nhưng bổ sung trạng thái đơn xin ca để giữ được các lượt quét trong thời gian chờ duyệt.
- Thêm collection `overtimeRequests`, Firestore rules và index cần thiết; không thay đổi dữ liệu attendance gốc.
- Không cho phép dữ liệu `PENDING`, `DUPLICATE`, `UNSCHEDULED`, `OUT_OF_ORDER`, malformed hoặc thiếu một đầu chấm công tạo thành ca tăng ca hợp lệ nếu đơn chưa được duyệt.

## Quy tắc nghiệp vụ

### Ca tăng ca

- Ca `SUPPLEMENTARY` được cố định từ `17:30` đến `20:30` theo múi giờ `Asia/Ho_Chi_Minh`, không cho Admin thay đổi giờ và không đưa vào lịch phân ca tuần.
- Nhân viên được gửi đơn cho ngày hiện tại hoặc ngày làm việc sắp tới. Mỗi nhân viên chỉ có một đơn cho một ngày; đơn có trạng thái `PENDING`, `APPROVED` hoặc `REJECTED`.
- Khi đơn `PENDING`, nhân viên vẫn được check-in/check-out trong khung ca; hệ thống lưu các lượt quét và đánh dấu ứng viên tăng ca đang chờ duyệt, không chặn thiết bị và không coi là quét trùng với ca chính.
- Admin duyệt sau vẫn làm cho các lượt quét hợp lệ trong ngày được resolver tính lại thành ca tăng ca. Admin từ chối không xóa lượt quét, nhưng các lượt đó không được tính tiền tăng ca, thưởng theo ca hoặc Top 3.
- Một ca chỉ được tính là hoàn thành khi đơn đã `APPROVED`, có cặp check-in/check-out hợp lệ, được resolver chấp nhận và nằm trong khung `17:30–20:30` theo cùng ngày địa phương.
- Mỗi ca tăng ca hoàn thành:
  - tính tiền giờ theo đơn giá hiện có và 3 giờ làm;
  - cộng thêm `50.000đ` vào tiền thưởng.

### Đơn xin ca bổ sung

- `overtimeRequests` lưu tối thiểu: `employeeId`, `workDate`, `startTime = 17:30`, `endTime = 20:30`, `status`, `submittedAt`, `reviewedAt`, `reviewedBy` và `rejectionReason` khi bị từ chối.
- Khóa logic của đơn là `employeeId + workDate`; không tạo hai đơn đang hoạt động cho cùng một nhân viên và ngày.
- Admin nhìn thấy danh sách đơn chờ duyệt, có thể duyệt hoặc từ chối. Từ chối bắt buộc nhập lý do để nhân viên và audit log giải thích được quyết định.
- Duyệt/từ chối phải ghi audit log; thao tác duyệt làm mới kết quả resolve và breakdown payroll liên quan nếu phiếu lương chưa được lưu.
- Đơn được gửi sau 17:30 vẫn không bị mất; nếu còn trong khung đến 20:30, các lượt quét hợp lệ vẫn được gắn vào đơn đang chờ duyệt.

### Top 3 chuyên cần tăng ca

- Xếp theo số ca tăng ca hoàn thành trong tháng.
- Chỉ nhân viên có `lateCount == 0` mới đủ điều kiện Top 3.
- Ba người đứng đầu đủ điều kiện nhận mỗi người `500.000đ`.
- Nếu đồng hạng, ưu tiên tổng giờ tăng ca cao hơn; nếu vẫn bằng, dùng mã nhân viên để có kết quả ổn định.
- Nếu có ít hơn 3 người đủ điều kiện, chỉ những người có trong danh sách được thưởng.

### Đi muộn và giới hạn thưởng

- `lateCount` lấy từ các ca chính đã phân sau khi áp dụng lịch và adjustment hiện hành, không lấy trực tiếp từ status raw cũ; ca bổ sung không bị tính là đi muộn.
- Mỗi lần đi muộn trừ `100.000đ` khỏi tiền thưởng.
- Công thức:

  `bonus = top3Bonus + overtimeShiftCount * 50.000 - lateCount * 100.000`

- `bonus` được chặn sàn ở `0đ`; phần âm không chuyển thành khấu trừ lương.
- `deduction` hiện có của phiếu lương không bị tự động thay đổi bởi quy tắc này.

## Thiết kế kỹ thuật

### Domain

Thêm bộ tính thuần Kotlin, nhận employees, attendance, schedules, shifts, overtime requests, adjustments, tháng và timezone; trả về số ca tăng ca, số lần đi muộn, hạng Top 3, tiền thưởng tự động và tổng giờ tăng ca. Bộ tính chỉ nhận request đã `APPROVED` khi tính thưởng; request `PENDING` vẫn được dùng để phân loại ứng viên chấm công nhưng không tạo tiền. Bộ tính dùng chung resolver/employee summaries hiện có và không import Firebase.

### Ca bổ sung

Tại luồng quản lý ca tuần chỉ hiển thị các ca chính. Ca bổ sung dùng mốc cố định `17:30–20:30` và được tạo từ đơn của nhân viên, không lưu như một lịch phân trước trong `WorkSchedule`. Resolver phải tách phiên ca chính và phiên tăng ca để lượt quét lúc 17:30 không bị coi là duplicate của lượt quét ca chính.

### Payroll/UI

- `PayrollScreen` hiển thị breakdown: giờ tăng ca, số ca tăng ca, số lần đi muộn, thưởng Top 3, thưởng theo ca và phần trừ đi muộn.
- Trường thưởng được điền tự động theo tháng/nhân viên; Admin không phải nhập tay.
- Khi lưu phiếu lương, giá trị thưởng tự động được lưu vào trường `Payroll.bonus` hiện có.
- Nếu lịch/chấm công/adjustment thay đổi trước khi lưu, breakdown được tính lại từ state mới.

### Firebase và tương thích

- Thêm collection `overtimeRequests` cùng rules giới hạn nhân viên chỉ tạo/xem đơn của mình, còn Admin mới được duyệt/từ chối; các thao tác review phải ghi vào `audit_logs`.
- Cập nhật resolver/Cloud Function hiện có để tra đơn theo `employeeId + workDate`, giữ các lượt quét khi request `PENDING` và resolve lại khi request được duyệt; không cần tạo Cloud Function mới.
- Tạo index Firestore cho các truy vấn danh sách đơn chờ duyệt nếu Firebase yêu cầu khi triển khai query thực tế.
- Phiếu lương hiện có tiếp tục lưu snapshot; phiếu đã lưu không tự động hồi tố.
- Dữ liệu legacy vẫn dùng fallback hiện tại; status/scan không đủ điều kiện hoặc request bị từ chối không được tính tăng ca.

## Kiểm thử

- Ca supplementary luôn cố định `17:30–20:30` và dài đúng 3 giờ.
- Đơn xin ca được tạo đúng theo nhân viên/ngày, trạng thái chuyển đúng `PENDING → APPROVED/REJECTED`, và lý do từ chối bắt buộc.
- Request `PENDING` không chặn check-in/check-out; khi được duyệt, lượt quét hợp lệ trước đó được resolve lại; khi bị từ chối, dữ liệu vẫn còn nhưng không tính tăng ca.
- Khung cố định `17:30–20:30` không bị nhập sai hoặc bị phân trước trong lịch tuần; lượt quét không bị nhầm với ca chính 13:00–17:00.
- Ca thiếu check-out, rejected/unverified hoặc duplicate không được tính.
- Top 3 chỉ chọn người không đi muộn; kiểm tra đồng hạng và ít hơn 3 người.
- Một ca tăng ca cộng đúng `50.000đ`; mỗi lần đi muộn trừ đúng `100.000đ`; thưởng âm bị chặn về 0.
- Payroll và breakdown UI dùng cùng kết quả domain.
- Regression cho adjustment, ca qua đêm và legacy attendance.
