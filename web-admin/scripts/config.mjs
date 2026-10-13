import {readFile,writeFile,access} from 'node:fs/promises';
import path from 'node:path';
export async function ensureConfig(root) {
  const target=path.join(root,'firebase-config.js');
  try{await access(target);return target;}catch{}
  try {
    const android=JSON.parse(await readFile(path.join(root,'..','app','google-services.json'),'utf8'));
    const apiKey=android.client?.find(c=>c.api_key?.[0]?.current_key)?.api_key[0].current_key;
    const project=android.project_info;if(!apiKey||!project?.project_id)throw new Error('Thiếu cấu hình client.');
    const config={apiKey,authDomain:`${project.project_id}.firebaseapp.com`,projectId:project.project_id,storageBucket:project.storage_bucket,messagingSenderId:project.project_number};
    await writeFile(target,'// Cấu hình Firebase client tạo từ app/google-services.json. Không chứa quyền Admin SDK.\nexport const firebaseConfig = '+JSON.stringify(config,null,2)+';\n');
    console.log('Đã tạo cấu hình Firebase client từ cấu hình Android.');
  } catch { await writeFile(target,'// Chưa tìm thấy cấu hình Android. Điền thông tin từ firebase-config.example.js.\nexport const firebaseConfig = {};\n');console.log('Chưa có cấu hình Firebase: có thể xem giao diện bằng chế độ dữ liệu mẫu.'); }
  return target;
}
