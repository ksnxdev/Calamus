import sys
import types
import json
import importlib
import importlib.machinery
import importlib.util
import os
import _calamus

_config = json.loads(_config_json)

class NativeModules:
    def find_spec(self, fullname, path=None, target=None):
        filename = _config["native_modules"].get(fullname)
        if filename is None:
            return None
        loader = importlib.machinery.ExtensionFileLoader(fullname, _config["native_dir"] + "/" + filename)
        return importlib.util.spec_from_loader(fullname, loader)

sys.meta_path.insert(0, NativeModules())
for _name in _config["native_modules"]:
    importlib.import_module(_name)

import io
import zipfile
import builtins
from importlib.resources.abc import Traversable
import asyncio
_package = zipfile.ZipFile(io.BytesIO(_archive_bytes))
_loop = asyncio.new_event_loop()
asyncio.set_event_loop(_loop)

class Output:
    def write(self, text):
        return len(text)
    def flush(self):
        return None

sys.stdout = Output()
sys.stderr = Output()
API_VERSION = 3

def request(operation, **arguments):
    response = json.loads(_calamus.request(json.dumps({"operation": operation, "arguments": arguments}, ensure_ascii=True)))
    if response.get("error"):
        raise RuntimeError(response["error"])
    return response.get("result")

class Plugin:
    def on_load(self, context):
        self.context = context
    def on_unload(self):
        pass
    def on_event(self, name, payload):
        pass
    def on_command(self, name, arguments, chat):
        pass


class Context:
    def has_permission(self, permission):
        return permission in _config["permissions"]
    def get(self, key, default=None):
        return request("store.get", key=key, default=default)
    def set(self, key, value):
        return request("store.set", key=key, value=value)
    def setting(self, key):
        return request("setting.get", key=key)
    def log(self, text):
        return request("log", text=str(text)[:500])
    def notify(self, text):
        return request("notify", text=str(text)[:200])
    def register_command(self, name, title):
        return request("command.register", name=name, title=title)
    def register_message_action(self, name, title):
        return request("message.action.register", name=name, title=title)
    def accounts(self):
        return request("accounts")
    def account(self, account_id):
        account = next((a for a in self.accounts() if a["account_id"] == account_id), None)
        if account is None:
            raise RuntimeError("Account access is unavailable")
        return AccountContext(account_id, account["user_id"])


class AccountContext:
    def __init__(self, account_id, user_id):
        self.account_id = account_id
        self.user_id = user_id
    def _request(self, operation, **arguments):
        return request(operation, account_id=self.account_id, user_id=self.user_id, **arguments)
    def set_ghost(self, enabled):
        return self._request("presence.set", enabled=bool(enabled))
    def presence(self, dialog_id):
        return self._request("presence.get", dialog_id=dialog_id)
    def archive_record(self, message):
        return self._request("archive.record", dialog_id=message["dialog_id"], message=message)
    def archive_query(self, dialog_id, **options):
        return self._request("archive.query", dialog_id=dialog_id, **options)
    def show_archive(self, dialog_id, message_id=0, deleted_only=False):
        return self._request("archive.show", dialog_id=dialog_id, message_id=message_id, deleted_only=bool(deleted_only))
    def chats(self, limit=50):
        return self._request("chats", limit=limit)
    def history(self, dialog_id, limit=20, offset_id=0):
        return self._request("history", dialog_id=dialog_id, limit=limit, offset_id=offset_id)
    def send_message(self, dialog_id, text, reply_to=0):
        return self._request("send_message", dialog_id=dialog_id, text=text, reply_to=reply_to)
    def open_chat(self, dialog_id):
        return self._request("open_chat", dialog_id=dialog_id)
    def show_dialog(self, title, text):
        return self._request("show_dialog", title=title, text=text)
    def get(self, key, default=None):
        self._request("validate_account")
        return request("store.get", key=f"account:{self.user_id}:{key}", default=default)
    def set(self, key, value):
        self._request("validate_account")
        return request("store.set", key=f"account:{self.user_id}:{key}", value=value)


api = types.ModuleType("calamus_api")
api.Plugin = Plugin
api.API_VERSION = API_VERSION
sys.modules["calamus_api"] = api
class PackagePath(Traversable):
    def __init__(self, path=""):
        self.path = path.strip("/")
    @property
    def name(self):
        return self.path.rsplit("/", 1)[-1]
    def is_file(self):
        return self.path in _package.namelist() and not self.path.endswith("/")
    def is_dir(self):
        prefix = self.path + "/" if self.path else ""
        return any(name.startswith(prefix) for name in _package.namelist()) and not self.is_file()
    def iterdir(self):
        prefix = self.path + "/" if self.path else ""
        children = {name[len(prefix):].split("/", 1)[0] for name in _package.namelist() if name.startswith(prefix) and name != prefix}
        return iter(PackagePath(prefix + name) for name in sorted(children))
    def joinpath(self, *parts):
        return PackagePath("/".join((self.path, *map(str, parts))))
    def open(self, mode="r", *args, **kwargs):
        return package_open("/plugin/" + self.path, mode, *args, **kwargs)

class PackageReader:
    def __init__(self, prefix):
        self.prefix = prefix
    def files(self):
        return PackagePath(self.prefix)
    def open_resource(self, name):
        return self.files().joinpath(name).open("rb")
    def resource_path(self, name):
        raise FileNotFoundError("Resources have no host filesystem path")
    def is_resource(self, name):
        return self.files().joinpath(name).is_file()
    def contents(self):
        return (child.name for child in self.files().iterdir())

class PackageModules:
    def find_spec(self, fullname, path=None, target=None):
        if not fullname.startswith("calamus_plugin."):
            return None
        relative = fullname[len("calamus_plugin."):].replace(".", "/")
        for filename, package in [(relative + "/__init__.py", True), (relative + ".py", False)]:
            if filename in _package.namelist():
                spec = importlib.util.spec_from_loader(fullname, self, is_package=package)
                spec.origin = "/plugin/" + filename
                return spec
        return None
    def create_module(self, spec):
        return None
    def exec_module(self, module):
        filename = module.__spec__.origin
        module.__file__ = filename
        if module.__spec__.submodule_search_locations is not None:
            module.__path__ = [filename.rsplit("/", 1)[0]]
        exec(compile(_package.read(filename[len("/plugin/"):]), filename, "exec"), module.__dict__)
    def get_resource_reader(self, fullname):
        prefix = "" if fullname == "calamus_plugin" else fullname[len("calamus_plugin."):].replace(".", "/")
        return PackageReader(prefix)
    def get_data(self, path):
        if not path.startswith("/plugin/"):
            raise OSError("Only package resources are available")
        return _package.read(path[len("/plugin/"):])

_package_modules = PackageModules()
sys.meta_path.insert(0, _package_modules)
_open = builtins.open

def package_open(file, mode="r", buffering=-1, encoding=None, errors=None, newline=None, closefd=True, opener=None):
    path = os.fspath(file) if not isinstance(file, int) else None
    if isinstance(path, str) and path.startswith("/plugin/"):
        if mode not in ("r", "rb", "rt"):
            raise PermissionError("Package resources are read-only")
        content = _package.read(path[len("/plugin/"):])
        if "b" in mode:
            return io.BytesIO(content)
        return io.StringIO(content.decode("utf-8" if encoding in (None, "locale") else encoding, errors or "strict"), newline=newline)
    return _open(file, mode, buffering, encoding, errors, newline, closefd, opener)

builtins.open = package_open
io.open = package_open
instance = None

def dispatch(operation, raw_argument):
    global instance
    argument = json.loads(raw_argument)
    if operation == "load":
        root = types.ModuleType("calamus_plugin")
        root.__path__ = ["/plugin"]
        root.__file__ = "/plugin/__init__.py"
        root.__loader__ = _package_modules
        root.__spec__ = importlib.util.spec_from_loader(root.__name__, _package_modules, is_package=True)
        sys.modules[root.__name__] = root
        module = importlib.import_module("calamus_plugin.main")
        if not isinstance(module.Plugin, type) or not issubclass(module.Plugin, Plugin):
            raise TypeError("main.py must export a calamus_api.Plugin subclass")
        instance = module.Plugin()
        context = Context()
        context.plugin_id = _config["id"]
        instance.on_load(context)
    elif operation == "event":
        instance.on_event(argument["name"], argument.get("payload", {}))
    elif operation == "command":
        instance.on_command(argument["name"], argument.get("arguments", ""), argument.get("chat", {}))
    elif operation == "tick":
        _loop.call_soon(_loop.stop)
        _loop.run_forever()
    else:
        raise ValueError("Unknown worker operation")
    return True
