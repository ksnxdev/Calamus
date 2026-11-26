package ksnxdev.calamus.extera;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.exteragram.hook.ExteraHook;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsController;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.LaunchActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import ksnxdev.calamus.extera.ui.ExteraPluginSettingsActivity;

public final class ExteraPluginManager {
    private static final ExteraPluginManager INSTANCE = new ExteraPluginManager();
    private static final String ACK = "warning_acknowledged";
    private final ConcurrentHashMap<String,JSONObject> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Review> reviews = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean ready;
    private boolean initializing;
    private Python python;
    private PyObject engine;
    private static final class Review {
        final File file;final JSONObject info;final String digest;final long expires;
        Review(File file,JSONObject info,String digest){this.file=file;this.info=info;this.digest=digest;expires=android.os.SystemClock.elapsedRealtime()+600000;}
    }
    private ExteraPluginManager() {}
    public static ExteraPluginManager getInstance(){return INSTANCE;}
    private static SharedPreferences preferences(){return ApplicationLoader.applicationContext.getSharedPreferences("calamus_extera_preferences",Context.MODE_PRIVATE);}
    public static boolean isWarningAccepted(){return ApplicationLoader.applicationContext!=null&&preferences().getBoolean(ACK,false);}
    public static void acceptWarning(){preferences().edit().putBoolean(ACK,true).commit();}
    public boolean isReady(){return ready;}
    public static void startInstalled(){if(android.os.Process.myUid()%100000<90000&&isWarningAccepted())INSTANCE.list(null);}
    private File root(){return new File(ApplicationLoader.getFilesDirFixed(),"exteraGram");}
    private File pluginsDir(){return new File(root(),"plugins");}
    private AtomicFile database(){return new AtomicFile(new File(root(),"registry.json"));}
    private void ensureReady() throws Exception {
        if(!isWarningAccepted())throw new IllegalStateException(LocaleController.getString(R.string.CalamusExteraConsentRequired));
        if(ready)return;
        if(Build.VERSION.SDK_INT<26)throw new IllegalStateException("exteraGram plugins require Android 8 or newer");
        if(initializing)throw new IllegalStateException("Plugin runtime is still starting");
        initializing=true;
        try {
            root().mkdirs();pluginsDir().mkdirs();new File(root(),"imports").mkdirs();
            registry.clear();
            if(database().getBaseFile().isFile())try(InputStream stream=database().openRead()){
                JSONObject data=new JSONObject(new String(readBounded(stream,4*1024*1024),StandardCharsets.UTF_8));
                JSONArray list=data.optJSONArray("plugins");if(list!=null)for(int i=0;i<list.length();i++){JSONObject item=list.optJSONObject(i);if(item!=null&&validId(item.optString("id")))registry.put(item.optString("id"),item);}
            }
            String interrupted=preferences().getString("loading_plugin",null);
            if(interrupted!=null&&registry.containsKey(interrupted))registry.get(interrupted).put("enabled",false).put("error","Plugin failed during the previous startup");
            preferences().edit().remove("loading_plugin").commit();
            if(!Python.isStarted())Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
            python=Python.getInstance();engine=python.getModule("calamus_extera");
            if(Build.SUPPORTED_ABIS.length>0&&(Build.SUPPORTED_ABIS[0].startsWith("arm")||Build.SUPPORTED_ABIS[0].startsWith("armeabi"))){
                if(!ExteraHook.initialize())throw new IllegalStateException("exteraGram method hooks could not be initialized");
            }
            engine.callAttr("init",pluginsDir().getAbsolutePath());
            ready=true;
            for(JSONObject item:new ArrayList<>(registry.values())){
                String id=item.optString("id");registerMetadata(item);
                if(item.optBoolean("enabled"))try{enable(id);}catch(Throwable failure){failed(id,failure);}
            }
            save();
        } finally {initializing=false;}
    }
    private static boolean validId(String id){return id!=null&&id.matches("[A-Za-z_][A-Za-z0-9_]{0,127}");}
    private void registerMetadata(JSONObject item){
        Plugin plugin=new Plugin(item.optString("id"),item.optString("name",item.optString("id")));plugin.setDescription(item.optString("description"));plugin.setAuthor(item.optString("author"));plugin.setVersion(item.optString("version"));plugin.setIcon(item.optString("icon"));
        ArrayList<String> requirements=new ArrayList<>();JSONArray list=item.optJSONArray("requirements");if(list!=null)for(int i=0;i<list.length();i++)requirements.add(list.optString(i));plugin.setRequirements(requirements);
        PluginsController.getInstance().plugins.put(plugin.getId(),plugin);
    }
    private void enable(String id)throws Exception {
        JSONObject item=registry.get(id);if(item==null)throw new IllegalArgumentException("Unknown plugin");
        PluginsController controller=PluginsController.getInstance();Plugin plugin=controller.plugins.get(id);if(plugin==null){registerMetadata(item);plugin=controller.plugins.get(id);}
        if(controller.isPluginActive(id))return;
        preferences().edit().putString("loading_plugin",id).commit();
        try {
            plugin.setError(null);plugin.setEnabled(true);
            engine.callAttr("load",id,new File(pluginsDir(),id+".py").getAbsolutePath(),item.toString());
            item.put("enabled",true).put("error","");
            engine.callAttr("on_event",id,"app_start");
        } catch(Throwable failure){
            plugin.setEnabled(false);plugin.setError(failure);controller.cleanupPlugin(id);try{engine.callAttr("unload",id);}catch(Throwable ignored){}throw failure;
        } finally {preferences().edit().remove("loading_plugin").commit();}
    }
    private void disable(String id)throws Exception {
        JSONObject item=registry.get(id);if(item==null)return;
        PluginsController controller=PluginsController.getInstance();
        boolean wasActive=controller.isPluginActive(id);
        Plugin active=controller.plugins.get(id);if(active!=null)active.setEnabled(false);
        try {if(wasActive){try{engine.callAttr("on_event",id,"app_stop");}finally{engine.callAttr("unload",id);}}}
        finally{controller.cleanupPlugin(id);Plugin plugin=controller.plugins.get(id);if(plugin!=null)plugin.setEnabled(false);item.put("enabled",false);}
    }
    private void failed(String id,Throwable failure){
        FileLog.e(failure);JSONObject item=registry.get(id);if(item!=null)try{item.put("enabled",false).put("error",error(failure));}catch(Exception ignored){}
        PluginsController.getInstance().cleanupPlugin(id);Plugin plugin=PluginsController.getInstance().plugins.get(id);if(plugin!=null){plugin.setEnabled(false);plugin.setError(failure);}
    }
    private void save()throws Exception {
        JSONArray list=new JSONArray();for(JSONObject item:registry.values())list.put(item);
        byte[] bytes=new JSONObject().put("plugins",list).toString().getBytes(StandardCharsets.UTF_8);AtomicFile file=database();FileOutputStream out=file.startWrite();
        try{out.write(bytes);file.finishWrite(out);}catch(Throwable failure){file.failWrite(out);throw failure;}
    }
    private static byte[] readBounded(InputStream input,int limit)throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] block=new byte[65536];int n;while((n=input.read(block))!=-1){if(out.size()+n>limit)throw new java.io.IOException("File is too large");out.write(block,0,n);}return out.toByteArray();
    }
    private static String digest(File file)throws Exception {MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] block=new byte[65536];int n;while((n=input.read(block))!=-1)hash.update(block,0,n);}StringBuilder value=new StringBuilder();for(byte v:hash.digest())value.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return value.toString();}
    private static String error(Throwable failure){String message=failure.getMessage();if(message==null)message=failure.getClass().getSimpleName();return message.substring(0,Math.min(message.length(),6000));}
    private JSONObject result(){JSONObject result=new JSONObject();JSONArray list=new JSONArray();ArrayList<JSONObject> ordered=new ArrayList<>(registry.values());ordered.sort((a,b)->a.optString("name").compareToIgnoreCase(b.optString("name")));for(JSONObject item:ordered)list.put(item);try{return result.put("plugins",list);}catch(Exception ignored){return result;}}
    private void respond(Utilities.Callback<JSONObject> callback,JSONObject result){if(callback!=null)AndroidUtilities.runOnUIThread(()->callback.run(result));}
    private interface Operation{void run()throws Exception;}
    private void operation(Operation task,Utilities.Callback<JSONObject> callback){Utilities.pluginsQueue.postRunnable(()->{JSONObject response;try{ensureReady();task.run();save();response=result();}catch(Throwable failure){FileLog.e(failure);if(ready)try{save();}catch(Exception saveFailure){FileLog.e(saveFailure);}response=result();try{response.put("error",error(failure));}catch(Exception ignored){}}respond(callback,response);notifyChanged();});}
    public void list(Utilities.Callback<JSONObject> callback){operation(()->{},callback);}
    public void setEnabled(String id,boolean enabled,Utilities.Callback<JSONObject> callback){operation(()->{if(enabled)try{enable(id);}catch(Throwable failure){failed(id,failure);throw failure;}else disable(id);},callback);}
    public void disableAll(Utilities.Callback<JSONObject> callback){operation(()->{for(String id:registry.keySet())try{disable(id);}catch(Throwable failure){failed(id,failure);}},callback);}
    public void remove(String id,Utilities.Callback<JSONObject> callback){operation(()->{if(!validId(id))throw new IllegalArgumentException("Invalid plugin id");disable(id);File file=new File(pluginsDir(),id+".py");if(file.exists()&&!file.delete())throw new java.io.IOException("Could not delete plugin");clearSettings(id);registry.remove(id);PluginsController.getInstance().plugins.remove(id);},callback);}
    public void review(Uri uri,Utilities.Callback<JSONObject> callback){Utilities.pluginsQueue.postRunnable(()->{
        File staged=null;JSONObject response;
        try{
            ensureReady();String filename=null;
            try(Cursor cursor=ApplicationLoader.applicationContext.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()){int col=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(col>=0)filename=cursor.getString(col);}
            }
            if(filename==null)filename=uri.getLastPathSegment();
            if(filename!=null&&!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".plugin"))throw new IllegalArgumentException(LocaleController.getString(R.string.CalamusExteraUnsupported));
            staged=new File(new File(root(),"imports"),UUID.randomUUID()+".plugin");
            try(InputStream input=ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(staged)){
                if(input==null)throw new java.io.IOException("Could not read plugin");byte[] bytes=readBounded(input,64*1024*1024);out.write(bytes);out.getFD().sync();
            }
            response=new JSONObject(engine.callAttr("metadata",staged.getAbsolutePath()).toString());String id=response.optString("id");if(!validId(id))throw new IllegalArgumentException("Invalid plugin id");
            String token=UUID.randomUUID().toString();Review review=new Review(staged,response,digest(staged));reviews.put(token,review);response=new JSONObject(response.toString()).put("token",token).put("update",registry.containsKey(id));staged=null;
        }catch(Throwable failure){response=new JSONObject();try{response.put("error",error(failure));}catch(Exception ignored){}}
        finally{if(staged!=null)staged.delete();}
        respond(callback,response);
    });}
    public void cancelReview(String token){Review value=reviews.remove(token);if(value!=null)value.file.delete();}
    public void install(String token,Utilities.Callback<JSONObject> callback){operation(()->{
        Review review=reviews.remove(token);if(review==null)throw new IllegalStateException("Installation preview has expired");
        try{
            if(android.os.SystemClock.elapsedRealtime()>review.expires||!digest(review.file).equals(review.digest))throw new IllegalStateException("Installation preview has expired");
            String id=review.info.getString("id");disable(id);AtomicFile destination=new AtomicFile(new File(pluginsDir(),id+".py"));FileOutputStream output=destination.startWrite();
            try(InputStream input=new FileInputStream(review.file)){byte[] block=new byte[65536];int n;while((n=input.read(block))!=-1)output.write(block,0,n);destination.finishWrite(output);}catch(Throwable failure){destination.failWrite(output);throw failure;}
            JSONObject item=new JSONObject(review.info.toString()).put("enabled",false).put("error","");registry.put(id,item);registerMetadata(item);save();
            try{enable(id);}catch(Throwable failure){failed(id,failure);throw failure;}
        }finally{review.file.delete();}
    },callback);}
    public PyObject invoke(String id,String method,Object[] args){if(!ready)return null;return engine.callAttr("invoke",id,method,args);}
    public List<PyObject> settings(String id){if(!ready||!PluginsController.getInstance().isPluginActive(id))return new ArrayList<>();try{PyObject result=engine.callAttr("settings",id);return result==null?new ArrayList<>():new ArrayList<>(result.asList());}catch(Throwable failure){FileLog.e(failure);return new ArrayList<>();}}
    public void invalidateSettings(String id){notifyChanged();}
    public Object getSetting(String id,String key,Object fallback){if(!ready)return fallback;try{PyObject value=python.getModule("plugin_settings").callAttr("get_setting",id,key,fallback);return value==null?null:value.toJava(Object.class);}catch(Throwable failure){FileLog.e(failure);return fallback;}}
    public void setSetting(String id,String key,Object value){if(!ready)return;python.getModule("plugin_settings").callAttr("set_setting",id,key,value);}
    public void clearSettings(String id){if(ready)python.getModule("plugin_settings").callAttr("clear_settings",id);}
    public Map<String,?> allSettings(String id){HashMap<String,Object> values=new HashMap<>();if(ready)try{PyObject data=python.getModule("plugin_settings").callAttr("get_all_settings",id);if(data!=null)for(var entry:data.asMap().entrySet())values.put(entry.getKey().toString(),entry.getValue()==null?null:entry.getValue().toJava(Object.class));}catch(Throwable failure){FileLog.e(failure);}return values;}
    public void openSettings(String id){AndroidUtilities.runOnUIThread(()->{var fragment=LaunchActivity.getSafeLastFragment();if(fragment!=null)fragment.presentFragment(new ExteraPluginSettingsActivity(id));});}
    public void appEvent(String event){if(!ready)return;Utilities.pluginsQueue.postRunnable(()->{for(var entry:PluginsController.getInstance().plugins.entrySet())if(PluginsController.getInstance().isPluginActive(entry.getKey()))try{engine.callAttr("on_event",entry.getKey(),event);}catch(Throwable failure){FileLog.e(failure);}});}
    public void addListener(Runnable listener){listeners.addIfAbsent(listener);}
    public void removeListener(Runnable listener){listeners.remove(listener);}
    public void notifyChanged(){for(Runnable listener:listeners)AndroidUtilities.runOnUIThread(listener);}
}
