import json
from pathlib import Path
import tempfile
import threading
import unittest
import sqlite3
from unittest.mock import Mock, patch
from urllib import request, error

from face_attendance.server import make_server, _sync_loop
from face_attendance.storage import DemoBackend, Gateway, Outbox
from test_gateway import REGISTRY, event


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        p=Path(self.directory.name)
        gateway=Gateway(Outbox(p/"outbox.sqlite"),DemoBackend(p/"demo.sqlite",REGISTRY),
                        station_id="GATE-01",device_id="GATE-01")
        self.gateway=gateway
        self.admin="test-admin-"+"a"*32
        self.device="test-device-"+"b"*32
        self.server=make_server(gateway,"127.0.0.1",0,self.admin,self.device)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.addCleanup(self.close)
        self.url=f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def post(self,path,body,headers):
        req=request.Request(self.url+path,data=json.dumps(body).encode(),headers=headers)
        try:
            with request.urlopen(req,timeout=3) as res:
                return res.status,json.load(res)
        except error.HTTPError as exc:
            return exc.code,json.load(exc)

    def test_auth_and_no_cross_method_device_token(self):
        p=event("FACE","http-face").payload
        self.assertEqual(401,self.post("/v1/face",p,{"X-Device-Token":self.device})[0])
        code,result=self.post("/v1/face",p,{"X-Admin-Token":self.admin})
        self.assertEqual(200,code)
        self.assertEqual("ACCEPTED_DEMO",result["status"])
        self.assertNotEqual("ACCEPTED_SYNCED",result["status"])

    def test_fingerprint_and_face_share_same_attendance(self):
        _,a=self.post("/v1/fingerprint",event("FINGERPRINT","finger").payload,{"X-Device-Token":self.device})
        _,b=self.post("/v1/face",event("FACE","face",1).payload,{"X-Admin-Token":self.admin})
        self.assertEqual(a["attendanceId"],b["attendanceId"])

    def test_payload_conflict_returns_rejection_not_ack(self):
        self.post("/v1/fingerprint",event("FINGERPRINT","conflict").payload,{"X-Device-Token":self.device})
        code,r=self.post("/v1/fingerprint",event("FINGERPRINT","conflict",1).payload,{"X-Device-Token":self.device})
        self.assertEqual(409,code)
        self.assertEqual("REJECTED",r["status"])

    def test_health_has_mode_without_tokens(self):
        with request.urlopen(self.url+"/health",timeout=3) as res:
            data=json.load(res)
        self.assertEqual("demo",data["mode"])
        self.assertNotIn("Token",json.dumps(data))

    def test_sqlite_write_failure_returns_503_and_same_event_retries_once(self):
        original_put=self.gateway.outbox.put
        source=event("FACE","storage-retry")
        attempts=iter((False,True))

        def transient_put(received):
            if not next(attempts):
                raise sqlite3.OperationalError("simulated database full")
            return original_put(received)

        with patch.object(self.gateway.outbox,"put",side_effect=transient_put):
            code,result=self.post("/v1/face",source.payload,{"X-Admin-Token":self.admin})
            self.assertEqual(code,503)
            self.assertEqual(result,{"status":"QUEUED","reason":"NOT_STORED","sourceEventId":"storage-retry"})
            code,accepted=self.post("/v1/face",source.payload,{"X-Admin-Token":self.admin})
        self.assertEqual(code,200)
        self.assertEqual(accepted["status"],"ACCEPTED_DEMO")
        self.assertEqual(self.gateway.backend.count(),1)

    def test_sync_worker_survives_sqlite_failure_and_retries(self):
        gateway=Mock()
        gateway.flush.side_effect=[sqlite3.OperationalError("database locked"),[]]
        stop=Mock()
        stop.wait.side_effect=[False,False,True]
        with self.assertLogs("face_attendance.server",level="WARNING"):
            _sync_loop(gateway,stop,interval=0)
        self.assertEqual(gateway.flush.call_count,2)
