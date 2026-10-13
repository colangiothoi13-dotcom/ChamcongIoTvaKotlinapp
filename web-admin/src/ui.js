export const e = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const normalize = value => String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g,'d').replace(/Đ/g,'D').toLowerCase().trim();
const paths = {
  camera:'M3 7h4l2-3h6l2 3h4v14H3z M16 14a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  dashboard:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  users:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8',
  tasks:'M9 5h11 M9 12h11 M9 19h11 M3 5l1 1 2-2 M3 12l1 1 2-2 M3 19l1 1 2-2',
  calendar:'M8 2v4 M16 2v4 M3 10h18 M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2 M7 14h3 M14 14h3 M7 18h3',
  clock:'M12 8v4l3 2 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  check:'M20 6 9 17l-5-5',
  device:'M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2 M10 17h4 M8 7h8 M8 10h8',
  wallet:'M21 8H5a2 2 0 0 1 0-4h14v4 M3 6v13a2 2 0 0 0 2 2h16V8 M21 12h-5v5h5',
  chart:'M4 3v18h18 M8 17v-5 M13 17V8 M18 17V5',
  file:'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M8 13h8 M8 17h6',
  building:'M4 21V3h12v18 M16 9h4v12 M2 21h20 M8 7h4 M8 11h4 M8 15h4 M9 21v-3h2v3',
  bell:'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
  history:'M3 12a9 9 0 1 0 3-7 M3 3v5h5 M12 7v5l3 2',
  search:'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  plus:'M12 5v14 M5 12h14',
  download:'M12 3v12 M7 10l5 5 5-5 M4 16v5h16v-5',
  logout:'M9 21H3V3h6 M9 12h12 M17 8l4 4-4 4',
  arrow:'M5 12h14 M13 6l6 6-6 6',
  refresh:'M20 7a9 9 0 0 0-15-2L2 8 M2 3v5h5 M4 17a9 9 0 0 0 15 2l3-3 M22 21v-5h-5',
  menu:'M3 6h18 M3 12h18 M3 18h18',
  shield:'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6z M8 12l3 3 5-6',
  edit:'M16 3l5 5 M4 20l4-1L21 6l-5-5L3 14z',
  close:'M6 6l12 12 M6 18 18 6'
};
export function icon(name) { return `<svg class="icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="${paths[name] || paths.file}"/></svg>`; }
const labels = {ACTIVE:'Đang làm việc',INACTIVE:'Đã ngừng',ON_TIME:'Đúng giờ',LATE:'Đi trễ',EARLY_LEAVE:'Về sớm',NORMAL:'Bình thường',CHECK_IN:'Vào ca',CHECK_OUT:'Ra ca',SCAN:'Lượt quét gốc',PENDING:'Chờ xử lý',ACCEPTED:'Hợp lệ',REJECTED:'Từ chối',APPROVED:'Đã duyệt',DUPLICATE:'Quét trùng',UNSCHEDULED:'Ngoài lịch',DISCARDED:'Đã loại',ASSIGNED:'Được giao',IN_PROGRESS:'Đang thực hiện',SUBMITTED:'Chờ duyệt',COMPLETED:'Hoàn thành',REWORK_REQUIRED:'Cần làm lại',NEEDS_REVISION:'Cần điều chỉnh',CANCELLED:'Đã hủy',ONLINE:'Trực tuyến',OFFLINE:'Mất kết nối',LOW:'Thấp',MEDIUM:'Trung bình',NORMAL_PRIORITY:'Bình thường',HIGH:'Cao',URGENT:'Khẩn cấp',MORNING:'Ca sáng',EVENING:'Ca chiều',SUPPLEMENTARY:'Tăng ca',FINGERPRINT:'Vân tay',FACE:'Khuôn mặt',UNKNOWN:'Chưa rõ',LEAVE:'Nghỉ phép',ATTENDANCE_ADJUSTMENT:'Điều chỉnh công',SHIFT_CHANGE:'Đổi ca',MEETING:'Lịch họp',REWARD:'Khen thưởng',DOCUMENT:'Tài liệu'};
Object.assign(labels,{PENDING_REVIEW:'Chờ duyệt',PRESENT:'Đang có mặt',LEFT:'Đã rời ca',ON_LEAVE:'Nghỉ phép',NOT_CHECKED_IN:'Chưa chấm vào',MISSING_CHECK_IN:'Thiếu chấm vào',MISSING_CHECK_OUT:'Thiếu chấm ra',ABNORMAL:'Cần kiểm tra'});
export const label = value => labels[value] || value || '—';
export function badge(value) { const good = ['ACTIVE','ON_TIME','NORMAL','ACCEPTED','APPROVED','COMPLETED','ONLINE','CHECK_IN']; const bad = ['INACTIVE','REJECTED','LATE','EARLY_LEAVE','OFFLINE','DISCARDED','URGENT']; const warn=['PENDING','SUBMITTED','UNSCHEDULED','NEEDS_REVISION','HIGH']; return `<span class="badge ${good.includes(value)?'good':bad.includes(value)?'bad':warn.includes(value)?'warn':'neutral'}">${e(label(value))}</span>`; }
export const field = (name,title,value='',type='text',attrs='') => `<label class="field"><span>${e(title)}</span><input name="${e(name)}" type="${e(type)}" value="${e(value)}" ${attrs}></label>`;
export const textarea = (name,title,value='',attrs='') => `<label class="field span-2"><span>${e(title)}</span><textarea name="${e(name)}" rows="4" ${attrs}>${e(value)}</textarea></label>`;
export const select = (name,title,items,value='') => `<label class="field"><span>${e(title)}</span><select name="${e(name)}">${items.map(x=>`<option value="${e(x.value)}" ${String(x.value)===String(value)?'selected':''}>${e(x.label)}</option>`).join('')}</select></label>`;
export const button = (title,action,attrs='') => {const extra=attrs.match(/class="([^"]*)"/)?.[1]||'';return `<button type="button" class="btn ${e(extra)}" data-action="${e(action)}" ${attrs.replace(/class="[^"]*"/,'')}>${e(title)}</button>`;};
export function table(headers,rows,empty='Chưa có dữ liệu phù hợp.') {
  const last=headers.length-1;
  const actionIndex=['Thao tác','Đánh giá','Phiếu'].includes(headers[last])?last:-1;
  return `<div class="table-wrap"><table><thead><tr>${headers.map((title,index)=>`<th scope="col"${index===actionIndex?' class="action-cell"':''}>${e(title)}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.map(row=>`<tr>${row.map((cell,index)=>`<td${index===actionIndex?' class="action-cell"':''}>${cell??'—'}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${headers.length}" class="empty-cell">${e(empty)}</td></tr>`}</tbody></table></div>`;
}
export function person(name,sub='') { const initials=String(name||'?').split(/\s+/).filter(Boolean).slice(-2).map(x=>x[0]).join('').toUpperCase(); return `<span class="person"><span class="avatar">${e(initials)}</span><span><strong>${e(name||'Chưa đặt tên')}</strong>${sub?`<small>${e(sub)}</small>`:''}</span></span>`; }
export const money = value => new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:0}).format(Number(value)||0);
export function asDate(value) { if (!value) return null; const d = value.toDate?value.toDate():value instanceof Date?value:typeof value==='object'&&'seconds' in value?new Date(value.seconds*1000):new Date(value); return Number.isNaN(d.getTime())?null:d; }
export const dateLabel = value => asDate(value)?.toLocaleDateString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'}) || '—';
export const timeLabel = value => asDate(value)?.toLocaleTimeString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh',hour:'2-digit',minute:'2-digit',second:'2-digit'}) || '—';
export const dateTimeLabel = value => asDate(value)?`${dateLabel(value)} ${timeLabel(value)}`:'—';
export function today(now=new Date()) { const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Ho_Chi_Minh',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(now); const obj=Object.fromEntries(parts.map(p=>[p.type,p.value])); return `${obj.year}-${obj.month}-${obj.day}`; }
export const dateAdd = (date,days) => { const d=new Date(`${date}T12:00:00+07:00`); d.setUTCDate(d.getUTCDate()+days); return today(d); };
export const monthRange = month => { const [y,m]=month.split('-').map(Number); return {from:`${month}-01`,to:`${month}-${new Date(Date.UTC(y,m,0)).getUTCDate()}`}; };
export const emptyState = (title,body='') => `<div class="empty-state">${icon('file')}<h3>${e(title)}</h3><p>${e(body)}</p></div>`;
export const stat = (title,value,detail='',iconName='users') => `<article class="stat-card"><div class="stat-heading">${e(title)}<span class="stat-icon">${icon(iconName)}</span></div><strong class="stat-value">${e(value)}</strong><small>${e(detail)}</small></article>`;
export function csv(filename,headers,rows) { const cell=value=>{let s=String(value??''); if(/^[\s]*[=+@-]/.test(s))s="'"+s;return '"'+s.replace(/"/g,'""')+'"';}; const blob=new Blob(['\uFEFF'+[headers,...rows].map(r=>r.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000); }
export function errorMessage(error) { const code=error?.code||''; return ({'auth/invalid-credential':'Email hoặc mật khẩu chưa đúng.','auth/user-disabled':'Tài khoản đã bị vô hiệu hóa.','auth/too-many-requests':'Bạn thao tác quá nhanh. Vui lòng thử lại sau.','auth/network-request-failed':'Không thể kết nối. Kiểm tra kết nối mạng rồi thử lại.','auth/email-already-in-use':'Email đã được sử dụng.','auth/requires-recent-login':'Vui lòng đăng nhập lại để thực hiện thao tác này.','auth/unauthorized-domain':'Tên miền chưa được cho phép trong Firebase Authentication.','permission-denied':'Không đủ quyền thao tác. Kiểm tra tài khoản và Firestore Rules.','unavailable':'Không thể kết nối Firebase. Vui lòng thử lại.','failed-precondition':'Dữ liệu cần chỉ mục Firestore. Kiểm tra cấu hình index của dự án.'})[code]||error?.message||'Thao tác chưa thành công. Vui lòng thử lại.'; }
