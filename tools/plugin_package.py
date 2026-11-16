import json
import re
import stat
import zipfile
from pathlib import Path, PurePosixPath

MAX_ARCHIVE = 2 * 1024 * 1024
MAX_EXPANDED = 8 * 1024 * 1024
MAX_FILES = 128
MAX_PLUGINS = 32
PERMISSIONS = frozenset({"storage", "notifications", "accounts.read", "chats.read", "messages.read", "messages.send", "ui", "commands", "messages.archive", "messages.menu", "presence.control", "presence.read"})
EVENTS = frozenset({"app.started", "app.foreground", "app.background", "account.changed", "chat.opened", "message.received", "message.sent", "message.failed", "message.sending", "message.updated", "settings.changed", "message.deleted", "message.action"})
ID = re.compile(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+")


def checked_id(value):
    if not isinstance(value, str) or len(value) > 96 or not ID.fullmatch(value):
        raise ValueError("Invalid plugin id")
    return value


def manifest(value):
    if not isinstance(value, dict):
        raise ValueError("Manifest must be an object")
    checked_id(value.get("id"))
    for key, limit in (("name", 64), ("version", 32), ("description", 300), ("author", 80)):
        text = value.get(key)
        if not isinstance(text, str) or not text.strip() or len(text) > limit or any(ord(c) < 32 for c in text):
            raise ValueError("Invalid manifest field: " + key)
    if type(value.get("api_version")) is not int or value["api_version"] not in (1, 2, 3):
        raise ValueError("Unsupported plugin API version")
    for key, allowed in (("permissions", PERMISSIONS), ("events", EVENTS)):
        items = value.get(key, [])
        if not isinstance(items, list) or len(items) > len(allowed) or any(not isinstance(i, str) or i not in allowed for i in items):
            raise ValueError("Unsupported " + key)
        if len(set(items)) != len(items):
            raise ValueError("Duplicate " + key)
    required_permissions = value.get("required_permissions", [])
    if not isinstance(required_permissions, list) or any(not isinstance(p, str) or p not in value.get("permissions", []) for p in required_permissions) or len(set(required_permissions)) != len(required_permissions):
        raise ValueError("Invalid required permissions")
    settings = value.get("settings", [])
    if not isinstance(settings, list) or len(settings) > 24:
        raise ValueError("At most 24 settings are supported")
    keys = set()
    for setting in settings:
        if not isinstance(setting, dict) or not isinstance(setting.get("key"), str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,47}", setting["key"]):
            raise ValueError("Invalid setting key")
        if setting["key"] in keys:
            raise ValueError("Duplicate setting key")
        keys.add(setting["key"])
        if not isinstance(setting.get("title"), str) or not 1 <= len(setting["title"]) <= 80:
            raise ValueError("Invalid setting title")
        kind = setting.get("type")
        default = setting.get("default")
        if kind == "boolean" and type(default) is bool:
            continue
        if kind == "string" and isinstance(default, str) and len(default) <= 1000:
            continue
        if kind == "integer" and type(default) is int and -1000000 <= default <= 1000000:
            continue
        raise ValueError("Unsupported setting type or default")
    required = {"message.received": "messages.read", "message.updated": "messages.read",
                "message.deleted": "messages.archive", "message.action": "messages.menu", "message.sending": "messages.read", "message.sent": "messages.read", "message.failed": "messages.read", "chat.opened": "chats.read"}
    if any(required.get(event) and required[event] not in value.get("permissions", []) for event in value.get("events", [])):
        raise ValueError("Event requires a declared capability")
    return {key: value[key] for key in ("id", "name", "version", "description", "author", "api_version")} | {
        "permissions": value.get("permissions", []), "required_permissions": required_permissions, "events": value.get("events", []), "settings": settings}


def unpack(archive, destination):
    if Path(archive).suffix.lower() != ".plug":
        raise ValueError("Plugin package must have the .plug extension")
    if Path(archive).stat().st_size > MAX_ARCHIVE:
        raise ValueError("Plugin archive exceeds 2 MiB")
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if not entries or len(entries) > MAX_FILES:
            raise ValueError("Plugin must contain at most 128 entries")
        names = set()
        total = 0
        for entry in entries:
            name = entry.filename
            path = PurePosixPath(name)
            if (not name or len(name) > 180 or "\\" in name or path.is_absolute()
                    or any(p in ("", ".", "..") for p in name.rstrip("/").split("/"))
                    or any(ord(c) < 32 for c in name) or name in names):
                raise ValueError("Invalid or duplicate archive path")
            names.add(name)
            mode = entry.external_attr >> 16
            kind = stat.S_IFMT(mode)
            if kind not in (0, stat.S_IFREG, stat.S_IFDIR) or entry.flag_bits & 1:
                raise ValueError("Links, special files and encrypted entries are unsupported")
            if entry.is_dir():
                continue
            if path.suffix not in (".py", ".json", ".txt", ".md", ".png", ".jpg", ".webp"):
                raise ValueError("Unsupported plugin file type")
            total += entry.file_size
            if total > MAX_EXPANDED:
                raise ValueError("Expanded plugin exceeds 8 MiB")
        if "manifest.json" not in names or "main.py" not in names:
            raise ValueError("manifest.json and main.py must be at archive root")
        if source.getinfo("manifest.json").file_size > 8192:
            raise ValueError("Manifest exceeds 8 KiB")
        info = manifest(json.loads(source.read("manifest.json")))
        written = 0
        for entry in entries:
            target = destination / entry.filename
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(entry) as incoming, target.open("xb") as outgoing:
                while chunk := incoming.read(65536):
                    written += len(chunk)
                    if written > MAX_EXPANDED:
                        raise ValueError("Expanded plugin exceeds 8 MiB")
                    outgoing.write(chunk)
            if target.suffix == ".py":
                compile(target.read_bytes(), entry.filename, "exec")
        return info
