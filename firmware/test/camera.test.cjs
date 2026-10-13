const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '..', 'esp32_cam', 'esp32_cam.ino'), 'utf8');
const boardHeader = fs.readFileSync(path.join(__dirname, '..', 'esp32_cam', 'camera_board.h'), 'utf8');

function functionSource(name) {
  const start = source.search(new RegExp(`\\b(?:bool|esp_err_t|void|const char\\*) ${name}\\([^)]*\\)\\s*\\{`));
  assert.notEqual(start, -1, `${name} exists`);
  let end = source.indexOf('{', start), depth = 1;
  while (depth && ++end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end + 1);
}

// Production request decisions are executed against an HTTP/camera fake.
// This verifies auth, image ownership and freshness sequencing; electrical
// behavior, camera DMA and Arduino core compilation still need the board/toolchain.
function adapt(input) {
  return input
    .replace(/^(?:bool|esp_err_t|void|const char\*) (\w+)\(([^)]*)\)/, (_, name, parameters) =>
      `function ${name}(${parameters.replace(/\b(?:const\s+)?(?:httpd_req_t\*|char\*)\s*/g, '')})`)
    .replace(/\bconst\s+(?:size_t|time_t|esp_err_t|bool)\s+/g, 'const ')
    .replace(/camera_config_t (\w+) = \{\};/g, 'let $1 = {};')
    .replace(/sensor_t\* (\w+) =/g, 'let $1 =')
    .replace(/esp_camera_init\(&config\)/g, 'esp_camera_init(config)')
    .replace(/\buint8_t (\w+) =/g, 'let $1 =')
    .replace(/for \(size_t /g, 'for (let ')
    .replace(/char (\w+)\[\d+\](?: = \{\})?;/g, "let $1 = ''; ")
    .replace(/camera_fb_t\* (\w+) =/g, 'let $1 =')
    .replace(/struct tm (\w+);/g, 'let $1 = {};')
    .replace(/static_cast<uint8_t>\(supplied\[i\] \^ CAMERA_ACCESS_TOKEN\[i\]\)/g,
      '(supplied.charCodeAt(i) ^ CAMERA_ACCESS_TOKEN.charCodeAt(i))')
    .replace(/static_cast<unsigned long long>\(([^)]+)\)/g, '$1')
    .replace(/reinterpret_cast<const char\*>\(([^)]+)\)/g, '$1')
    .replace(/->/g, '.')
    .replace(/httpd_req_get_hdr_value_str\(request, "X-Camera-Token", supplied, sizeof\(supplied\)\)/g,
      'httpd_req_get_hdr_value_str(request, "X-Camera-Token", value => { supplied = value; }, 121)')
    .replace(/snprintf\(frameId, sizeof\(frameId\), "%s-%llu", bootNonce, \+\+frameSequence\);/g,
      'frameId = `${bootNonce}-${++frameSequence}`;')
    .replace(/gmtime_r\(&captured, &utc\);/g, 'utc = new Date(captured * 1000);')
    .replace(/strftime\(timestamp, sizeof\(timestamp\), "%Y-%m-%dT%H:%M:%SZ", &utc\);/g,
      "timestamp = utc.toISOString().replace('.000', '');")
    .replace(/\bnullptr\b/g, 'null');
}

function fixture(profile = 'S3_N16R8') {
  // Supply the production profile constants to the executed initialization code.
  const profileStart = boardHeader.indexOf(`#${profile === 'S3_N16R8' ? 'if' : 'elif'} CAMERA_BOARD_PROFILE == CAMERA_BOARD_${profile}`);
  assert.notEqual(profileStart, -1);
  const profileEnd = boardHeader.indexOf(profile === 'S3_N16R8' ? '#elif CAMERA_BOARD_PROFILE' : '#else\n#error "Unknown', profileStart);
  const constants = {};
  for (const match of boardHeader.slice(profileStart, profileEnd).matchAll(/constexpr (?:int|bool) (\w+) = (-?\d+|true|false);/g)) {
    constants[match[1]] = match[2] === 'true' ? true : match[2] === 'false' ? false : Number(match[2]);
  }
  const s = {
    ...constants,
    CAMERA_ACCESS_TOKEN: 'random-test-camera-token-32chars', CAMERA_DEVICE_ID: 'CAM-01',
    MIN_VALID_CAMERA_TIME: 1700000000, bootNonce: 'random-boot-nonce-1', frameSequence: 0,
    cameraMutex: {}, cameraReady: true, cameraSensorPid: 0, cameraLastError: 0,
    lockAllowed: true, unixTime: 1791594000, hasPsram: true, initializationResult: 0,
    OV2640_PID: 0x26, OV3660_PID: 0x3660, OV5640_PID: 0x5640,
    ESP_FAIL: -1, ESP_ERR_NO_MEM: 257, OUTPUT: 'OUTPUT', LOW: 'LOW', pinOperations: [],
    LEDC_CHANNEL_0: 0, LEDC_TIMER_0: 0, FRAMESIZE_VGA: 'VGA', FRAMESIZE_QVGA: 'QVGA',
    CAMERA_FB_IN_PSRAM: 'PSRAM', CAMERA_FB_IN_DRAM: 'DRAM', CAMERA_GRAB_WHEN_EMPTY: 'WHEN_EMPTY',
    psramFound: () => s.hasPsram,
    pinMode(pin, mode) { s.pinOperations.push(['mode', pin, mode]); },
    digitalWrite(pin, value) { s.pinOperations.push(['write', pin, value]); },
    Serial: { println() {}, printf() {} },
    esp_camera_init(config) { s.initializedConfig = {...config}; return s.initializationResult; },
    esp_camera_sensor_get: () => ({id: {PID: s.OV5640_PID}}),
    ESP_OK: 0, pdTRUE: 1, HTTPD_RESP_USE_STRLEN: -1, PIXFORMAT_JPEG: 'JPEG',
    statuses: [], headers: {}, bodies: [], sensorReads: [], returned: [], releases: 0, deinitializations: 0,
    frames: [{ id: 'buffered-before-request', format: 'JPEG', buf: 'OLD', len: 3 },
      { id: 'fresh-after-request', format: 'JPEG', buf: 'FRESH', len: 5 }],
    strlen: x => x.length, strcmp: (a,b) => a === b ? 0 : 1,
    httpd_req_get_hdr_value_len: request => request.token?.length || 0,
    httpd_req_get_hdr_value_str(request, _name, set) { set(request.token); return 0; },
    httpd_resp_set_status(_req,status) { s.statuses.push(status); },
    httpd_resp_set_type(_req,type) { s.contentType = type; },
    httpd_resp_set_hdr(_req,name,value) { s.headers[name] = value; },
    httpd_resp_send(_req,body,len) { s.bodies.push({body,len}); return 0; },
    xSemaphoreTake: () => s.lockAllowed ? 1 : 0,
    xSemaphoreGive() { s.releases++; }, pdMS_TO_TICKS: x => x,
    esp_camera_fb_get() { const frame = s.frames.shift() || null; s.sensorReads.push(frame?.id || null); return frame; },
    esp_camera_fb_return(frame) { s.returned.push(frame.id); },
    esp_camera_deinit() { s.deinitializations++; }, time: () => s.unixTime,
  };
  const context = vm.createContext(s);
  s.run = text => vm.runInContext(text,context);
  for (const name of ['tokenConfigured','authorizeCameraRequest','cameraError','captureCamera',
    'cameraSensorName','initializeBoardOutputs','initializeCamera']) {
    s.run(adapt(functionSource(name)));
  }
  s.capture = token => { s.request = {token}; s.run('captureCamera(request)'); };
  return s;
}

test('camera rejects default/missing/wrong token before touching framebuffer', () => {
  for (const configure of [ s => {s.CAMERA_ACCESS_TOKEN = 'REPLACE_WITH_RANDOM_CAMERA_TOKEN';},
    s => {s.CAMERA_ACCESS_TOKEN = 'too-short';}, s => {}, s => {s.CAMERA_ACCESS_TOKEN = 'x'.repeat(121);} ]) {
    const s = fixture(); configure(s); s.capture('wrong');
    assert.deepEqual(s.sensorReads,[]); assert.equal(s.statuses.at(-1),'401 Unauthorized');
    assert.equal(s.headers['Cache-Control'],'no-store');
  }
  const s = fixture();
  for (const token of [undefined, s.CAMERA_ACCESS_TOKEN.replace('random','xxxxxx')]) {
    s.capture(token); assert.equal(s.statuses.at(-1),'401 Unauthorized');
  }
  assert.equal(s.releases,0);
});

test('S3 missing OPI PSRAM refuses camera initialization rather than reporting a working camera', () => {
  const s = fixture(); s.hasPsram = false;
  assert.equal(s.run('initializeCamera()'), false);
  assert.equal(s.initializedConfig, undefined);
  assert.equal(s.cameraReady, false); assert.equal(s.cameraSensorPid, 0);
  assert.equal(s.cameraLastError, s.ESP_ERR_NO_MEM); assert.equal(s.releases, 1);
});

test('S3 OV5640 probe uses PSRAM JPEG and never drives SCCB SDA as a flash output', () => {
  const s = fixture(); s.run('initializeBoardOutputs()');
  assert.deepEqual(s.pinOperations, []);
  assert.equal(s.run('initializeCamera()'), true);
  assert.equal(s.initializedConfig.pin_sccb_sda, s.CAMERA_PIN_SDA);
  assert.equal(s.initializedConfig.pixel_format, 'JPEG');
  assert.equal(s.initializedConfig.frame_size, 'VGA'); assert.equal(s.initializedConfig.fb_location, 'PSRAM');
  assert.equal(s.cameraSensorPid, 0x5640); assert.equal(s.run('cameraSensorName()'), 'OV5640');
  assert.equal(s.cameraLastError, 0); assert.equal(s.releases, 1);
});

test('classic board still turns its flash off and supports QVGA DRAM without PSRAM', () => {
  const s = fixture('AI_THINKER'); s.hasPsram = false;
  s.run('initializeBoardOutputs()');
  assert.deepEqual(s.pinOperations, [['mode', 4, 'OUTPUT'], ['write', 4, 'LOW']]);
  assert.equal(s.run('initializeCamera()'), true);
  assert.equal(s.initializedConfig.frame_size, 'QVGA'); assert.equal(s.initializedConfig.fb_location, 'DRAM');
  assert.equal(s.initializedConfig.pin_sccb_sda, 26);
});

test('sensor probe failure releases camera lock, records error and permits later recovery', () => {
  const s = fixture(); s.initializationResult = 0x105;
  assert.equal(s.run('initializeCamera()'), false);
  assert.equal(s.cameraReady, false); assert.equal(s.cameraSensorPid, 0);
  assert.equal(s.cameraLastError, 0x105); assert.equal(s.deinitializations, 1); assert.equal(s.releases, 1);
  s.initializationResult = 0; assert.equal(s.run('initializeCamera()'), true);
  assert.equal(s.cameraLastError, 0); assert.equal(s.cameraReady, true); assert.equal(s.releases, 2);
});

test('camera capture discards buffered image, sends only fresh JPEG, and returns owned frames', () => {
  const s = fixture(); s.capture(s.CAMERA_ACCESS_TOKEN);
  assert.deepEqual(s.sensorReads,['buffered-before-request','fresh-after-request']);
  assert.deepEqual(s.returned,['buffered-before-request','fresh-after-request']);
  assert.deepEqual(s.bodies,[{body:'FRESH',len:5}]); assert.equal(s.releases,1);
  assert.equal(s.contentType,'image/jpeg'); assert.equal(s.headers['Cache-Control'],'no-store');
  assert.equal(s.headers['X-Frame-Id'],'random-boot-nonce-1-1');
  assert.equal(s.headers['X-Camera-Id'],'CAM-01');
  assert.equal(s.headers['X-Frame-Captured-At'],new Date(s.unixTime*1000).toISOString().replace('.000',''));
});

test('fresh frame ID changes across requests and restart; absent NTP is not a made-up timestamp', () => {
  const s = fixture(); s.unixTime = 0; s.capture(s.CAMERA_ACCESS_TOKEN);
  assert.equal(s.headers['X-Frame-Captured-At'],undefined);
  const firstId = s.headers['X-Frame-Id'];
  s.frames = [{id:'buffered',format:'JPEG',buf:'OLD',len:3},{id:'new',format:'JPEG',buf:'NEW',len:3}];
  s.capture(s.CAMERA_ACCESS_TOKEN);
  assert.notEqual(s.headers['X-Frame-Id'],firstId);
  s.bootNonce = 'new-boot-nonce-2'; s.frameSequence = 0;
  s.frames = [{id:'buffered',format:'JPEG',buf:'OLD',len:3},{id:'new',format:'JPEG',buf:'NEW',len:3}];
  s.capture(s.CAMERA_ACCESS_TOKEN);
  assert.equal(s.headers['X-Frame-Id'],'new-boot-nonce-2-1');
  assert.notEqual(s.headers['X-Frame-Id'],firstId);
});

test('busy or unavailable camera returns 503 without claiming a fresh image', () => {
  for (const configure of [s => {s.lockAllowed = false;},s => {s.cameraReady = false;}]) {
    const s = fixture(); configure(s); s.capture(s.CAMERA_ACCESS_TOKEN);
    assert.equal(s.statuses.at(-1),'503 Service Unavailable'); assert.equal(s.sensorReads.length,0);
    assert.equal(s.headers['X-Frame-Id'],undefined);
  }
});

test('framebuffer error releases lock, requests camera recovery and emits no frame ID', () => {
  for (const frames of [[],[{id:'old',format:'JPEG',buf:'OLD',len:3}],
    [{id:'old',format:'JPEG',buf:'OLD',len:3},{id:'bad',format:'RGB',buf:'RGB',len:3}]]) {
    const s = fixture(); s.frames = frames; s.capture(s.CAMERA_ACCESS_TOKEN);
    assert.equal(s.statuses.at(-1),'503 Service Unavailable'); assert.equal(s.cameraReady,false);
    assert.equal(s.releases,1); assert.equal(s.deinitializations,1); assert.equal(s.headers['X-Frame-Id'],undefined);
  }
});
