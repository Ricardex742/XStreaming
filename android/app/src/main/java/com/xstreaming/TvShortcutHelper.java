package com.xstreaming;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.Collections;

public class TvShortcutHelper {
    public static Context sAppContext;

    public static boolean isSupported(Object... args) {
        if (args != null) {
            for (Object a : args) {
                if (a instanceof Context) {
                    sAppContext = ((Context) a).getApplicationContext();
                }
            }
        }
        return true;
    }

    public static void handleIncomingIntent(Activity activity) {
        if (activity == null) return;
        sAppContext = activity.getApplicationContext();
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return;
            ComponentName comp = intent.getComponent();
            if (comp != null && comp.getClassName() != null && comp.getClassName().startsWith("com.xstreaming.TvGameSlot")) {
                String slotKey = comp.getClassName().substring("com.xstreaming.".length());
                SharedPreferences prefs = activity.getSharedPreferences("tv_game_slots", Context.MODE_PRIVATE);
                String savedUri = prefs.getString(slotKey + "_uri", null);
                if (savedUri != null) {
                    Intent gameIntent = Intent.parseUri(savedUri, Intent.URI_INTENT_SCHEME);
                    activity.setIntent(gameIntent);
                }
            }
        } catch (Exception ignored) {}
    }

    public static boolean pinOnTv(Object... args) {
        if (Build.VERSION.SDK_INT < 26 || args == null) return true;
        ShortcutManager sm = null;
        ShortcutInfo info = null;
        IntentSender sender = null;
        for (Object a : args) {
            if (a instanceof Context) {
                sAppContext = ((Context) a).getApplicationContext();
            } else if (a instanceof ShortcutManager) {
                sm = (ShortcutManager) a;
            } else if (a instanceof ShortcutInfo) {
                info = (ShortcutInfo) a;
            } else if (a instanceof IntentSender) {
                sender = (IntentSender) a;
            } else if (a != null && a.getClass().getName().endsWith("ShortcutInfoCompat")) {
                try {
                    Object si = a.getClass().getMethod("toShortcutInfo").invoke(a);
                    if (si instanceof ShortcutInfo) info = (ShortcutInfo) si;
                } catch (Exception ignored) {}
            }
        }
        if (info == null) return true;
        try {
            if (sm == null && sAppContext != null) {
                sm = sAppContext.getSystemService(ShortcutManager.class);
            }
            if (sm != null) {
                sm.addDynamicShortcuts(Collections.singletonList(info));
                try { sm.requestPinShortcut(info, sender); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        Context ctx = sAppContext;
        if (ctx != null) {
            String id = info.getId() != null ? info.getId() : "game_" + System.currentTimeMillis();
            String title = info.getShortLabel() != null ? info.getShortLabel().toString() : "Jogo Xbox";
            Intent launchIntent = info.getIntent();
            if (launchIntent == null) {
                launchIntent = new Intent(ctx, MainActivity.class);
                launchIntent.setAction(Intent.ACTION_VIEW);
            }
            launchIntent.setPackage(ctx.getPackageName());
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            String intentUri = launchIntent.toUri(Intent.URI_INTENT_SCHEME);
            String posterUri = "https://compass-ssl.xbox.com/assets/16/9a/169a7d4c-2560-4f42-b197-11257b42b93e.jpg";
            publishToWatchNext(ctx, id, title, posterUri, intentUri);
            publishToTvChannel(ctx, id, title, posterUri, intentUri);
            enableAppRowSlot(ctx, title, intentUri);
            final String finalTitle = title;
            final Context finalCtx = ctx;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(finalCtx, "Atalho de '" + finalTitle + "' adicionado na Tela Inicial da TV!", Toast.LENGTH_LONG).show();
                }
            });
        }
        return true;
    }

    private static void publishToWatchNext(Context ctx, String id, String title, String posterUri, String intentUri) {
        try {
            ContentValues values = new ContentValues();
            values.put("type", 0);
            values.put("watch_next_type", 0);
            values.put("last_engagement_time_utc_millis", System.currentTimeMillis());
            values.put("title", title);
            values.put("short_description", "Xbox Cloud Gaming - Abrir jogo direto");
            values.put("poster_art_uri", posterUri);
            values.put("poster_art_aspect_ratio", 4);
            values.put("intent_uri", intentUri);
            values.put("internal_provider_id", id);
            ctx.getContentResolver().insert(Uri.parse("content://android.media.tv/watch_next_program"), values);
        } catch (Exception ignored) {}
    }

    private static void publishToTvChannel(Context ctx, String id, String title, String posterUri, String intentUri) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("tv_game_slots", Context.MODE_PRIVATE);
            long channelId = prefs.getLong("xbox_channel_id", -1L);
            if (channelId == -1L) {
                ContentValues cv = new ContentValues();
                cv.put("type", "TYPE_PREVIEW");
                cv.put("display_name", "Meus Jogos Xbox");
                cv.put("description", "Atalhos de jogos do Xbox Cloud Gaming");
                Intent mainIntent = new Intent(ctx, MainActivity.class);
                cv.put("app_link_intent_uri", mainIntent.toUri(Intent.URI_INTENT_SCHEME));
                Uri chUri = ctx.getContentResolver().insert(Uri.parse("content://android.media.tv/channel"), cv);
                if (chUri != null) {
                    channelId = ContentUris.parseId(chUri);
                    prefs.edit().putLong("xbox_channel_id", channelId).apply();
                    try {
                        Class.forName("android.media.tv.TvContract")
                            .getMethod("requestChannelBrowsable", Context.class, long.class)
                            .invoke(null, ctx, channelId);
                    } catch (Exception ignored) {}
                }
            }
            if (channelId != -1L) {
                ContentValues pv = new ContentValues();
                pv.put("channel_id", channelId);
                pv.put("type", 0);
                pv.put("title", title);
                pv.put("short_description", "Xbox Cloud Gaming");
                pv.put("poster_art_uri", posterUri);
                pv.put("poster_art_aspect_ratio", 4);
                pv.put("intent_uri", intentUri);
                pv.put("internal_provider_id", id);
                pv.put("weight", 100);
                ctx.getContentResolver().insert(Uri.parse("content://android.media.tv/preview_program"), pv);
            }
        } catch (Exception ignored) {}
    }

    private static void enableAppRowSlot(Context ctx, String title, String intentUri) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("tv_game_slots", Context.MODE_PRIVATE);
            int nextSlot = (prefs.getInt("last_slot", 0) % 5) + 1;
            String slotKey = "TvGameSlot" + nextSlot;
            prefs.edit().putInt("last_slot", nextSlot).putString(slotKey + "_uri", intentUri).putString(slotKey + "_title", title).apply();
            ComponentName comp = new ComponentName(ctx.getPackageName(), "com.xstreaming." + slotKey);
            ctx.getPackageManager().setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }
}