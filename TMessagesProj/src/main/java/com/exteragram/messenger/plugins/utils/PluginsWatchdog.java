package com.exteragram.messenger.plugins.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
public final class PluginsWatchdog {
    private final ConcurrentHashMap<String, Long> active = new ConcurrentHashMap<>();
    public void onPluginExecutionStarted(String id) { if (id != null) active.put(id, android.os.SystemClock.elapsedRealtime()); }
    public void onPluginExecutionFinished(String id) { if (id != null) active.remove(id); }
    public Map<String, Long> runningPlugins() { return new java.util.HashMap<>(active); }
}
