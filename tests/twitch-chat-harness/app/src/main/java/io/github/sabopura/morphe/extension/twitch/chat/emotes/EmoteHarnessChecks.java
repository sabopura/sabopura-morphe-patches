package io.github.sabopura.morphe.extension.twitch.chat.emotes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EmoteHarnessChecks {
    public static void checkSvg() throws Exception {
        java.lang.reflect.Method decode =
                EmoteImageLoader.class.getDeclaredMethod(
                        "decode", byte[].class, boolean.class, int.class);
        decode.setAccessible(true);
        Object decoded =
                decode.invoke(
                        null,
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\"><path fill=\"red\" d=\"M0 0h18v18H0z\"/></svg>"
                                .getBytes(StandardCharsets.UTF_8),
                        false,
                        36);
        java.lang.reflect.Field field = decoded.getClass().getDeclaredField("bitmap");
        field.setAccessible(true);
        Bitmap bitmap = (Bitmap) field.get(decoded);
        if (bitmap.getWidth() != 36
                || bitmap.getHeight() != 36
                || bitmap.getPixel(18, 18) != Color.RED) throw new AssertionError("SVG rendering");
        bitmap.recycle();
        boolean rejected = false;
        try {
            decode.invoke(
                    null,
                    "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///synthetic'>]><svg/>"
                            .getBytes(StandardCharsets.UTF_8),
                    false,
                    36);
        } catch (java.lang.reflect.InvocationTargetException e) {
            rejected = e.getCause() instanceof java.io.IOException;
        }
        if (!rejected) throw new AssertionError("SVG external entities");
    }

    public static void checkInsertion(Context context) throws Exception {
        android.widget.EditText input = new android.widget.EditText(context);
        input.setText("hello :Se tail");
        input.setSelection(9);
        java.lang.reflect.Method insert =
                EmotePicker.class.getDeclaredMethod(
                        "insert", android.widget.EditText.class, String.class, boolean.class);
        insert.setAccessible(true);
        insert.invoke(null, input, "SevenTest", true);
        if (!input.getText().toString().equals("hello SevenTest  tail"))
            throw new AssertionError("completion boundary");
        input.setText("replace this");
        input.setSelection(0, 7);
        insert.invoke(null, input, "FfzTest", false);
        if (!input.getText().toString().equals("FfzTest  this"))
            throw new AssertionError("picker selection");
    }

    public static void run(Context c) throws Exception {
        String ffz =
                "{\"default_sets\":[3],\"sets\":{\"3\":{\"emoticons\":[{\"name\":\"FfzTest\",\"urls\":{\"2\":\"https://cdn.frankerfacez.com/emote/9/2\"}}]},\"4\":{\"emoticons\":[{\"name\":\"NotGlobal\",\"urls\":{\"1\":\"https://cdn.frankerfacez.com/emote/1/1\"}}]}}}";
        Map<String, Emote> parsed = new LinkedHashMap<>();
        EmoteCatalog.parseFfz(new JSONObject(ffz), parsed, true);
        if (parsed.size() != 1 || !parsed.containsKey("FfzTest"))
            throw new AssertionError("FFZ default_sets");
        parsed.clear();
        EmoteCatalog.parseFfz(new JSONObject(ffz), parsed, false);
        if (parsed.size() != 2) throw new AssertionError("FFZ channel sets");
        File catalog = new File(c.getCacheDir(), "morphe_emote_catalog");
        catalog.mkdirs();
        write(new File(catalog, "ffz-global.json"), ffz);
        write(
                new File(catalog, "7tv-global.json"),
                "{\"emotes\":[{\"name\":\"SevenTest\",\"data\":{\"host\":{\"url\":\"//cdn.7tv.app/emote/test\",\"files\":[{\"name\":\"2x.webp\",\"format\":\"WEBP\"}]}}},{\"name\":\"OverlayTest\",\"flags\":1,\"data\":{\"host\":{\"url\":\"//cdn.7tv.app/emote/overlay\",\"files\":[{\"name\":\"2x.webp\",\"format\":\"WEBP\"}]}}}]}");
        write(new File(catalog, "bttv-global.json"), "[{\"id\":\"test\",\"code\":\"BttvTest\"}]");
        for (String p : new String[] {"7tv", "bttv", "ffz"})
            write(new File(catalog, p + "-channel-1234.json"), "{}");
        image(c, "https://cdn.7tv.app/emote/test/2x.webp", Color.MAGENTA);
        image(c, "https://cdn.7tv.app/emote/overlay/2x.webp", 0x66ffffff);
        image(c, "https://cdn.betterttv.net/emote/test/2x.webp", Color.CYAN);
        image(c, "https://cdn.frankerfacez.com/emote/9/2", Color.YELLOW);
    }

    private static void write(File f, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void image(Context c, String url, int color) throws Exception {
        File dir = new File(c.getCacheDir(), "morphe_emote_images");
        dir.mkdirs();
        StringBuilder key = new StringBuilder();
        for (byte b :
                MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)))
            key.append(String.format("%02x", b & 255));
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream out = new FileOutputStream(new File(dir, key + ".bin"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
