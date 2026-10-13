import assert from 'node:assert/strict';
import { collections } from '../src/demo.js';

const PROJECT = 'demo-web-admin-integration';
const STAMP = Object.freeze({ __webRuleTestServerTimestamp: true });
class EmulatorTimestamp {
  constructor(iso) { this.iso = iso; }
  toDate() { return new Date(this.iso); }
  toMillis() { return this.toDate().getTime(); }
}

export function assertEmulatorHost(host) {
  const match = /^(127\.0\.0\.1|localhost|\[::1\]):([1-9]\d{0,4})$/.exec(host || '');
  if (!match || Number(match[2]) > 65535) throw new Error('Rule integration tests require a loopback Firestore emulator host; production and remote hosts are forbidden.');
  return host;
}

const encoded = value => Buffer.from(JSON.stringify(value)).toString('base64url');
function token(uid, provider = 'password') {
  const now = Math.floor(Date.now() / 1000);
  return `${encoded({ alg: 'none', typ: 'JWT' })}.${encoded({ iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT,
    sub: uid, user_id: uid, iat: now, exp: now + 3600, auth_time: now,
    firebase: { sign_in_provider: provider, identities: {} } })}.`;
}
const fieldPath = keys => keys.map(key => '`' + key.replace(/\\/g, '\\\\').replace(/`/g, '\\`') + '`').join('.');
function valueOf(value, path, transforms) {
  if (value?.__webRuleTestServerTimestamp === true) {
    transforms.push({ fieldPath: fieldPath(path), setToServerValue: 'REQUEST_TIME' });
    return undefined;
  }
  if (value === null) return { nullValue: null };
  if (value instanceof EmulatorTimestamp) return { timestampValue: value.iso };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (typeof value?.toDate === 'function') return { timestampValue: value.toDate().toISOString() };
  if (typeof value === 'string') return { stringValue: value };
  if (typeof value === 'boolean') return { booleanValue: value };
  if (typeof value === 'number') {
    assert.ok(Number.isFinite(value), `non-finite number at ${path.join('.')}`);
    return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  }
  if (Array.isArray(value)) return { arrayValue: { values: value.map((item, index) => {
    assert.notEqual(item?.__webRuleTestServerTimestamp, true, 'serverTimestamp cannot appear in an array');
    return valueOf(item, [...path, String(index)], transforms);
  }) } };
  assert.ok(value && typeof value === 'object', `undefined field at ${path.join('.')}`);
  return { mapValue: { fields: encodeFields(value, path, transforms) } };
}
function encodeFields(data, prefix = [], transforms = []) {
  const fields = {};
  for (const [key, item] of Object.entries(data)) {
    const encodedValue = valueOf(item, [...prefix, key], transforms);
    if (encodedValue !== undefined) fields[key] = encodedValue;
  }
  return fields;
}
function decode(value) {
  if ('nullValue' in value) return null;
  if ('stringValue' in value) return value.stringValue;
  if ('booleanValue' in value) return value.booleanValue;
  if ('integerValue' in value) return Number(value.integerValue);
  if ('doubleValue' in value) return value.doubleValue;
  if ('timestampValue' in value) return new EmulatorTimestamp(value.timestampValue);
  if ('arrayValue' in value) return (value.arrayValue.values || []).map(decode);
  if ('mapValue' in value) return decodeFields(value.mapValue.fields || {});
  throw new Error(`Unsupported test Firestore value: ${JSON.stringify(value)}`);
}
const decodeFields = fields => Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, decode(value)]));
const docData = document => ({ ...decodeFields(document.fields || {}), id: document.name.split('/').at(-1) });

export function createHarness(host = process.env.WEB_FIRESTORE_EMULATOR_HOST) {
  assertEmulatorHost(host);
  const database = `projects/${PROJECT}/databases/(default)`;
  const documents = `http://${host}/v1/${database}/documents`;
  const name = (collection, id) => `${database}/documents/${collection}/${id}`;
  let ids = 0;
  async function request(url, auth, body, method = 'POST') {
    const response = await fetch(url, { method, headers: { Authorization: `Bearer ${auth}`, 'Content-Type': 'application/json' },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(30000) });
    const text = await response.text();
    if (!response.ok) {
      const error = new Error(`Firestore HTTP ${response.status}: ${text}`);
      error.status = response.status;
      try { error.code = JSON.parse(text).error?.status; } catch { /* Keep non-JSON failures readable. */ }
      throw error;
    }
    return text ? JSON.parse(text) : {};
  }
  function write(op) {
    if (op.type === 'delete') return { delete: name(op.collection, op.id) };
    const transforms = [];
    const fields = encodeFields(op.data, [], transforms);
    return { update: { name: name(op.collection, op.id), fields },
      ...(op.type === 'update' || op.merge ? { updateMask: { fieldPaths: Object.keys(op.data).map(key => fieldPath([key])) } } : {}),
      ...(op.type === 'update' ? { currentDocument: { exists: true } } : {}),
      ...(transforms.length ? { updateTransforms: transforms } : {}) };
  }
  function storeFor(uid, provider = 'password') {
    const auth = uid === 'owner' ? 'owner' : token(uid, provider);
    const store = {
      id: () => `web-rule-${process.pid}-${Date.now()}-${++ids}`,
      stamp: () => STAMP, now: () => new Date(), timestamp: date => date,
      async get(collection, id) {
        try { return docData(await request(`${documents}/${collection}/${id}`, auth, undefined, 'GET')); }
        catch (error) { if (error.status === 404) return null; throw error; }
      },
      set: (collection, id, data, options = {}) => store.batch([{ type: 'set', collection, id, data, merge: options.merge }]),
      update: (collection, id, data) => store.batch([{ type: 'update', collection, id, data }]),
      remove: (collection, id) => store.batch([{ type: 'delete', collection, id }]),
      async add(collection, data) { const id = store.id(collection); await store.set(collection, id, data); return id; },
      batch: operations => request(`${documents}:commit`, auth, { writes: operations.map(write) }),
      async list(collection, options = {}) {
        const parts = collection.split('/');
        const collectionId = parts.pop();
        const parent = parts.length ? `/${parts.join('/')}` : '';
        const filters = (options.where || []).map(([field, op, value]) => ({ fieldFilter: { field: { fieldPath: field },
          op: { '==': 'EQUAL', '>=': 'GREATER_THAN_OR_EQUAL', '<=': 'LESS_THAN_OR_EQUAL', '>': 'GREATER_THAN', '<': 'LESS_THAN' }[op], value: valueOf(value, [], []) } }));
        const ordering = [...(options.orderBy || [])];
        if (!ordering.some(([field]) => field === '__name__')) ordering.push(['__name__', ordering.at(-1)?.[1] || 'asc']);
        const cursorValue = field => field === '__name__' ? { referenceValue: options.startAfter.name }
          : field.split('.').reduce((value, key, index) => index === 0 ? options.startAfter.fields[key] : value?.mapValue?.fields[key], null);
        const response = await request(`${documents}${parent}:runQuery`, auth, { structuredQuery: { from: [{ collectionId }],
          ...(filters.length ? { where: filters.length === 1 ? filters[0] : { compositeFilter: { op: 'AND', filters } } } : {}),
          orderBy: ordering.map(([field, direction]) => ({ field: { fieldPath: field }, direction: direction === 'desc' ? 'DESCENDING' : 'ASCENDING' })),
          ...(options.startAfter ? { startAt: { before: false, values: ordering.map(([field]) => cursorValue(field)) } } : {}),
          ...(options.limit ? { limit: options.limit } : {}) } });
        const found = response.filter(item => item.document).map(item => item.document);
        const rows = found.map(docData);
        const last = found.at(-1);
        return { rows, cursor: last ? { name: last.name, fields: last.fields || {} } : null, hasMore: Boolean(options.limit && rows.length === options.limit) };
      },
      async transaction(callback) {
        // The production adapter uses Firebase runTransaction, which retries
        // aborted transactions. Concurrent REST transactions can both lose a
        // lock upgrade; evaluate the callback again from fresh reads, up to
        // the SDK's default five attempts. Do not retry rule denials or errors
        // raised by application validation.
        for (let attempt = 0; attempt < 5; attempt++) {
          let transaction;
          try {
            ({ transaction } = await request(`${documents}:beginTransaction`, auth, {}));
            const pending = [];
            const tx = {
              async get(collection, id) {
                assert.equal(pending.length, 0, 'Firestore transaction reads must precede writes');
                const response = await request(`${documents}:batchGet`, auth, { documents: [name(collection, id)], transaction });
                return response[0]?.found ? docData(response[0].found) : null;
              },
              set: (collection, id, data, options = {}) => pending.push({ type: 'set', collection, id, data, merge: options.merge }),
              update: (collection, id, data) => pending.push({ type: 'update', collection, id, data }),
              remove: (collection, id) => pending.push({ type: 'delete', collection, id }),
            };
            const result = await callback(tx);
            await request(`${documents}:commit`, auth, { transaction, writes: pending.map(write) });
            return result;
          } catch (error) {
            if (transaction) await request(`${documents}:rollback`, auth, { transaction }).catch(() => {});
            if (error.status !== 409 || error.code !== 'ABORTED' || attempt === 4) throw error;
            const backoffMs = Math.min(1000, 50 * 2 ** attempt) + Math.floor(Math.random() * 100);
            await new Promise(resolve => setTimeout(resolve, backoffMs));
          }
        }
      },
    };
    return store;
  }
  const owner = storeFor('owner');
  return { owner, storeFor,
    seed: (collection, id, data) => owner.set(collection, id, data),
    async context(uid, role = 'ADMIN', employeeId = null) {
      const user = { uid, email: `${uid}@example.test`, displayName: `Quản trị ${uid}`, role, active: true, employeeId };
      await owner.set('users', uid, user);
      const store = storeFor(uid);
      const ctx = { data: Object.fromEntries(collections.map(collection => [collection, []])), store, user, filters: {}, loaded: {},
        ui: { toast() {}, dialog(options) { ctx.dialog = options; }, confirm(title, body, callback) { ctx.confirm = callback; } }, render() {},
        audit: (action, targetType, targetId, details, reason = '') => ({ actorId: uid, actorName: user.displayName, action, targetType, targetId, details, reason, createdAt: store.stamp() }),
      };
      return ctx;
    },
  };
}
