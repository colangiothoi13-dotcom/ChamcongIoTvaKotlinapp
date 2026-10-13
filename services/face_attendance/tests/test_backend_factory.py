import os
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from face_attendance.backend import create_firestore_client


class FirebaseClientProjectTests(unittest.TestCase):
    def setUp(self):
        self.apps = {}
        self.admin = ModuleType("firebase_admin")
        self.admin.firestore = SimpleNamespace(client=lambda app: app.project)

        def get_app(name):
            if name not in self.apps:
                raise ValueError("Unknown app")
            return self.apps[name]

        def initialize_app(*, options, name):
            app = SimpleNamespace(project=options["projectId"])
            self.apps[name] = app
            return app

        self.admin.get_app = Mock(side_effect=get_app)
        self.admin.initialize_app = Mock(side_effect=initialize_app)

    def test_switching_project_does_not_reuse_another_projects_admin_client(self):
        with patch.dict(os.environ, {}, clear=True), patch.dict("sys.modules", {"firebase_admin": self.admin}):
            self.assertEqual(create_firestore_client("demo-project-a"), "demo-project-a")
            self.assertEqual(create_firestore_client("demo-project-b"), "demo-project-b")
            self.assertEqual(create_firestore_client("demo-project-a"), "demo-project-a")
        self.assertEqual(len(self.apps), 2)
        self.assertEqual(self.admin.initialize_app.call_count, 2)

    def test_same_project_reuses_its_own_app(self):
        with patch.dict(os.environ, {}, clear=True), patch.dict("sys.modules", {"firebase_admin": self.admin}):
            self.assertEqual(create_firestore_client("demo-one-project"), "demo-one-project")
            self.assertEqual(create_firestore_client("demo-one-project"), "demo-one-project")
        self.assertEqual(self.admin.initialize_app.call_count, 1)

    def test_missing_admin_credentials_reports_actionable_error_without_sdk_details(self):
        credential_error = type("DefaultCredentialsError", (Exception,), {"__module__": "google.auth.exceptions"})
        self.admin.firestore.client = Mock(side_effect=credential_error("private credential path and remote URL"))
        with patch.dict(os.environ, {}, clear=True), patch.dict("sys.modules", {"firebase_admin": self.admin}):
            with self.assertRaisesRegex(RuntimeError, "khóa Admin SDK") as caught:
                create_firestore_client("demo-setup-error")
        self.assertNotIn("private credential path", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
