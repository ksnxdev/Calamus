package com.exteragram.messenger.plugins;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import com.chaquo.python.PyObject;
import com.exteragram.hook.HookHandle;
import com.exteragram.messenger.plugins.hooks.MenuItemRecord;
import com.exteragram.messenger.plugins.utils.PluginsWatchdog;
import de.robv.android.xposed.XC_MethodHook;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.SendMessagesHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import ksnxdev.calamus.extera.ExteraPluginManager;

public final class PluginsController {
    public static final String PREF_PLUGIN_ENABLED_KEY_PREFIX = "plugin_enabled_";
    private static final PluginsController INSTANCE = new PluginsController();
    public final File pluginsDir = new File(ApplicationLoader.getFilesDirFixed(), "exteraGram/plugins");
    public final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("calamus_extera_preferences", Activity.MODE_PRIVATE);
    public final DispatchQueue pluginsQueue = Utilities.pluginsQueue;
    public final PluginsWatchdog watchdog = new PluginsWatchdog();
    public final ConcurrentHashMap<String, Plugin> plugins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PyObject> instances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<XC_MethodHook.Unhook>> javaHooks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<HookHandle>> partialHooks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<EventHook> events = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, MenuItemRecord> menuItems = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> fileIcons = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Drawable> iconDrawables = new ConcurrentHashMap<>();
    private final AtomicInteger nextFileIcon = new AtomicInteger(810000);
    private static final class EventHook {
        final String id, name; final boolean substring; final int priority;
        EventHook(String id, String name, boolean substring, int priority) { this.id=id;this.name=name;this.substring=substring;this.priority=priority; }
        boolean matches(String value) { return substring ? value.contains(name) : value.equals(name); }
    }
    private PluginsController() { pluginsDir.mkdirs(); }
    public static PluginsController getInstance() { return INSTANCE; }
    public boolean isInitialized() { return ExteraPluginManager.getInstance().isReady(); }
    public static boolean isPluginEngineSupported() { return true; }
    public static boolean isPluginEngineAvailable() { return ExteraPluginManager.isWarningAccepted(); }
    public static void runOnPluginsQueue(Runnable runnable) { Utilities.pluginsQueue.postRunnable(runnable); }
    public void registerInstance(String id, PyObject instance) { instances.put(id, instance); }
    public boolean isPluginActive(String id) { Plugin plugin=plugins.get(id);return plugin!=null&&plugin.isEnabled()&&instances.containsKey(id); }
    public void addEventHook(String id,String name,boolean substring,int priority) {
        if(name==null||name.isEmpty())return;
        removeEventHook(id,name);events.add(new EventHook(id,name,substring,priority));
    }
    public void removeEventHook(String id,String name) { events.removeIf(e->e.id.equals(id)&&e.name.equals(name)); }
    public void addXposedHook(String id,XC_MethodHook.Unhook hook) { if(hook!=null)javaHooks.computeIfAbsent(id,k->new CopyOnWriteArrayList<>()).add(hook); }
    public void addXposedHooks(String id,ArrayList<XC_MethodHook.Unhook> hooks) { if(hooks!=null)for(var hook:hooks)addXposedHook(id,hook); }
    public void removeXposedHook(String id,XC_MethodHook.Unhook hook) { if(hook==null)return;hook.unhook();var list=javaHooks.get(id);if(list!=null)list.remove(hook); }
    public void addPartialHook(String id,HookHandle hook) { if(hook!=null)partialHooks.computeIfAbsent(id,k->new CopyOnWriteArrayList<>()).add(hook); }
    public void removePartialHook(String id,HookHandle hook) { if(hook==null)return;hook.unhook();var list=partialHooks.get(id);if(list!=null)list.remove(hook); }
    public void removeHooksByPluginId(String id) {
        events.removeIf(e->e.id.equals(id));
        var hooks=javaHooks.remove(id);if(hooks!=null)for(var hook:hooks)try{hook.unhook();}catch(Throwable e){FileLog.e(e);}
        var partial=partialHooks.remove(id);if(partial!=null)for(var hook:partial)try{hook.unhook();}catch(Throwable e){FileLog.e(e);}
    }
    public void cleanupPlugin(String id) { removeHooksByPluginId(id);removeMenuItemsByPluginId(id);instances.remove(id); }
    public String addMenuItem(String id,PyObject data) {
        if(data==null||!isPluginActive(id))return null;
        MenuItemRecord item=new MenuItemRecord(id,data);String key=id+":"+item.getItemId();MenuItemRecord previous=menuItems.put(key,item);if(previous!=null)previous.markRemoved();notifyPluginsChanged();return item.getItemId();
    }
    public boolean removeMenuItem(String id,String key) { MenuItemRecord item=menuItems.remove(id+":"+key);if(item!=null){item.markRemoved();notifyPluginsChanged();}return item!=null; }
    public void removeMenuItemsByPluginId(String id) { for(var entry:menuItems.entrySet())if(entry.getValue().getPluginId().equals(id)){entry.getValue().markRemoved();menuItems.remove(entry.getKey(),entry.getValue());} }
    public List<MenuItemRecord> getMenuItemsForLocation(String location,Map<String,Object> context) {
        ArrayList<MenuItemRecord> result=new ArrayList<>();
        for(var item:menuItems.values())if(location.equals(item.getMenuType())&&isPluginActive(item.getPluginId())&&item.checkCondition(context))result.add(item);
        result.sort((a,b)->Integer.compare(b.getPriority(),a.getPriority()));return result;
    }
    public List<MenuItemRecord> getMenuItemsForLocation(String location) { return getMenuItemsForLocation(location,null); }
    public static int registerFileIcon(String extension,Drawable drawable) {
        if(extension==null||drawable==null)return 0;
        String key=extension.toLowerCase(java.util.Locale.ROOT).replaceFirst("^\\.","");Integer old=INSTANCE.fileIcons.remove(key);if(old!=null)INSTANCE.iconDrawables.remove(old);
        int id=INSTANCE.nextFileIcon.getAndIncrement();INSTANCE.fileIcons.put(key,id);INSTANCE.iconDrawables.put(id,drawable);return id;
    }
    public static void unregisterFileIcon(String extension) { if(extension==null)return;Integer id=INSTANCE.fileIcons.remove(extension.toLowerCase(java.util.Locale.ROOT).replaceFirst("^\\.",""));if(id!=null)INSTANCE.iconDrawables.remove(id); }
    public static void clearFileIcons() { INSTANCE.fileIcons.clear();INSTANCE.iconDrawables.clear(); }
    public static int getFileIconId(String name) { if(name==null)return 0;int dot=name.lastIndexOf('.');return dot<0?0:INSTANCE.fileIcons.getOrDefault(name.substring(dot+1).toLowerCase(java.util.Locale.ROOT),0); }
    public static boolean isPluginFileIcon(int icon) { return INSTANCE.iconDrawables.containsKey(icon); }
    public static Drawable getPluginFileIconDrawable(int icon) { return INSTANCE.iconDrawables.get(icon); }
    public void loadPluginSettings(String id) { ExteraPluginManager.getInstance().invalidateSettings(id); }
    public void loadPluginSettings() { for(String id:plugins.keySet())loadPluginSettings(id); }
    public boolean hasPluginSettings(String id) { return ExteraPluginManager.getInstance().settings(id).size()>0; }
    public String getPluginPath(String id) { return new File(pluginsDir,id+".py").getAbsolutePath(); }
    public Object getPluginEngine(String id) { return ExteraPluginManager.getInstance(); }
    public static void openPluginSettings(String id) { ExteraPluginManager.getInstance().openSettings(id); }
    public static void openPluginSettings(String id,String alias) { ExteraPluginManager.getInstance().openSettings(id); }
    public boolean getPluginSettingBoolean(String id,String key,boolean fallback) { Object value=ExteraPluginManager.getInstance().getSetting(id,key,fallback);return value instanceof Boolean?(Boolean)value:fallback; }
    public String getPluginSettingString(String id,String key,String fallback) { Object value=ExteraPluginManager.getInstance().getSetting(id,key,fallback);return value==null?fallback:String.valueOf(value); }
    public int getPluginSettingInt(String id,String key,int fallback) { Object value=ExteraPluginManager.getInstance().getSetting(id,key,fallback);return value instanceof Number?((Number)value).intValue():fallback; }
    public void setPluginSetting(String id,String key,Object value) { ExteraPluginManager.getInstance().setSetting(id,key,value); }
    public void setPluginSettingAndTriggerOnChange(String id,String key,Object value,PyObject callback) { setPluginSetting(id,key,value);if(callback!=null)callback.call(value); }
    public void clearPluginSettingsPreferences(String id) { ExteraPluginManager.getInstance().clearSettings(id); }
    public void clearPluginSettingsPreferences(String id,boolean clearEnabled) { clearPluginSettingsPreferences(id);if(clearEnabled)ExteraPluginManager.getInstance().setEnabled(id,false,null); }
    public Map<String,?> getPluginSettingsPreferences(String id) { return ExteraPluginManager.getInstance().allSettings(id); }
    public boolean hasPluginSettingsPreferences(String id) { return !getPluginSettingsPreferences(id).isEmpty(); }
    public void notifyPluginsChanged() { ExteraPluginManager.getInstance().notifyChanged(); }
    public void executeOnAppEvent(String event) { ExteraPluginManager.getInstance().appEvent(event); }
    private List<EventHook> matching(String name) {
        ArrayList<EventHook> result=new ArrayList<>();for(EventHook event:events)if(isPluginActive(event.id)&&event.matches(name))result.add(event);
        result.sort((a,b)->Integer.compare(b.priority,a.priority));return result;
    }
    private Object execute(String method,String name,String field,int account,Object object,Object error) {
        Object current=object;
        for(EventHook hook:matching(name))try {
            Object[] args=method.equals("on_send_message_hook")?new Object[]{account,current}:method.equals("post_request_hook")?new Object[]{name,account,current,error}:new Object[]{name,account,current};
            PyObject result=ExteraPluginManager.getInstance().invoke(hook.id,method,args);if(result==null)continue;
            PyObject strategy=result.get("strategy");if(strategy==null)continue;PyObject strategyValue=strategy.get("value");String action=strategyValue==null?strategy.toString():strategyValue.toString();
            if(action.equals("CANCEL"))return null;
            if(action.equals("MODIFY")||action.equals("MODIFY_FINAL")) {PyObject value=result.get(field);current=value==null?null:value.toJava(Object.class);if(action.equals("MODIFY_FINAL"))break;}
        } catch(Throwable e){FileLog.e(e);}
        return current;
    }
    public TLObject executePreRequestHook(String name,int account,TLObject request) { return (TLObject)execute("pre_request_hook",name,"request",account,request,null); }
    public TLRPC.Update executeUpdateHook(String name,int account,TLRPC.Update update) { return (TLRPC.Update)execute("on_update_hook",name,"update",account,update,null); }
    public TLRPC.Updates executeUpdatesHook(String name,int account,TLRPC.Updates updates) { return (TLRPC.Updates)execute("on_updates_hook",name,"updates",account,updates,null); }
    public SendMessagesHelper.SendMessageParams executeSendMessageHook(int account,SendMessagesHelper.SendMessageParams params) { return (SendMessagesHelper.SendMessageParams)execute("on_send_message_hook","send_message_hook","params",account,params,null); }
    public static final class PostRequestResult {
        public TLObject response; public TLRPC.TL_error error;
        public PostRequestResult(TLObject response,TLRPC.TL_error error){this.response=response;this.error=error;}
    }
    public PostRequestResult executePostRequestHook(String name,int account,TLObject response,TLRPC.TL_error error) {
        for(EventHook hook:matching(name))try {
            PyObject result=ExteraPluginManager.getInstance().invoke(hook.id,"post_request_hook",new Object[]{name,account,response,error});if(result==null)continue;
            PyObject strategy=result.get("strategy");if(strategy==null)continue;PyObject value=strategy.get("value");String action=value==null?strategy.toString():value.toString();
            if(action.equals("CANCEL"))return new PostRequestResult(null,null);
            if(action.equals("MODIFY")||action.equals("MODIFY_FINAL")) {PyObject res=result.get("response"),err=result.get("error");response=res==null?null:res.toJava(TLObject.class);error=err==null?null:err.toJava(TLRPC.TL_error.class);if(action.equals("MODIFY_FINAL"))break;}
        } catch(Throwable e){FileLog.e(e);}
        return new PostRequestResult(response,error);
    }
}
