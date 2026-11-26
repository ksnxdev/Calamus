package ksnxdev.calamus.extera.ui;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.models.CustomSetting;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import ksnxdev.calamus.extera.ExteraPluginManager;
import uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry;
import uz.unnarsx.cherrygram.preferences.helpers.SettingsHelper;

public final class ExteraPluginSettingsActivity extends BaseCGPreferencesEntry {
    private final String id;
    private List<PyObject> definitions = new ArrayList<>();
    private final IdentityHashMap<UItem,PyObject> bindings = new IdentityHashMap<>();
    private final boolean nested;
    public ExteraPluginSettingsActivity(String id){this.id=id;nested=false;}
    public ExteraPluginSettingsActivity(String id,List<PyObject> values){this.id=id;definitions=values;nested=true;}
    private ExteraPluginManager manager(){return ExteraPluginManager.getInstance();}
    @Override protected CharSequence getTitle(){var plugin=PluginsController.getInstance().plugins.get(id);return plugin==null?LocaleController.getString(R.string.CalamusPluginSettings):plugin.getName();}
    @Override public void onResume(){super.onResume();if(!nested)definitions=manager().settings(id);if(listView!=null)listView.adapter.update(false);}
    private static PyObject field(PyObject object,String key){try{return object.get(key);}catch(Throwable ignored){return null;}}
    private static String text(PyObject object,String key){PyObject value=field(object,key);return value==null?"":value.toString();}
    private Object defaultValue(PyObject setting){PyObject value=field(setting,"default");return value==null?null:value.toJava(Object.class);}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        bindings.clear();int index=100;
        for(PyObject definition:definitions)try{
            String type=text(definition,"type"),title=text(definition,"text"),key=text(definition,"key");UItem item;
            if(type.equals("header")){items.add(UItem.asHeader(title));continue;}
            if(type.equals("divider")){items.add(UItem.asShadow(title.isEmpty()?null:title));continue;}
            if(type.equals("switch")){
                Object value=manager().getSetting(id,key,defaultValue(definition));item=SettingsHelper.asSwitchCG(index,title).setChecked(Boolean.TRUE.equals(value));
            }else if(type.equals("input")||type.equals("edit_text")){
                Object value=manager().getSetting(id,key,defaultValue(definition));item=UItem.asButton(index,title,value==null?"":String.valueOf(value));
            }else if(type.equals("selector")){
                Object value=manager().getSetting(id,key,defaultValue(definition));int selected=value instanceof Number?((Number)value).intValue():0;PyObject choices=field(definition,"items");List<PyObject> values=choices==null?new ArrayList<>():choices.asList();
                item=UItem.asButton(index,title,selected>=0&&selected<values.size()?values.get(selected).toString():"");
            }else if(type.equals("custom")){
                PyObject own=field(definition,"item"),view=field(definition,"view"),factory=field(definition,"factory");
                if(own!=null)item=own.toJava(UItem.class);
                else if(view!=null)item=UItem.asCustom(view.toJava(View.class));
                else if(factory!=null){
                    try{PyObject java=field(factory,"java");if(java!=null)factory=java;}catch(Throwable ignored){}
                    CustomSetting.Factory<?> provider=factory.toJava(CustomSetting.Factory.class);
                    CustomSetting model=new CustomSetting(provider,field(definition,"factory_args"),field(definition,"on_click"),field(definition,"create_sub_fragment"),field(definition,"on_long_click"),text(definition,"link_alias"));
                    item=provider.create(PluginsController.getInstance().plugins.get(id),model,field(definition,"factory_args"));
                    if(item==null)throw new IllegalArgumentException("Custom settings factory returned no item");
                }else continue;
            }else item=UItem.asButton(index,title,text(definition,"subtext"));
            item.enabled=PluginsController.getInstance().isPluginActive(id);bindings.put(item,definition);items.add(item);index++;
        }catch(Throwable failure){FileLog.e(failure);items.add(UItem.asShadow(failure.getMessage()));}
        if(definitions.isEmpty())items.add(UItem.asShadow(LocaleController.getString(R.string.NoResult)));
    }
    private void changed(PyObject definition,Object value){
        manager().setSetting(id,text(definition,"key"),value);PyObject callback=field(definition,"on_change");if(callback!=null)callback.call(value);if(listView!=null)listView.adapter.update(true);
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){
        if(!PluginsController.getInstance().isPluginActive(id))return;PyObject definition=bindings.get(item);if(definition==null)return;
        try{
            String type=text(definition,"type"),key=text(definition,"key");
            if(type.equals("switch")){changed(definition,!Boolean.TRUE.equals(manager().getSetting(id,key,defaultValue(definition))));return;}
            if(type.equals("selector")){
                PyObject choices=field(definition,"items");if(choices==null)return;List<PyObject> values=choices.asList();CharSequence[] labels=new CharSequence[values.size()];for(int i=0;i<labels.length;i++)labels[i]=values.get(i).toString();
                AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity(),getResourceProvider());dialog.setTitle(text(definition,"text"));dialog.setItems(labels,(d,selected)->changed(definition,selected));showDialog(dialog.create());return;
            }
            if(type.equals("input")||type.equals("edit_text")){
                EditText input=new EditText(getParentActivity());Object value=manager().getSetting(id,key,defaultValue(definition));input.setText(value==null?"":String.valueOf(value));input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,getResourceProvider()));input.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray,getResourceProvider()));input.setTextSize(16);input.setPadding(AndroidUtilities.dp(21),AndroidUtilities.dp(12),AndroidUtilities.dp(21),AndroidUtilities.dp(12));
                PyObject multiline=field(definition,"multiline");input.setSingleLine(multiline==null||!multiline.toBoolean());input.setHint(text(definition,"hint"));
                PyObject maximum=field(definition,"max_length");if(maximum!=null&&maximum.toInt()>0)input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maximum.toInt())});
                AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity(),getResourceProvider());dialog.setTitle(text(definition,"text"));dialog.setView(input);dialog.setPositiveButton(LocaleController.getString(R.string.Save),(d,w)->changed(definition,input.getText().toString()));dialog.setNegativeButton(LocaleController.getString(R.string.Cancel),null);showDialog(dialog.create());return;
            }
            PyObject callback=field(definition,"on_click");if(callback!=null)callback.call(view);
            PyObject child=field(definition,"create_sub_fragment");if(child!=null){PyObject result=child.call();if(result!=null)presentFragment(new ExteraPluginSettingsActivity(id,new ArrayList<>(result.asList())));}
            PyObject factory=field(definition,"factory");if(factory!=null){PyObject java=field(factory,"java");if(java!=null)factory=java;factory.toJava(CustomSetting.Factory.class).onClick(PluginsController.getInstance().plugins.get(id),item,view);}
        }catch(Throwable failure){FileLog.e(failure);AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity(),getResourceProvider());dialog.setTitle(LocaleController.getString(R.string.ErrorOccurred));dialog.setMessage(failure.getMessage());dialog.setPositiveButton(LocaleController.getString(R.string.OK),null);showDialog(dialog.create());}
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){PyObject definition=bindings.get(item);if(definition==null||!PluginsController.getInstance().isPluginActive(id))return false;PyObject callback=field(definition,"on_long_click");if(callback==null)return false;try{callback.call(view);return true;}catch(Throwable failure){FileLog.e(failure);return false;}}
}
