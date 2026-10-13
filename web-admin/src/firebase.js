const sdkBase = 'https://www.gstatic.com/firebasejs/13.0.0';
export async function connectFirebase(config) {
  if (!config?.apiKey || !config?.projectId || config.apiKey.startsWith('YOUR_')) throw new Error('Chưa có cấu hình Firebase. Chạy npm start trong thư mục web-admin hoặc tạo firebase-config.js theo tệp mẫu.');
  const [appSdk, authSdk, fs] = await Promise.all([import(`${sdkBase}/firebase-app.js`),import(`${sdkBase}/firebase-auth.js`),import(`${sdkBase}/firebase-firestore.js`)]);
  const app = appSdk.initializeApp(config);
  const auth = authSdk.getAuth(app);
  await authSdk.setPersistence(auth, authSdk.browserSessionPersistence);
  const db = fs.getFirestore(app);
  const ref = (collection,id) => fs.doc(db,collection,id);
  const mapDoc = snapshot => snapshot.exists()?{...snapshot.data(),id:snapshot.id}:null;
  const queryRef = (name,options={}) => fs.query(fs.collection(db,name),
    ...(options.where||[]).map(([field,op,value])=>fs.where(field,op,value)),
    ...(options.orderBy||[]).map(([field,dir='asc'])=>fs.orderBy(field,dir)),
    ...(options.startAfter?[fs.startAfter(options.startAfter)]:[]),
    ...(options.limit?[fs.limit(options.limit)]:[]));
  const store = {
    id: name => fs.doc(fs.collection(db,name)).id,
    stamp: () => fs.serverTimestamp(), now: () => fs.Timestamp.now(), timestamp: value => fs.Timestamp.fromDate(value),
    get: async(name,id) => mapDoc(await fs.getDocFromServer(ref(name,id))),
    set: (name,id,data,options={}) => options.merge?fs.setDoc(ref(name,id),data,{merge:true}):fs.setDoc(ref(name,id),data),
    update: (name,id,data) => fs.updateDoc(ref(name,id),data),
    remove: (name,id) => fs.deleteDoc(ref(name,id)),
    add: async(name,data) => (await fs.addDoc(fs.collection(db,name),data)).id,
    async list(name,options={}) { const result=await fs.getDocsFromServer(queryRef(name,options)); return {rows:result.docs.map(mapDoc),cursor:result.docs.at(-1)||null,hasMore:!!options.limit&&result.size===options.limit}; },
    listen(name,options,onData,onError) { return fs.onSnapshot(queryRef(name,options),{includeMetadataChanges:true},result=>onData(result.docs.map(mapDoc),{fromCache:result.metadata.fromCache,hasMore:!!options.limit&&result.size===options.limit}),onError); },
    listenDoc(name,id,onData,onError) { return fs.onSnapshot(ref(name,id),snapshot=>onData(mapDoc(snapshot)),onError); },
    transaction: callback => fs.runTransaction(db, tx=>callback({
      get: async(name,id)=>mapDoc(await tx.get(ref(name,id))),
      set: (name,id,data,options={})=>options.merge?tx.set(ref(name,id),data,{merge:true}):tx.set(ref(name,id),data),
      update: (name,id,data)=>tx.update(ref(name,id),data), remove:(name,id)=>tx.delete(ref(name,id))
    })),
    async batch(operations) { if(operations.length>500)throw new Error('Vượt giới hạn 500 thay đổi trong một lần.');const batch=fs.writeBatch(db);for(const op of operations){const target=ref(op.collection,op.id);if(op.type==='delete')batch.delete(target);else if(op.type==='update')batch.update(target,op.data);else if(op.merge)batch.set(target,op.data,{merge:true});else batch.set(target,op.data);}await batch.commit(); },
    async createEmployeeAccount(email,password,profile) {
      const secondary=appSdk.initializeApp(config,`employee-provision-${crypto.randomUUID()}`);
      const employeeAuth=authSdk.getAuth(secondary); let created;
      try {
        await authSdk.setPersistence(employeeAuth,authSdk.inMemoryPersistence);
        created=(await authSdk.createUserWithEmailAndPassword(employeeAuth,email,password)).user;
        await store.transaction(async tx=>{const employee=await tx.get('employees',profile.employeeId);if(employee?.active!==true)throw new Error('Nhân viên đã ngừng hoạt động hoặc không tồn tại.');tx.update('employees',profile.employeeId,{accountVersion:Number(employee.accountVersion||0)+1});tx.set('users',created.uid,{...profile,uid:created.uid,email:email.trim(),role:'EMPLOYEE',active:true,updatedAt:store.stamp()});});
        return created.uid;
      } catch(error) { if(created){try{await authSdk.deleteUser(created);}catch{throw new Error('Chưa lưu được quyền tài khoản và chưa dọn được tài khoản Auth vừa tạo. Kiểm tra email này trong Firebase Console trước khi tạo lại.');}}throw error; }
      finally { await authSdk.signOut(employeeAuth);await appSdk.deleteApp(secondary); }
    }
  };
  return {store,observe:callback=>authSdk.onAuthStateChanged(auth,callback),signIn:(email,password)=>authSdk.signInWithEmailAndPassword(auth,email.trim(),password),signOut:()=>authSdk.signOut(auth),reset:email=>authSdk.sendPasswordResetEmail(auth,email.trim()),changePassword:password=>authSdk.updatePassword(auth.currentUser,password)};
}
