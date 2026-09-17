# KPI thưởng theo tăng ca và đi muộn

## Mục tiêu

Tự động tính phần thưởng trong phiếu lương theo dữ liệu chấm công đã được resolver xác nhận. Nhân viên tăng ca nhiều nhưng không đi muộn được ưu tiên thưởng Top 3; mỗi ca tăng ca có thêm thưởng cố định; mỗi lần đi muộn làm giảm tiền thưởng.

## Phạm vi

- Áp dụng theo từng tháng lương.
- Tái sử dụng các cặp chấm công đã resolve, lịch làm việc và ca đã phân.
- Không thay đổi dữ liệu attendance gốc và không thêm collection Firebase mới.
- Không cho phép dữ liệu `PENDING`, `DUPLICATE`, `UNSCHEDULED`, `OUT_OF_ORDER`, malformed hoặc thiếu một đầu chấm công tạo thành ca tăng ca hợp lệ.

## Quy tắc nghiệp vụ

### Ca tăng ca

- Ca có category `SUPPLEMENTARY` có thời lượng cố định 3 giờ.
- Admin nhập giờ bắt đầu; hệ thống tự tạo giờ kết thúc sau 3 giờ, hỗ trợ qua ngày.
- Một ca chỉ được tính là hoàn thành khi có cặp check-in/check-out hợp lệ, được resolver chấp nhận và thuộc đúng lịch ca.
- Mỗi ca tăng ca hoàn thành:
  - tính tiền giờ theo đơn giá hiện có và 3 giờ làm;
  - cộng thêm `50.000đ` vào tiền thưởng.

### Top 3 chuyên cần tăng ca

- Xếp theo số ca tăng ca hoàn thành trong tháng.
- Chỉ nhân viên có `lateCount == 0` mới đủ điều kiện Top 3.
- Ba người đứng đầu đủ điều kiện nhận mỗi người `500.000đ`.
- Nếu đồng hạng, ưu tiên tổng giờ tăng ca cao hơn; nếu vẫn bằng, dùng mã nhân viên để có kết quả ổn định.
- Nếu có ít hơn 3 người đủ điều kiện, chỉ những người có trong danh sách được thưởng.

### Đi muộn và giới hạn thưởng

- `lateCount` lấy từ các ngày/ca đã phân sau khi áp dụng lịch và adjustment hiện hành, không lấy trực tiếp từ status raw cũ.
- Mỗi lần đi muộn trừ `100.000đ` khỏi tiền thưởng.
- Công thức:

  `bonus = top3Bonus + overtimeShiftCount * 50.000 - lateCount * 100.000`

- `bonus` được chặn sàn ở `0đ`; phần âm không chuyển thành khấu trừ lương.
- `deduction` hiện có của phiếu lương không bị tự động thay đổi bởi quy tắc này.

## Thiết kế kỹ thuật

### Domain

Thêm bộ tính thuần Kotlin, nhận employees, attendance, schedules, shifts, adjustments, tháng và timezone; trả về số ca tăng ca, số lần đi muộn, hạng Top 3, tiền thưởng tự động và tổng giờ tăng ca. Bộ tính dùng chung resolver/employee summaries hiện có và không import Firebase.

### Ca bổ sung

Tại luồng quản lý ca tuần, template `SUPPLEMENTARY` yêu cầu start time và tự suy ra end time `start + 3h`. Dữ liệu lịch vẫn lưu trong `WorkSchedule` và `WorkShift` hiện có; không tạo schema mới.

### Payroll/UI

- `PayrollScreen` hiển thị breakdown: giờ tăng ca, số ca tăng ca, số lần đi muộn, thưởng Top 3, thưởng theo ca và phần trừ đi muộn.
- Trường thưởng được điền tự động theo tháng/nhân viên; Admin không phải nhập tay.
- Khi lưu phiếu lương, giá trị thưởng tự động được lưu vào trường `Payroll.bonus` hiện có.
- Nếu lịch/chấm công/adjustment thay đổi trước khi lưu, breakdown được tính lại từ state mới.

### Firebase và tương thích

- Không thêm collection, index hoặc Cloud Function mới.
- Phiếu lương hiện có tiếp tục lưu snapshot; phiếu đã lưu không tự động hồi tố.
- Dữ liệu legacy vẫn dùng fallback hiện tại; status/scan không đủ điều kiện không được tính tăng ca.

## Kiểm thử

- Ca supplementary luôn dài 3 giờ, gồm trường hợp qua ngày.
- Ca thiếu check-out, rejected/unverified hoặc duplicate không được tính.
- Top 3 chỉ chọn người không đi muộn; kiểm tra đồng hạng và ít hơn 3 người.
- Một ca tăng ca cộng đúng `50.000đ`; mỗi lần đi muộn trừ đúng `100.000đ`; thưởng âm bị chặn về 0.
- Payroll và breakdown UI dùng cùng kết quả domain.
- Regression cho adjustment, ca qua đêm và legacy attendance.
