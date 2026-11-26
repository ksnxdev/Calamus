import ast
import importlib.util
import importlib.metadata
import json
import os
import re
import sys
from pathlib import Path

_instances = {}
_ready = False


def init(directory):
    global _ready
    if _ready:
        return
    import _sdk_version
    from org.telegram.messenger import BuildVars
    _sdk_version.X.BUILD_VERSION = int(BuildVars.BUILD_VERSION)
    _sdk_version.SafeModeImporter.I = True
    _sdk_version.SafeModeImporter.E = False
    if directory not in sys.path:
        sys.path.insert(0, directory)
    libraries = os.path.join(os.path.dirname(directory), "libraries")
    os.makedirs(libraries, exist_ok=True)
    if libraries not in sys.path:
        sys.path.insert(0, libraries)
    import plugin_settings
    from com.exteragram.messenger.plugins import PluginsController
    plugin_settings.init(directory, PluginsController.getInstance().preferences.getAll())
    import base_plugin
    _ready = True


def metadata(file_path):
    from extera_utils.metadata_parser import get_metadata
    info = get_metadata(file_path)
    plugin_id = str(info.get("id", ""))
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,127}", plugin_id):
        raise ValueError("Missing or invalid __id__")
    if plugin_id in sys.stdlib_module_names or importlib.util.find_spec(plugin_id) is not None:
        existing = sys.modules.get(plugin_id)
        if existing is None or getattr(existing, "__calamus_extera_plugin__", False) is not True:
            from com.exteragram.messenger.plugins import PluginsController
            target = Path(str(PluginsController.getInstance().pluginsDir.getAbsolutePath())) / (plugin_id + ".py")
            spec = importlib.util.find_spec(plugin_id)
            if spec is None or not spec.origin or Path(spec.origin).resolve() != target.resolve():
                raise ValueError("Plugin id conflicts with an existing Python module")
    with open(file_path, encoding="utf-8-sig") as source:
        tree = ast.parse(source.read(), filename=file_path)
    from packaging.specifiers import SpecifierSet
    from org.telegram.messenger import BuildVars
    versions = [(info.get("app_version", info.get("min_version")), str(BuildVars.BUILD_VERSION_STRING)), (info.get("sdk_version"), "1.4.6.5")]
    for requirement, actual in versions:
        if requirement and str(requirement).strip():
            requirement = str(requirement).strip()
            if requirement[0].isdigit():
                requirement = ">=" + requirement
            if actual not in SpecifierSet(requirement):
                raise ValueError("Incompatible version requirement: " + requirement)
    requirements = str(info.get("requirements", "")).split(",")
    for statement in tree.body:
        if isinstance(statement, ast.Assign) and any(isinstance(target, ast.Name) and target.id == "__requirements__" for target in statement.targets):
            value = ast.literal_eval(statement.value)
            if isinstance(value, (list, tuple)) and all(isinstance(item, str) for item in value):
                requirements = list(value)
    return json.dumps({"id": plugin_id, "name": str(info.get("name", plugin_id)), "description": str(info.get("description", "")), "author": str(info.get("author", "")), "version": str(info.get("version", "1.0")), "icon": str(info.get("icon", "")), "requirements": [x.strip() for x in requirements if x.strip()]})


def dependencies(requirements):
    from packaging.requirements import Requirement
    from com.exteragram.messenger.plugins import PluginsController
    destination = os.path.join(os.path.dirname(str(PluginsController.getInstance().pluginsDir.getAbsolutePath())), "libraries")
    for value in json.loads(requirements):
        requirement = Requirement(value)
        if requirement.marker and not requirement.marker.evaluate():
            continue
        try:
            version = importlib.metadata.version(requirement.name)
            if version in requirement.specifier:
                continue
        except importlib.metadata.PackageNotFoundError:
            pass
        from pip._internal import main
        result = main(["install", "--disable-pip-version-check", "--no-warn-script-location", "--only-binary=:all:", "--upgrade", "--extra-index-url", "https://chaquo.com/pypi-13.1", "--target", destination, value])
        if result != 0:
            raise RuntimeError("Could not install dependency: " + value)
    importlib.invalidate_caches()


def load(plugin_id, file_path, data):
    from base_plugin import BasePlugin
    info = json.loads(data)
    dependencies(json.dumps(info.get("requirements", [])))
    BasePlugin._begin_lifecycle(plugin_id)
    sys.modules.pop(plugin_id, None)
    cached = importlib.util.cache_from_source(file_path)
    if os.path.exists(cached):
        os.unlink(cached)
    importlib.invalidate_caches()
    spec = importlib.util.spec_from_file_location(plugin_id, file_path)
    module = importlib.util.module_from_spec(spec)
    module.__calamus_extera_plugin__ = True
    sys.modules[plugin_id] = module
    try:
        spec.loader.exec_module(module)
        cls = BasePlugin._findPluginClass(module)
        if cls is None:
            raise ValueError("No BasePlugin subclass was found")
        instance = cls()
        for key in ("id", "name", "description", "author", "version", "requirements", "icon"):
            setattr(instance, key, info.get(key))
        instance.enabled = True
        instance.initialized = True
        _instances[plugin_id] = instance
        from com.exteragram.messenger.plugins import PluginsController
        PluginsController.getInstance().registerInstance(plugin_id, instance)
        instance.on_plugin_load()
        return instance
    except BaseException:
        unload(plugin_id)
        raise


def unload(plugin_id):
    from base_plugin import BasePlugin
    instance = _instances.pop(plugin_id, None)
    try:
        if instance is not None:
            instance.enabled = False
            instance._invoke_plugin_unload()
    finally:
        BasePlugin._stop_lifecycle(plugin_id)
        BasePlugin._cleanup(plugin_id)
        sys.modules.pop(plugin_id, None)


def settings(plugin_id):
    instance = _instances.get(plugin_id)
    if instance is None:
        return []
    return instance.create_settings() or []


def on_event(plugin_id, event):
    from base_plugin import AppEvent
    instance = _instances.get(plugin_id)
    if instance is not None:
        instance.on_app_event(AppEvent(event))


def invoke(plugin_id, method, args):
    instance = _instances.get(plugin_id)
    if instance is None:
        return None
    return getattr(instance, method)(*list(args))
