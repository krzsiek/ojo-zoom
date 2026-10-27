package it.danieleverducci.ojo;

import android.content.Context;
import android.content.SharedPreferences;

public class SharedPreferencesManager {
    private static final String SP_ROTATION_ENABLED = "rot_en";
    private static final String SP_MAX_RECORDING_MINUTES = "max_rec_min";
    public static final int[] MAX_RECORDING_MINUTES_CHOICES = {1, 5, 10, 15, 30, 60};
    public static final int DEFAULT_MAX_RECORDING_MINUTES = 10;

    public static void saveRotationEnabled(Context ctx, boolean enabled) {
        SharedPreferences sharedPref = ctx.getSharedPreferences(SP_ROTATION_ENABLED, Context.MODE_PRIVATE);
        sharedPref.edit().putBoolean(SP_ROTATION_ENABLED, enabled).apply();
    }

    public static boolean loadRotationEnabled(Context ctx) {
        SharedPreferences sharedPref = ctx.getSharedPreferences(SP_ROTATION_ENABLED, Context.MODE_PRIVATE);
        return sharedPref.getBoolean(SP_ROTATION_ENABLED, true);   // Ojo Zoom: follow the device rotation by default
    }

    public static void toggleRotationEnabled(Context ctx) {
        saveRotationEnabled(ctx, ! loadRotationEnabled(ctx));
    }

    public static void saveMaxRecordingMinutes(Context ctx, int minutes) {
        SharedPreferences sharedPref = ctx.getSharedPreferences(SP_MAX_RECORDING_MINUTES, Context.MODE_PRIVATE);
        sharedPref.edit().putInt(SP_MAX_RECORDING_MINUTES, minutes).apply();
    }

    public static int loadMaxRecordingMinutes(Context ctx) {
        SharedPreferences sharedPref = ctx.getSharedPreferences(SP_MAX_RECORDING_MINUTES, Context.MODE_PRIVATE);
        return sharedPref.getInt(SP_MAX_RECORDING_MINUTES, DEFAULT_MAX_RECORDING_MINUTES);
    }
}
