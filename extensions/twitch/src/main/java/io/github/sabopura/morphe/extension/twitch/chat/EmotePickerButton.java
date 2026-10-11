package io.github.sabopura.morphe.extension.twitch.chat;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;

import io.github.sabopura.morphe.extension.twitch.chat.emotes.EmotePicker;

/** Additional picker; Twitch's original picker and input controller remain in charge of sending. */
public final class EmotePickerButton extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EmotePickerButton(Context c, AttributeSet a) {
        super(c, a);
        setClickable(true);
        setFocusable(true);
        setContentDescription(
                Text.label("追加エモート・お気に入り", "Additional emotes and favorites"));
        setOnClickListener(
                v -> {
                    EditText input = input();
                    if (input != null) EmotePicker.show(input);
                });
    }

    private EditText input() {
        int id = getResources().getIdentifier("message_input", "id", getContext().getPackageName());
        View v = getRootView().findViewById(id);
        return v instanceof EditText ? (EditText) v : null;
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(
                () -> {
                    EditText v = input();
                    if (v != null) EmotePicker.attach(v);
                });
    }

    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float d = getResources().getDisplayMetrics().density,
                x = getWidth() / 2f,
                y = getHeight() / 2f;
        p.setColor(0xffadadb8);
        p.setStrokeWidth(1.8f * d);
        p.setStyle(Paint.Style.STROKE);
        c.drawCircle(x, y, 9 * d, p);
        c.drawArc(x - 5 * d, y - 4 * d, x + 5 * d, y + 5 * d, 15, 150, false, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x - 3 * d, y - 3 * d, d, p);
        c.drawCircle(x + 3 * d, y - 3 * d, d, p);
    }
}
