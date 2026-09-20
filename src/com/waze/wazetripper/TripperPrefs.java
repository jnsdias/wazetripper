package com.waze.wazetripper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Estado de pareamento do WazeTripper, numa prefs própria dentro do processo do Waze (o Waze
 * patchado não enxerga o armazenamento de outros apps, então esse estado começa sempre zerado na
 * primeira instalação do patch).
 */
final class TripperPrefs {

    private static final String PREFS = "wazetripper_pairing";
    private static final String KEY_MAC = "mac";
    private static final String KEY_NAME = "name";
    private static final String SETTINGS = "wazetripper_settings";
    private static final String KEY_CLOCK_12H = "clock12h";
    private static final String KEY_COMPASS = "compassEnabled";
    private static final String KEY_BOTTOM_INFO = "bottomInfoMode";
    private static final String KEY_AUTO_RECONNECT = "autoReconnect";
    private static final String KEY_RADAR_MODE = "radarMode";
    private static final String KEY_CALL_ICON = "callIcon";
    private static final String KEY_DISTANCE_INTENSITY = "distanceIntensity";

    private TripperPrefs() {
    }

    static boolean hasSavedDevice(Context ctx) {
        return prefs(ctx).getString(KEY_MAC, null) != null;
    }

    static String pairedMac(Context ctx) {
        return prefs(ctx).getString(KEY_MAC, null);
    }

    static String pairedName(Context ctx) {
        return prefs(ctx).getString(KEY_NAME, null);
    }

    static void markPaired(Context ctx, String mac, String name) {
        prefs(ctx).edit()
                .putString(KEY_MAC, mac)
                .putString(KEY_NAME, name)
                .apply();
    }

    static void forget(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    // ---------- configuracoes do usuario -------------------------------------------------------
    // Prefs separada da de pareamento: forget() limpa so o pareamento, as configuracoes
    // sobrevivem (default 24h).

    static boolean is12h(Context ctx) {
        return settings(ctx).getBoolean(KEY_CLOCK_12H, false);
    }

    static void set12h(Context ctx, boolean enabled) {
        settings(ctx).edit().putBoolean(KEY_CLOCK_12H, enabled).apply();
    }

    /** O que aparece na parte de baixo da tela de navegacao (TripperProtocol.BOTTOM_*); default distancia total. */
    static int bottomInfoMode(Context ctx) {
        return settings(ctx).getInt(KEY_BOTTOM_INFO, TripperProtocol.BOTTOM_TOTAL_DISTANCE);
    }

    static void setBottomInfoMode(Context ctx, int mode) {
        settings(ctx).edit().putInt(KEY_BOTTOM_INFO, mode).apply();
    }

    /** Radar no Pod: desligado / compativel (0x3C + linha de baixo) / experimental (0x3C + distancia no campo menor [8-9]). */
    static final int RADAR_OFF = 0;
    static final int RADAR_COMPAT = 1;
    static final int RADAR_EXPERIMENTAL = 2;

    static int radarMode(Context ctx) {
        return settings(ctx).getInt(KEY_RADAR_MODE, RADAR_EXPERIMENTAL);
    }

    static void setRadarMode(Context ctx, int mode) {
        settings(ctx).edit().putInt(KEY_RADAR_MODE, mode).apply();
    }

    /** Icone de ligacao no Pod quando o celular toca ou esta em chamada - default ligado. */
    static boolean isCallIcon(Context ctx) {
        return settings(ctx).getBoolean(KEY_CALL_ICON, true);
    }

    static void setCallIcon(Context ctx, boolean enabled) {
        settings(ctx).edit().putBoolean(KEY_CALL_ICON, enabled).apply();
    }

    /** Intensidade do icone conforme a distancia ate a manobra (byte [6]) - default ligado. */
    static boolean isDistanceIntensity(Context ctx) {
        return settings(ctx).getBoolean(KEY_DISTANCE_INTENSITY, true);
    }

    static void setDistanceIntensity(Context ctx, boolean enabled) {
        settings(ctx).edit().putBoolean(KEY_DISTANCE_INTENSITY, enabled).apply();
    }

    /** Procurar sozinho o Tripper conhecido (ao abrir o Waze e apos quedas) - default ligada. */
    static boolean isAutoReconnect(Context ctx) {
        return settings(ctx).getBoolean(KEY_AUTO_RECONNECT, true);
    }

    static void setAutoReconnect(Context ctx, boolean enabled) {
        settings(ctx).edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply();
    }

    /** Bussola sem rota (GPS) - default desligada. */
    static boolean isCompassEnabled(Context ctx) {
        return settings(ctx).getBoolean(KEY_COMPASS, false);
    }

    static void setCompassEnabled(Context ctx, boolean enabled) {
        settings(ctx).edit().putBoolean(KEY_COMPASS, enabled).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences settings(Context ctx) {
        return ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }
}
