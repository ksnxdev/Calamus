package ksnxdev.calamus.filetest;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

public class FileInstrumentation extends Instrumentation {
    private Context context;
    private ClassLoader loader;
    private Class<?> theme;
    private Class<?> type(String name) throws Exception { return Class.forName(name,true,loader); }
    private int key(String name) throws Exception { return theme.getField("key_"+name).getInt(null); }
    private int color(String name) throws Exception { return (int)theme.getMethod("getColor",int.class).invoke(null,key(name)); }
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();Throwable[] failure={null};
        context=getTargetContext();loader=context.getClassLoader();
        runOnMainSync(()->{
            try {
                type("org.telegram.messenger.ApplicationLoader").getMethod("postInitApplication").invoke(null);
                theme=type("org.telegram.ui.ActionBar.Theme");
                theme.getMethod("createChatResources",Context.class,boolean.class).invoke(null,context,false);
                Object previous=theme.getMethod("getCurrentTheme").invoke(null);boolean savedDark=(boolean)previous.getClass().getMethod("isDark").invoke(previous);
                try {
                    for(boolean dark:new boolean[]{false,true}){
                        Object info=theme.getMethod("getTheme",String.class).invoke(null,dark?"Monet Dark":"Monet Light");
                        theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,info,false,dark);
                        render(dark);
                    }
                } finally {theme.getMethod("applyTheme",type("org.telegram.ui.ActionBar.Theme$ThemeInfo"),boolean.class,boolean.class).invoke(null,previous,false,savedDark);}
            } catch(Throwable error){failure[0]=error;}
        });
        if(failure[0]!=null){result.putString("stream",android.util.Log.getStackTraceString(failure[0]));finish(0,result);}
        else{result.putString("stream","File controls passed: actual RadialProgress2 with bubble gradient, incoming/outgoing, normal/pressed, download/cancel/file, light/dark; icon pixel colors and contrast verified.\n");finish(-1,result);}
    }
    private void render(boolean dark) throws Exception {
        Bitmap picture=Bitmap.createBitmap(720,1120,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(picture);canvas.drawColor(color("windowBackgroundGray"));
        Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setTextSize(27);text.setTypeface(Typeface.DEFAULT_BOLD);text.setColor(color("windowBackgroundWhiteBlackText"));
        canvas.drawText(dark?"File controls: dark":"File controls: light",24,48,text);
        Class<?> radial=type("org.telegram.ui.Components.RadialProgress2");
        Class<?> action=type("org.telegram.ui.Components.MediaActionDrawable");
        String[] states={"DOWNLOAD","CANCEL","FILE"};
        for(int row=0;row<4;row++){
            boolean outgoing=row>=2,pressed=(row%2)==1;String side=outgoing?"out":"in";
            int bubble=color("chat_"+side+"Bubble");float top=80+row*250;
            Paint back=new Paint(Paint.ANTI_ALIAS_FLAG);back.setColor(bubble);canvas.drawRoundRect(16,top,704,top+228,36,36,back);
            text.setTextSize(24);canvas.drawText((outgoing?"Outgoing":"Incoming")+(pressed?" selected":" normal"),36,top+38,text);
            for(int column=0;column<3;column++){
                Bitmap bitmap=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);Canvas button=new Canvas(bitmap);button.drawColor(bubble);
                Object progress=radial.getConstructor(View.class).newInstance(new View(context));
                radial.getMethod("setProgressRect",int.class,int.class,int.class,int.class).invoke(progress,32,32,128,128);
                radial.getMethod("setColorKeys",int.class,int.class,int.class,int.class).invoke(progress,key("chat_"+side+"Loader"),key("chat_"+side+"LoaderSelected"),key("chat_"+side+"MediaIcon"),key("chat_"+side+"MediaIconSelected"));
                LinearGradient gradient=new LinearGradient(0,0,0,160,bubble,bubble,Shader.TileMode.CLAMP);
                radial.getMethod("setBackgroundGradientDrawable",LinearGradient.class).invoke(progress,gradient);
                radial.getMethod("setIcon",int.class,boolean.class,boolean.class).invoke(progress,action.getField("ICON_"+states[column]).getInt(null),false,false);
                radial.getMethod("setPressed",boolean.class,boolean.class).invoke(progress,pressed,false);
                radial.getMethod("draw",Canvas.class).invoke(progress,button);
                int foreground=color("chat_"+side+"MediaIcon"+(pressed?"Selected":""));int background=color("chat_"+side+"Loader"+(pressed?"Selected":""));
                Class<?> colors=type("androidx.core.graphics.ColorUtils");
                double contrast=(double)colors.getMethod("calculateContrast",int.class,int.class).invoke(null,foreground,background);
                if(contrast<4.5)throw new AssertionError("Low icon contrast: "+side+" "+contrast);
                int count=0;
                for(int y=34;y<126;y++)for(int x=34;x<126;x++){
                    int value=bitmap.getPixel(x,y);
                    if(Math.abs(Color.red(value)-Color.red(foreground))<12 && Math.abs(Color.green(value)-Color.green(foreground))<12 && Math.abs(Color.blue(value)-Color.blue(foreground))<12)count++;
                }
                if(count<30)throw new AssertionError("Gradient obscured icon: "+side+" "+states[column]+" pixels="+count);
                canvas.drawBitmap(bitmap,30+column*226,top+44,null);text.setTextSize(18);canvas.drawText(states[column],50+column*226,top+211,text);bitmap.recycle();
            }
        }
        try(FileOutputStream stream=new FileOutputStream(new File(context.getFilesDir(),"file-controls-"+(dark?"dark":"light")+".png"))){picture.compress(Bitmap.CompressFormat.PNG,100,stream);}picture.recycle();
    }
}
