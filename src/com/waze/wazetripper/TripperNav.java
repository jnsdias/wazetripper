package com.waze.wazetripper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * TripperNav - guarda o estado de navegacao que o Waze informa (via NavHooks), monta o pacote de
 * navegacao (CMD_NAVIGATE) e o envia ao Pod: na hora quando algo muda (com um pequeno atraso pra
 * juntar varias atualizacoes seguidas, comuns no recalculo da rota) e reenviado a cada REFRESH_MS
 * mesmo sem mudanca - a tela de navegacao tem o mesmo watchdog da bussola (o Pod derruba o link se
 * nao chegar pacote novo em poucos segundos).
 *
 * Campos do pacote (ver docs/PROTOCOL.md):
 *   [2]     manobra atual                  [3-5]   distancia ate a manobra
 *   [6]     modo noturno (tema do Waze)    [7]     proxima manobra (seta pequena) ou 0x3C = radar
 *   [8-9]   com radar a frente: distancia ate o radar (uint16, metros; FF FF sem radar). HIPOTESE: o
 *           Pod parece ter um campo de distancia menor ao lado da seta pequena; ainda nao verificado.
 *   [11-13] parte de baixo: distancia total, tempo restante ou hora de chegada (escolha do painel).
 *           O radar NUNCA escreve aqui: o total da rota continua visivel durante o alerta.
 *
 * Quem decide QUANDO enviar e' o TripperBridge (arbitragem navegacao > bussola > relogio): ele liga
 * setSending(true) so' com o link conectado e a navegacao ativa. Mesmo sem enviar, o pacote que
 * SERIA enviado e' registrado no log - da pra validar a traducao sem o Pod.
 *
 * Os hooks chegam de threads do Waze; tudo e' repassado pra main thread, onde o estado vive.
 */
final class TripperNav {

    private TripperNav() {
    }

    private static final String TAG = "WazeTripper";
    private static final long REFRESH_MS = 2000L;
    private static final long DEBOUNCE_MS = 150L;
    // Radar so' aparece no Pod a ate 300 m (regra de outro app do Tripper: procura a 700 m, exibe a 300 m).
    private static final int RADAR_MAX_M = 300;
    private static final long ROUTE_START_MS = 2000L;        // tempo da tela de "rota iniciada"
    private static final long ROUTE_START_WINDOW_MS = 5000L; // so' vale se o link estiver pronto logo no inicio
    private static final long RECALC_MAX_MS = 8000L;         // teto da tela de "recalculando"

    private static final Handler main = new Handler(Looper.getMainLooper());

    private static volatile boolean navigating;
    private static boolean sending;

    private static String typeName;
    private static int exit = -1;
    private static int maneuver = -1;   // byte [2]; -1 = ainda sem manobra valida
    private static String nextTypeName;
    private static int nextExit = -1;
    private static int next = TripperProtocol.NEXT_NONE; // byte [7]
    private static int distanceM = -1;  // metros ate a manobra; -1 = sem dado
    private static int totalM = -1;     // metros ate o destino; -1 = sem dado
    private static int etaMinutes = -1; // minutos ate a chegada; -1 = sem dado
    // Radar a frente: todas as fontes viram o mesmo aviso no Pod (o Pod nao tem icone de velocidade
    // recomendada). Fontes com distancia usam a mais proxima; sem distancia, so' acende o radar.
    private static int alertRadarM = -1;   // alerta CAMERA ativo mais proximo; -1 = nenhum
    private static boolean avgCamActive;   // dentro/perto de um trecho de velocidade media
    private static int avgCamLeftM = -1;   // metros que faltam do trecho; -1 = sem dado
    private static boolean zoneActive;     // zona de fiscalizacao ativa (o Waze nao da' distancia)

    private static long flourishUntil;   // elapsedRealtime ate quando ainda vale mostrar "rota iniciada"; 0 = nao
    private static long holdUntil;       // a tela de "rota iniciada" segura as demais ate este instante
    private static long recalcUntil;     // Waze recalculando (distancia -1) ate este instante; 0 = nao

    private static boolean sendPending;
    private static String lastLogged;

    private static final Runnable debouncedSend = () -> {
        sendPending = false;
        sendNow();
    };

    private static final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!sending) return;
            sendNow();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    static boolean isNavigating() {
        return navigating;
    }

    // ---------- entradas (NavHooks, qualquer thread) ------------------------------------------

    static void onNavState(boolean nav) {
        main.post(() -> {
            if (nav == navigating) return;
            navigating = nav;
            if (nav) flourishUntil = SystemClock.elapsedRealtime() + ROUTE_START_WINDOW_MS;
            // O trajeto gravado (TripperLog) acompanha a navegacao do Waze: um arquivo por rota.
            if (nav) TripperLog.startTrip(tripHeader());
            else TripperLog.endTrip();
            // So' zera ao terminar: no inicio de uma rota alguns callbacks de manobra podem chegar
            // antes do estado "navegando", e nao podem ser apagados.
            if (!nav) reset();
            requestModeRefresh();
        });
    }

    static void onCurrent(String name) {
        main.post(() -> {
            typeName = name;
            int b = TripperProtocol.maneuverByte(typeName, exit);
            if (b >= 0) maneuver = b; // sem traducao: mantem a anterior valida
            else logUntranslated("atual", name);
            scheduleSend();
        });
    }

    static void onExit(int e) {
        main.post(() -> {
            exit = e;
            int b = TripperProtocol.maneuverByte(typeName, exit);
            if (b >= 0) maneuver = b;
            scheduleSend();
        });
    }

    static void onNext(String name) {
        main.post(() -> {
            nextTypeName = name;
            next = TripperProtocol.nextManeuverByte(nextTypeName, nextExit);
            if (next == TripperProtocol.NEXT_NONE && !"LAST_DIRECTION".equals(name)) logUntranslated("proxima", name);
            scheduleSend();
        });
    }

    static void onNextExit(int e) {
        main.post(() -> {
            nextExit = e;
            next = TripperProtocol.nextManeuverByte(nextTypeName, nextExit);
            scheduleSend();
        });
    }

    static void onDistance(int meters) {
        main.post(() -> {
            // -1 e' o "vazio" que o Waze manda no inicio e a cada recalculo. No inicio (ainda sem distancia
            // valida) e' ignorado; depois de uma distancia valida e' um recalculo: tela de "recalculando".
            if (meters < 0) {
                if (distanceM >= 0) {
                    recalcUntil = SystemClock.elapsedRealtime() + RECALC_MAX_MS;
                    scheduleSend();
                }
                return;
            }
            recalcUntil = 0;
            distanceM = meters;
            scheduleSend();
        });
    }

    static void onEtaDistance(int meters) {
        main.post(() -> {
            if (meters < 0) return;
            totalM = meters;
            scheduleSend();
        });
    }

    static void onEtaMinutes(int minutes) {
        main.post(() -> {
            if (minutes < 0) return;
            etaMinutes = minutes;
            scheduleSend();
        });
    }

    /** Radar ativo a frente (metros) ou -1 quando nao ha (vem dos alertas do Waze, tipo CAMERA). */
    static void onRadar(int meters) {
        main.post(() -> {
            if (meters == alertRadarM) return;
            alertRadarM = meters;
            scheduleSend();
        });
    }

    /** Trecho de velocidade media: ativo ou nao, com os metros que faltam do trecho (-1 = sem dado). */
    static void onAvgSpeedCam(boolean active, int leftMeters) {
        main.post(() -> {
            int left = active ? leftMeters : -1;
            if (active == avgCamActive && left == avgCamLeftM) return;
            avgCamActive = active;
            avgCamLeftM = left;
            scheduleSend();
        });
    }

    /** Zona de fiscalizacao ativa ou nao (sem distancia: so' acende o radar no Pod). */
    static void onEnforcementZone(boolean active) {
        main.post(() -> {
            if (active == zoneActive) return;
            zoneActive = active;
            scheduleSend();
        });
    }

    /** Reenvia o pacote atual na hora (ex.: a ligacao terminou e a tela deve voltar). */
    static void resendNow() {
        main.post(TripperNav::sendNow);
    }

    // ---------- controle (TripperBridge, main thread) -----------------------------------------

    /** Liga/desliga o envio ao Pod (o TripperBridge liga so' conectado e com a navegacao ativa). */
    static void setSending(boolean on) {
        if (on == sending) return;
        sending = on;
        main.removeCallbacks(refresh);
        if (on) {
            sendNow();
            main.postDelayed(refresh, REFRESH_MS);
        }
    }

    // ---------- internos ----------------------------------------------------------------------

    private static void reset() {
        typeName = null;
        exit = -1;
        maneuver = -1;
        nextTypeName = null;
        nextExit = -1;
        next = TripperProtocol.NEXT_NONE;
        distanceM = -1;
        totalM = -1;
        etaMinutes = -1;
        alertRadarM = -1;
        avgCamActive = false;
        avgCamLeftM = -1;
        zoneActive = false;
        flourishUntil = 0;
        holdUntil = 0;
        recalcUntil = 0;
        lastLogged = null;
    }

    /** Contexto gravado no inicio de cada trajeto: estado do Pod e preferencias que mudam o pacote. */
    private static String tripHeader() {
        TripperBridge b = TripperBridge.peek();
        if (b == null) return "Tripper: (bridge ainda nao criado)";
        Context ctx = b.context();
        int mode = TripperPrefs.bottomInfoMode(ctx);
        return "Tripper: " + b.getState()
                + "\nPreferencias: hora " + (TripperPrefs.is12h(ctx) ? "12h" : "24h")
                + ", informacao inferior "
                + (mode == TripperProtocol.BOTTOM_TIME_REMAINING ? "tempo restante"
                : mode == TripperProtocol.BOTTOM_ARRIVAL_TIME ? "hora de chegada" : "distancia total")
                + ", bussola " + (TripperPrefs.isCompassEnabled(ctx) ? "ligada" : "desligada")
                + "\nRecursos: radar " + new String[]{"desligado", "compativel", "experimental"}[Math.max(0, Math.min(2, TripperPrefs.radarMode(ctx)))]
                + ", icone de ligacao " + (TripperPrefs.isCallIcon(ctx) ? "ligado" : "desligado")
                + ", intensidade por distancia " + (TripperPrefs.isDistanceIntensity(ctx) ? "ligada" : "desligada");
    }

    /** Manobra do Waze sem byte no Pod: avisa no log (Log.w) pra saber o que falta mapear. */
    private static void logUntranslated(String which, String name) {
        if (name == null || name.contains("NONE")) return; // "sem manobra" e' legitimo, nao e' lacuna
        TripperLog.w(TAG, "NAV manobra " + which + " sem traducao pro Tripper: " + name);
    }

    /** Metros ate o radar mais proximo entre as fontes com distancia, se estiver a ate RADAR_MAX_M; senao -1. */
    private static int radarMeters() {
        int m = alertRadarM;
        if (avgCamActive && avgCamLeftM >= 0 && (m < 0 || avgCamLeftM < m)) m = avgCamLeftM;
        return m >= 0 && m <= RADAR_MAX_M ? m : -1;
    }

    private static void scheduleSend() {
        if (sendPending) return;
        sendPending = true;
        main.postDelayed(debouncedSend, DEBOUNCE_MS);
    }

    private static void requestModeRefresh() {
        TripperBridge b = TripperBridge.peek();
        if (b != null) b.refreshMode();
    }

    private static void sendNow() {
        try {
            if (!navigating) return;
            TripperBridge bridge = TripperBridge.peek();
            if (bridge == null) return;
            Context ctx = bridge.context();
            if (bridge.isCallActive()) return; // ligacao tem prioridade: o keepalive do bridge manda o icone dela

            boolean night = TripperNight.isNight();
            long nowMs = SystemClock.elapsedRealtime();
            if (sending && flourishUntil > nowMs) {
                // Inicio da rota: tela de "rota iniciada" (icone grande 0x45) por ~2 s, antes da primeira manobra.
                flourishUntil = 0;
                holdUntil = nowMs + ROUTE_START_MS;
                byte[] start = TripperProtocol.buildNavPacket(TripperProtocol.MANEUVER_ROUTE_STARTED, 0,
                        TripperProtocol.NEXT_NONE, -1, new int[]{0xFF, 0xFF, 0xFF}, night ? 1 : 0);
                TripperLog.i(TAG, "NAV inicio de rota: tela 'rota iniciada' (0x45) por " + ROUTE_START_MS + " ms -> "
                        + TripperProtocol.toHexString(start));
                bridge.sendNavPacket(start);
                main.postDelayed(TripperNav::sendNow, ROUTE_START_MS + 50);
                return;
            }
            if (holdUntil > nowMs) return; // a tela de "rota iniciada" ainda esta no ar
            if (maneuver < 0) return;
            if (recalcUntil > nowMs) {
                // Waze recalculando a rota: tela "carregando/recalculando" (0x1C) em vez da ultima manobra.
                byte[] loading = TripperProtocol.buildScreenPacket(TripperProtocol.SCREEN_LOADING, night);
                String d = "recalculando (tela 0x1C) -> " + TripperProtocol.toHexString(loading);
                if (!d.equals(lastLogged)) {
                    lastLogged = d;
                    TripperLog.i(TAG, "NAV " + d);
                }
                if (sending) bridge.sendNavPacket(loading);
                return;
            }
            int nextByte = next;
            int nextDistance = -1;
            int mode = TripperPrefs.bottomInfoMode(ctx);
            int[] bottom = TripperProtocol.buildBottomInfoBytes(mode, etaMinutes, totalM, TripperPrefs.is12h(ctx));
            String bottomDesc = (mode == TripperProtocol.BOTTOM_TIME_REMAINING ? "tempo " + etaMinutes + " min"
                    : mode == TripperProtocol.BOTTOM_ARRIVAL_TIME ? "chegada em " + etaMinutes + " min"
                    : "total " + totalM + " m");
            int radarM = radarMeters();
            int radarMode = TripperPrefs.radarMode(ctx);
            if (radarMode != TripperPrefs.RADAR_OFF && (radarM >= 0 || avgCamActive || zoneActive)) {
                if (radarMode == TripperPrefs.RADAR_COMPAT) {
                    // Como outro app do Tripper: 0x3C na seta pequena e a distancia na linha de baixo.
                    nextByte = TripperProtocol.NEXT_RADAR;
                    if (radarM >= 0) {
                        bottom = TripperProtocol.encodeDistance(radarM);
                        bottomDesc = "radar " + radarM + " m (linha de baixo)";
                    } else {
                        bottomDesc += " | radar (sem distancia)";
                    }
                } else {
                    // Experimental: 0x3C na seta pequena e a distancia no campo menor [8-9]; a linha de baixo fica.
                    nextByte = TripperProtocol.NEXT_RADAR;
                    nextDistance = radarM;
                    bottomDesc += " | radar " + (radarM >= 0 ? radarM + " m em [8-9]" : "(sem distancia)");
                }
            }
            int byte6 = TripperPrefs.isDistanceIntensity(ctx)
                    ? TripperProtocol.distanceIntensity(distanceM < 0 ? Integer.MAX_VALUE : distanceM, night)
                    : (night ? 1 : 0);
            byte[] pkt = TripperProtocol.buildNavPacket(maneuver, Math.max(distanceM, 0), nextByte, nextDistance, bottom, byte6);

            String desc = String.format("manobra=0x%02X (%s, saida=%d) dist=%d m | proxima=0x%02X (%s) | baixo=%s | b6=0x%02X %s -> %s",
                    maneuver, typeName, exit, distanceM, nextByte, nextTypeName, bottomDesc, byte6,
                    night ? "noite" : "dia", TripperProtocol.toHexString(pkt));
            if (!desc.equals(lastLogged)) {
                lastLogged = desc;
                TripperLog.i(TAG, "NAV pacote" + (sending ? "" : " (sem envio)") + ": " + desc);
            }

            if (!sending) return;
            bridge.sendNavPacket(pkt);
        } catch (Throwable t) {
            TripperLog.e(TAG, "TripperNav.sendNow() falhou", t);
        }
    }
}
