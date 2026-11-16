import importlib.util
import sys
import types
import unittest
from pathlib import Path

api = types.ModuleType("calamus_api")
api.Plugin = type("BasePlugin", (), {})
sys.modules["calamus_api"] = api
spec = importlib.util.spec_from_file_location("calamus_archive_under_test", Path(__file__).resolve().parents[1] / "plugins/calamus_archive/main.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Account:
    account_id = 0
    user_id = 123

    def __init__(self):
        self.values = {}
        self.records = []
        self.views = []
        self.presence_calls = []
        self.status = {"kind": "recently", "exact": False, "by_me": True}
        self.dialogs = []

    def get(self, key, default=None):
        return self.values.get(key, default)

    def set(self, key, value):
        self.values[key] = value

    def set_ghost(self, enabled):
        self.presence_calls.append(enabled)

    def archive_record(self, message):
        self.records.append(message.copy())

    def show_archive(self, dialog, **options):
        self.views.append((dialog, options))

    def presence(self, dialog):
        return self.status.copy()

    def show_dialog(self, title, text):
        self.dialogs.append((title, text))


class Context:
    def __init__(self):
        self.selected = Account()
        self.options = {"capture": True, "ghost": False}
        self.commands = {}
        self.actions = {}
        self.notices = []
        self.setting_reads = []

    def accounts(self):
        return [{"account_id": 0, "user_id": 123}]

    def account(self, account):
        return self.selected

    def has_permission(self, permission):
        return True

    def setting(self, key):
        self.setting_reads.append(key)
        return self.options[key]

    def register_command(self, name, title):
        self.commands[name] = title

    def register_message_action(self, name, title):
        self.actions[name] = title

    def notify(self, text):
        self.notices.append(text)

    def log(self, text):
        self.notices.append(text)


class ArchivePluginTests(unittest.TestCase):
    def setUp(self):
        self.context = Context()
        self.plugin = module.Plugin()
        self.plugin.on_load(self.context)
        self.chat = {"account_id": 0, "user_id": 123, "dialog_id": 42}
        self.message = {"id": 100, "dialog_id": 42, "archivable": True, "text": "Полученное сообщение"}

    def test_capture_and_context_action(self):
        self.plugin.on_event("message.received", {**self.chat, "messages": [self.message]})
        self.assertEqual(self.context.selected.records, [self.message])
        self.plugin.on_event("message.action", {**self.chat, "action": "history", "message": self.message})
        self.assertEqual(self.context.selected.views[-1], (42, {"message_id": 100}))
        self.assertEqual(self.context.actions["history"], "История изменений")

    def test_capture_pause_and_account_identity(self):
        self.context.options["capture"] = False
        self.plugin.on_event("settings.changed", {"key": "capture", "value": False})
        self.plugin.on_event("message.received", {**self.chat, "messages": [self.message]})
        self.context.options["capture"] = True
        self.plugin.on_event("settings.changed", {"key": "capture", "value": True})
        self.plugin.on_event("message.received", {**self.chat, "user_id": 456, "messages": [self.message]})
        self.plugin.on_event("message.received", {**self.chat, "messages": [{**self.message, "archivable": False}]})
        self.assertEqual(self.context.selected.records, [])

    def test_message_stream_does_not_request_settings_per_message(self):
        initial = list(self.context.setting_reads)
        for value in range(130):
            self.plugin.on_event("message.received", {**self.chat, "messages": [{**self.message, "id": 100 + value}]})
        self.assertEqual(len(self.context.selected.records), 130)
        self.assertEqual(self.context.setting_reads, initial)

    def test_exhausted_api_budget_does_not_crash_event_handler(self):
        def reject(*args, **kwargs):
            raise RuntimeError("SecurityException: Plugin API rate limit exceeded")
        original = self.context.selected.archive_record
        self.context.selected.archive_record = reject
        self.context.log = reject
        self.plugin.on_event("message.received", {**self.chat, "messages": [self.message]})
        accounts = self.context.accounts
        self.context.accounts = reject
        self.context.notify = reject
        self.plugin.on_event("app.foreground", {})
        self.context.accounts = accounts
        self.context.selected.archive_record = original
        self.plugin.on_event("message.received", {**self.chat, "messages": [self.message]})
        self.assertEqual(self.context.selected.records, [self.message])

    def test_ghost_is_disabled_by_default_and_persists(self):
        self.assertEqual(self.context.selected.presence_calls, [])
        self.plugin.on_command("ghost", "on", self.chat)
        self.assertTrue(self.context.selected.values["archive_ghost"])
        self.plugin.on_event("settings.changed", {"key": "ghost", "value": False})
        self.assertEqual(self.context.selected.presence_calls, [True, False])
        self.assertFalse(self.context.selected.values["archive_ghost"])

    def test_last_seen_never_invents_a_timestamp(self):
        self.plugin.on_command("lastseen", "", self.chat)
        text = self.context.selected.dialogs[-1][1]
        self.assertIn("недавно", text)
        self.assertIn("Premium", text)
        self.assertNotIn("Последний известный заход:", text)
        self.context.selected.status = {"kind": "offline", "exact": True, "timestamp": 100, "formatted": "15.11.2026 12:00"}
        self.plugin.on_command("lastseen", "", self.chat)
        self.assertEqual(self.context.selected.dialogs[-1][1], "Последний известный заход: 15.11.2026 12:00")

    def test_ui_rate_limit_does_not_stop_the_plugin(self):
        def reject(*args, **kwargs):
            raise RuntimeError("SecurityException: Plugin operation rate limit")
        self.context.selected.show_archive = reject
        self.plugin.on_command("archive", "", self.chat)
        self.plugin.on_event("message.action", {**self.chat, "action": "history", "message": self.message})
        self.assertEqual(self.context.notices[-1], "Подождите несколько секунд и повторите действие")

    def test_commands_and_bad_message_id(self):
        self.plugin.on_command("deleted", "", self.chat)
        self.assertEqual(self.context.selected.views[-1], (42, {"deleted_only": True}))
        self.plugin.on_command("history", "not-an-id", self.chat)
        self.assertEqual(len(self.context.selected.views), 1)
        self.assertIn(".history ID", self.context.notices[-1])


if __name__ == "__main__":
    unittest.main()
