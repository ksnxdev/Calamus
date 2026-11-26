package com.exteragram.messenger.plugins;

import java.util.List;
import java.util.ArrayList;
public final class Plugin {
    private final String id;
    private final String name;
    private String description = "";
    private String author = "";
    private String version = "1.0";
    private String icon;
    private String engine = "python";
    private String appVersion;
    private String sdkVersion;
    private boolean enabled;
    private Throwable error;
    private List<String> requirements = new ArrayList<>();
    public Plugin(String id, String name) { this.id = id; this.name = name; }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getAuthor() { return author; }
    public void setAuthor(String value) { author = value; }
    public String getVersion() { return version; }
    public void setVersion(String value) { version = value; }
    public String getIcon() { return icon; }
    public void setIcon(String value) { icon = value; }
    public String getEngine() { return engine; }
    public void setEngine(String value) { engine = value; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String value) { appVersion = value; }
    public String getSdkVersion() { return sdkVersion; }
    public void setSdkVersion(String value) { sdkVersion = value; }
    public boolean isEnabled() { return enabled && error == null; }
    public void setEnabled(boolean value) { enabled = value; }
    public Throwable getError() { return error; }
    public void setError(Throwable value) { error = value; }
    public boolean hasError() { return error != null; }
    public List<String> getRequirements() { return requirements; }
    public void setRequirements(List<String> value) { requirements = value; }
}
