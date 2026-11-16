-keep class ksnxdev.calamus.plugins.NativePython { *; }
-keepclassmembers class ksnxdev.calamus.plugins.IsolatedPluginService { public java.lang.String request(java.lang.String); }
-keep class ksnxdev.calamus.plugins.PluginManager {
    public static ksnxdev.calamus.plugins.PluginManager getInstance(android.content.Context);
    public void execute(java.lang.String,java.lang.String,ksnxdev.calamus.plugins.PluginManager$Callback);
}
-keep interface ksnxdev.calamus.plugins.PluginManager$Callback { *; }
