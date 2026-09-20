package com.waze.wazetripper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

/**
 * CompassManager - manda o rumo (GPS) pro Tripper Pod mostrar a bussola quando nao ha navegacao
 * (o "sem rota"). Porta de uma implementacao anterior do autor (validada em hardware):
 *
 * - Usa GPS (LocationManager.GPS_PROVIDER), nao o magnetometro: o rumo do proprio deslocamento
 *   e' bem mais estavel numa moto do que a bussola magnetica (interferencia de metal/eletronica).
 * - No maximo 1 atualizacao por segundo.
 * - So troca de setor (uma das 8 direcoes) depois de girar mais de ~30,5 graus a partir do centro
 *   do setor atual - sem essa zona morta a seta tremeria entre duas direcoes vizinhas.
 *
 * IMPORTANTE (achado em teste real no Pod): o firmware do Pod tem um watchdog proprio da
 * TELA da bussola (0x41) - se ela for a ultima tela mostrada e nao chegar pacote novo em poucos
 * segundos, o Pod derruba a conexao sozinho (um pacote unico -> queda ~5s depois; rajada
 * continua -> sem queda). Por isso a ultima direcao conhecida e' reenviada a cada
 * REFRESH_INTERVAL_MS mesmo sem rumo novo do GPS (parado no semaforo, GPS ruim, etc.).
 *
 * Tudo roda na main thread: quem chama (TripperBridge) posta start()/stop() no main handler.
 */
final class CompassManager {

    // Bem abaixo dos ~5s que causaram queda nos testes - margem confortavel.
    private static final long REFRESH_INTERVAL_MS = 2000L;
    private static final float HYSTERESIS_DEG = 30.5f;

    private final Context appContext;
    private final TripperBridge bridge;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private int currentSector = -1;
    private int lastLoggedSector = -1;
    private boolean active;

    // Ultima direcao mandada ao Pod: e' o que o laco de refresh reenvia sem rumo novo do GPS.
    private byte lastDirection = TripperProtocol.bearingToDirection(0f);
    private Runnable refreshRunnable;

    private final LocationListener locationListener = location -> {
        if (location.hasBearing()) onBearing(location.getBearing());
    };

    CompassManager(Context appContext, TripperBridge bridge) {
        this.appContext = appContext;
        this.bridge = bridge;
    }

    boolean isActive() {
        return active;
    }

    @SuppressLint("MissingPermission")
    void start() {
        if (active) return;
        if (appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            bridge.log("Bussola: permissao de localizacao nao concedida - nao iniciada.");
            return;
        }
        active = true;
        lastLoggedSector = -1;
        bridge.log("Bussola: ativada, esperando rumo do GPS (so vem com deslocamento real)...");
        startRefreshLoop();
        try {
            LocationManager lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            locationManager = lm;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener,
                    Looper.getMainLooper());
            android.location.Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && last.hasBearing()) onBearing(last.getBearing());
        } catch (Throwable t) {
            bridge.log("Bussola: erro ao pedir localizacao - " + t.getMessage());
            TripperLog.e("WazeTripper", "CompassManager.start() falhou", t);
            active = false;
            stopRefreshLoop();
            locationManager = null;
        }
    }

    void stop() {
        if (!active && locationManager == null) return;
        bridge.log("Bussola: desativada.");
        active = false;
        currentSector = -1;
        lastLoggedSector = -1;
        stopRefreshLoop();
        try {
            if (locationManager != null) locationManager.removeUpdates(locationListener);
        } catch (Throwable ignored) {
            // Sem permissao pra remover nao e' motivo pra derrubar o app - a referencia e' zerada abaixo.
        }
        locationManager = null;
    }

    private void onBearing(float bearing) {
        int sector = sectorWithHysteresis(bearing);
        byte direction = TripperProtocol.bearingToDirection(sector * 45f);
        lastDirection = direction;
        if (sector != lastLoggedSector) {
            lastLoggedSector = sector;
            bridge.log("Bussola: rumo " + (int) bearing + " graus -> setor " + sector + " -> pacote enviado ao Tripper");
        }
        bridge.sendCompassPacket(direction);
    }

    /**
     * Reenvia a ultima direcao conhecida a cada REFRESH_INTERVAL_MS, com ou sem rumo novo do GPS.
     * Quando um rumo novo chega (onBearing) ele ja e' mandado na hora, por fora desse laco - o laco
     * e' so' a rede de seguranca pros intervalos sem fix novo (ver comentario da classe).
     */
    private void startRefreshLoop() {
        stopRefreshLoop();
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                bridge.sendCompassPacket(lastDirection);
                handler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        };
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void stopRefreshLoop() {
        if (refreshRunnable != null) handler.removeCallbacks(refreshRunnable);
        refreshRunnable = null;
    }

    private int sectorWithHysteresis(float bearing) {
        int raw = (int) (((bearing + 22.5f) % 360f) / 45f);
        raw = Math.max(0, Math.min(7, raw));
        int current = currentSector;
        if (current < 0 || raw == current) {
            currentSector = raw;
            return raw;
        }
        float diff = Math.abs(bearing - current * 45f) % 360f;
        if (diff > 180f) diff = 360f - diff;
        if (diff > HYSTERESIS_DEG) currentSector = raw;
        return currentSector;
    }
}
