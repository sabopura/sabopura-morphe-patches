package io.github.sabopura.morphe.extension.twitch.chat;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.widget.*;

import org.json.*;

public final class UserSearch {
    public static void show(Context c) {
        EditText input = new EditText(c);
        input.setSingleLine();
        input.setHint(Text.label("ログイン名 または 数値ID", "Login name or numeric user ID"));
        AlertDialog dialog =
                new AlertDialog.Builder(c)
                        .setTitle(Text.label("ユーザー検索", "Find user"))
                        .setView(input)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.search_go, null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            String value = input.getText().toString().trim().replaceFirst("^@", "");
                            if (!value.matches("[A-Za-z0-9_]{1,25}")) {
                                input.setError(
                                        Text.label(
                                                "ログイン名またはIDを入力してください", "Enter a login name or ID"));
                                return;
                            }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                            if (!PublicApi.submit(
                                    () -> {
                                        try {
                                            JSONObject body =
                                                    new JSONObject()
                                                            .put(
                                                                    "query",
                                                                    value.matches("[0-9]+")
                                                                            ? "query($value:ID!){user(id:$value){id"
                                                                                  + " login"
                                                                                  + " displayName"
                                                                                  + " description}}"
                                                                            : "query($value:String!){user(login:$value){id"
                                                                                  + " login"
                                                                                  + " displayName"
                                                                                  + " description}}")
                                                            .put(
                                                                    "variables",
                                                                    new JSONObject()
                                                                            .put("value", value));
                                            JSONObject
                                                    result =
                                                            new JSONObject(
                                                                    PublicApi.twitchMetadata(
                                                                            body.toString())),
                                                    data = result.optJSONObject("data"),
                                                    user =
                                                            data == null
                                                                    ? null
                                                                    : data.optJSONObject("user");
                                            if (user == null) throw new IllegalArgumentException();
                                            String login = user.getString("login");
                                            if (!login.matches("[A-Za-z0-9_]{1,25}"))
                                                throw new IllegalArgumentException();
                                            PublicApi.UI.post(
                                                    () -> {
                                                        if (!dialog.isShowing()) return;
                                                        dialog.dismiss();
                                                        new AlertDialog.Builder(c)
                                                                .setTitle(
                                                                        user.optString(
                                                                                "displayName",
                                                                                login))
                                                                .setMessage(
                                                                        "@"
                                                                                + login
                                                                                + " · "
                                                                                + user.optString(
                                                                                        "id")
                                                                                + "\n\n"
                                                                                + user.optString(
                                                                                        "description"))
                                                                .setNegativeButton(
                                                                        android.R.string.cancel,
                                                                        null)
                                                                .setPositiveButton(
                                                                        Text.label(
                                                                                "チャンネルを開く",
                                                                                "Open channel"),
                                                                        (d, i) -> {
                                                                            try {
                                                                                c.startActivity(
                                                                                        new Intent(
                                                                                                        Intent
                                                                                                                .ACTION_VIEW,
                                                                                                        Uri
                                                                                                                .parse(
                                                                                                                        "https://www.twitch.tv/"
                                                                                                                                + login))
                                                                                                .setPackage(
                                                                                                        c
                                                                                                                .getPackageName()));
                                                                            } catch (Exception e) {
                                                                                Toast.makeText(
                                                                                                c,
                                                                                                Text
                                                                                                        .label(
                                                                                                                "チャンネルを開けません",
                                                                                                                "Unable"
                                                                                                                    + " to open"
                                                                                                                    + " channel"),
                                                                                                Toast
                                                                                                        .LENGTH_SHORT)
                                                                                        .show();
                                                                            }
                                                                        })
                                                                .show();
                                                    });
                                        } catch (Exception e) {
                                            PublicApi.UI.post(
                                                    () -> {
                                                        if (dialog.isShowing()) {
                                                            dialog.getButton(
                                                                            AlertDialog
                                                                                    .BUTTON_POSITIVE)
                                                                    .setEnabled(true);
                                                            input.setError(
                                                                    Text.label(
                                                                            "見つからないか、検索サービスに接続できません",
                                                                            "User not found or"
                                                                                + " lookup"
                                                                                + " unavailable"));
                                                        }
                                                    });
                                        }
                                    }))
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        });
    }
}
