package io.github.sabopura.playbackprobe;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

public final class LayoutProbe extends Instrumentation {
    private final StringBuilder report=new StringBuilder();
    private int failures;
    private ClassLoader loader;
    interface Check { void run() throws Throwable; }
    private Class<?> type(String name) throws Exception { return Class.forName(name,true,loader); }
    private void check(String name, Check action) {
        try { action.run(); report.append("PASS ").append(name).append('\n'); }
        catch(Throwable t) {
            failures++; report.append("FAIL ").append(name).append('\n');
            java.io.StringWriter w=new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(w)); report.append(w.toString());
        }
    }
    private View inflate(Context context,String name) {
        return LayoutInflater.from(context).inflate(context.getResources().getIdentifier(name,"layout",context.getPackageName()),null,false);
    }
    private void draw(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.AT_MOST));
        view.layout(0,0,800,Math.max(1,view.getMeasuredHeight()));
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(800,Math.max(1,view.getMeasuredHeight()),android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap)); bitmap.recycle();
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        runOnMainSync(() -> {
            Context context=getTargetContext();
            loader=context.getClassLoader();
            Context themed=new ContextThemeWrapper(context,context.getApplicationInfo().theme);
            for(String name:new String[]{"player_view_delegate","player_control_overlay","message_input"}) {
                try {
                    int id=context.getResources().getIdentifier(name,"layout",context.getPackageName());
                    View view=LayoutInflater.from(themed).inflate(id,null,false);
                    report.append("PASS ").append(name).append(" ").append(view.getClass().getName()).append('\n');
                } catch(Throwable t) {
                    failures++;
                    report.append("FAIL ").append(name).append('\n');
                    java.io.StringWriter w=new java.io.StringWriter();
                    t.printStackTrace(new java.io.PrintWriter(w)); report.append(w.toString());
                }
            }
            check("channel connection constructor", () -> {
                Class<?> tuid=type("tv.twitch.android.models.Tuid");
                Object id=tuid.getConstructor(String.class).newInstance("1");
                type("tv.twitch.android.shared.chat.pub.messages.data.ChannelChatConnectionKey").getConstructor(tuid,String.class).newInstance(id,"playback_probe");
                Object room=type("io.github.sabopura.morphe.extension.twitch.chat.ChatEnhancements").getMethod("channelId").invoke(null);
                if(!"1".equals(room)) throw new AssertionError("Channel bridge returned wrong ID");
            });
            for(String style:new String[]{"Default","Overlay","OverlayHighContrast"}) {
                check("native chat bind and draw "+style, () -> {
                    View row=inflate(themed,"chat_message_item");
                    Class<?> holderType=type("h5d"),modelType=type("mtp"),styleType=type("o96");
                    Object holder=holderType.getConstructor(View.class).newInstance(row);
                    Object config=type("n96").getConstructors()[0].newInstance(false,false,false,false,styleType.getField(style).get(null));
                    Object content=type("hb6").getConstructors()[0].newInstance(null,null,null,null,"probe: hello chat",false);
                    Class<?> function=type("kotlin.jvm.functions.Function1");
                    Object formatter=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{function},(p,m,a)->content);
                    Object tuid=type("tv.twitch.android.models.Tuid").getConstructor(String.class).newInstance("2");
                    java.lang.reflect.Constructor<?> constructor=java.util.Arrays.stream(modelType.getConstructors()).filter(c -> c.getParameterTypes().length==19).findFirst().get();
                    Object[] arguments={"probe-id",type("lb6").getField("a").get(null),tuid,"probe","Probe",Long.valueOf(System.currentTimeMillis()/1000),formatter,null,null,false,null,null,config,null,null,"1",false,null,false};
                    Object model=constructor.newInstance(arguments);
                    holderType.getMethod("C",modelType,boolean.class).invoke(holder,model,false);
                    draw(row);
                    holderType.getMethod("C",modelType,boolean.class).invoke(holder,model,true);
                    draw(row);
                    java.lang.reflect.Method author=type("io.github.sabopura.morphe.extension.twitch.chat.RowAccess").getMethod("userId",Object.class);
                    if(!"2".equals(author.invoke(null,model))) throw new AssertionError("Author ID conversion");
                    arguments[2]=null;
                    model=constructor.newInstance(arguments);
                    if(author.invoke(null,model)!=null) throw new AssertionError("Null author ID conversion");
                    holderType.getMethod("C",modelType,boolean.class).invoke(holder,model,false);
                    draw(row);
                });
            }
            check("native deleted span rebuild and draw", () -> {
                android.text.SpannableStringBuilder original=new android.text.SpannableStringBuilder("probe: message");
                Object name=type("ud7").getConstructors()[0].newInstance(null,null,null,null,"probe","probe",null);
                original.setSpan(name,0,5,33);
                android.text.SpannedString text=new android.text.SpannedString(original);
                Class<?> event=type("tv.twitch.android.core.mvp.viewdelegate.EventDispatcher");
                java.lang.reflect.Method format=type("zmi").getMethod("m",String.class,android.text.SpannedString.class,Context.class,event,boolean.class);
                android.text.Spanned first=(android.text.Spanned)format.invoke(null,"probe-id",text,themed,null,false);
                if(first==null) throw new AssertionError("No deleted span");
                android.text.Spanned rebuilt=(android.text.Spanned)format.invoke(null,"probe-id",new android.text.SpannedString(first),themed,null,false);
                android.widget.TextView view=new android.widget.TextView(themed); view.setText(rebuilt); draw(view);
            });
        });
        Bundle result=new Bundle(); result.putString("stream","\n"+report+"RESULT failed="+failures+"\n");
        finish(failures==0 ? -1 : 0,result);
    }
}
