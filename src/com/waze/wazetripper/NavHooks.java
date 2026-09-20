package com.waze.wazetripper;


/**
 * NavHooks - pontos de entrada chamados pelos hooks smali injetados em
 * com.waze.navigate.NavigationInfoNativeManager (ver patches/apply_patches_wazetripper.py).
 *
 * Registra no log o que o Waze informa (manobra atual/proxima, distancias, ETA, estado da
 * navegacao), pra validar via logcat que os dados chegam e com que cadencia, e repassa
 * manobra atual, saida, distancias e estado ao TripperNav (2b), que monta e envia o pacote ao Pod. Filtro: adb logcat -s WazeTripper:V
 *
 * Precisa ser public (classe e metodos): os hooks ficam em com.waze.navigate, outro pacote, e
 * invoke-static entre pacotes falha em tempo de execucao se o alvo nao for public. Toda falha
 * aqui e' engolida: a instrumentacao nunca pode afetar o Waze.
 *
 * Os callbacks do Waze disparam mesmo quando o valor nao mudou; por isso cada um so' loga na
 * mudanca (sem inundar o log).
 */
public final class NavHooks {

    private NavHooks() {
    }

    private static final String TAG = "WazeTripper";

    private static String lastCurrent;
    private static String lastNext;
    private static int lastExit = Integer.MIN_VALUE;
    private static int lastNextExit = Integer.MIN_VALUE;
    private static int lastDistance = Integer.MIN_VALUE;
    private static int lastEtaDistance = Integer.MIN_VALUE;
    private static int lastEtaSeconds = Integer.MIN_VALUE;
    private static String lastEtaMinutes;
    private static boolean avgCamActive;
    private static int lastZone = Integer.MIN_VALUE;
    private static int lastRadar = Integer.MIN_VALUE;
    private static String lastAlerts;

    /** onCurrentInstructionChanged: codigo cru + nome do Instruction$Type (a manobra da seta grande). */
    public static void onCurrent(int code, String name) {
        try {
            String v = name + " (" + code + ")";
            if (v.equals(lastCurrent)) return;
            lastCurrent = v;
            TripperLog.i(TAG, "NAV manobra atual: " + v);
            TripperNav.onCurrent(name);
        } catch (Throwable ignored) {
        }
    }

    /** onNextInstructionChanged: a proxima manobra (seta pequena, byte [7] do Pod). */
    public static void onNext(int code, String name) {
        try {
            String v = name + " (" + code + ")";
            if (v.equals(lastNext)) return;
            lastNext = v;
            TripperLog.i(TAG, "NAV proxima manobra: " + v);
            TripperNav.onNext(name);
        } catch (Throwable ignored) {
        }
    }

    /** onExitNumberChanged: ordinal da saida (rotatoria) da manobra atual. */
    public static void onExit(int exit) {
        try {
            if (exit == lastExit) return;
            lastExit = exit;
            TripperLog.i(TAG, "NAV saida atual: " + exit);
            TripperNav.onExit(exit);
        } catch (Throwable ignored) {
        }
    }

    /** onNextExitNumberChanged: ordinal da saida da proxima manobra. */
    public static void onNextExit(int exit) {
        try {
            if (exit == lastNextExit) return;
            lastNextExit = exit;
            TripperLog.i(TAG, "NAV proxima saida: " + exit);
            TripperNav.onNextExit(exit);
        } catch (Throwable ignored) {
        }
    }

    /** onCurrentInstructionDistanceChanged: distancia ate a manobra atual (metros + texto do Waze). */
    public static void onDistance(int meters, String text, String unit) {
        try {
            if (meters == lastDistance) return;
            lastDistance = meters;
            TripperLog.i(TAG, "NAV distancia ate a manobra: " + meters + " m (\"" + text + " " + unit + "\")");
            TripperNav.onDistance(meters);
        } catch (Throwable ignored) {
        }
    }

    /** onEtaDistanceChanged: distancia total restante ate o destino (metros). */
    public static void onEtaDistance(int meters) {
        try {
            if (meters == lastEtaDistance) return;
            lastEtaDistance = meters;
            TripperLog.i(TAG, "NAV distancia total: " + meters + " m");
            TripperNav.onEtaDistance(meters);
        } catch (Throwable ignored) {
        }
    }

    /** onEtaMinutesChanged: (texto do ETA, unidade do texto, minutos restantes). */
    public static void onEtaMinutes(String time, String unit, int minutes) {
        try {
            String v = minutes + " min (\"" + time + " " + unit + "\")";
            if (v.equals(lastEtaMinutes)) return;
            lastEtaMinutes = v;
            TripperLog.i(TAG, "NAV tempo restante: " + v);
            TripperNav.onEtaMinutes(minutes);
        } catch (Throwable ignored) {
        }
    }

    /** onCurrentEtaSecondsChanged: tempo restante em segundos (resolucao maior que o de minutos). */
    public static void onEtaSeconds(int seconds) {
        try {
            if (seconds == lastEtaSeconds) return;
            lastEtaSeconds = seconds;
            TripperLog.i(TAG, "NAV tempo restante (s): " + seconds);
        } catch (Throwable ignored) {
        }
    }

    /** onNavigationStateChanged: navegando ou nao (o segundo argumento do Waze e' so' registrado). */
    public static void onNavState(boolean navigating, int arg) {
        try {
            TripperLog.i(TAG, "NAV estado: navegando=" + navigating + " (arg=" + arg + ")");
            TripperNav.onNavState(navigating);
            if (!navigating) {
                // Rota encerrada: zera o cache, pra a proxima rota logar tudo de novo.
                lastCurrent = null;
                lastNext = null;
                lastExit = Integer.MIN_VALUE;
                lastNextExit = Integer.MIN_VALUE;
                lastDistance = Integer.MIN_VALUE;
                lastEtaDistance = Integer.MIN_VALUE;
                lastEtaSeconds = Integer.MIN_VALUE;
                lastEtaMinutes = null;
                lastRadar = Integer.MIN_VALUE;
                lastZone = Integer.MIN_VALUE;
                lastAlerts = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private static java.lang.reflect.Method mPositionList;
    private static java.lang.reflect.Method mLat;
    private static java.lang.reflect.Method mLon;

    /**
     * onRouteGeometryUpdated: os pontos da rota (Position$IntPosition, latitude/longitude em
     * microgrados), registrados no log em blocos ("GEO n texto") pra permitir simular o trajeto
     * inteiro via adb (ver scripts/simulate_route.py). Recebe Object pra nao depender do tipo do
     * Waze em tempo de compilacao; le por reflexao.
     */
    public static void onRouteGeometry(Object geometry) {
        try {
            if (geometry == null) return;
            if (mPositionList == null) {
                mPositionList = geometry.getClass().getMethod("getPositionList");
            }
            java.util.List<?> pts = (java.util.List<?>) mPositionList.invoke(geometry);
            int n = pts.size();
            TripperLog.i(TAG, "GEO rota: " + n + " pontos");
            StringBuilder sb = new StringBuilder();
            int chunk = 0;
            for (int i = 0; i < n; i++) {
                Object p = pts.get(i);
                if (mLat == null) {
                    mLat = p.getClass().getMethod("getLatitude");
                    mLon = p.getClass().getMethod("getLongitude");
                }
                sb.append((Integer) mLat.invoke(p)).append(',').append((Integer) mLon.invoke(p)).append(';');
                if (sb.length() > 3000) {
                    TripperLog.i(TAG, "GEO " + chunk++ + " " + sb);
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) TripperLog.i(TAG, "GEO " + chunk + " " + sb);
        } catch (Throwable t) {
            TripperLog.e(TAG, "onRouteGeometry falhou", t);
        }
    }

    // ---------- radares e alertas (captura: so' log por enquanto) -----------------------------

    /** onEnforcementZoneUpdate: zona de fiscalizacao (radar); o significado do inteiro ainda e' a descobrir no log. */
    public static void onEnforcementZone(int value) {
        try {
            if (value != lastZone) {
                lastZone = value;
                TripperLog.i(TAG, "RADAR zona de fiscalizacao: " + value);
            }
            // Significado do inteiro ainda desconhecido: 0 ou negativo e' tratado como "sem zona".
            TripperNav.onEnforcementZone(value > 0);
        } catch (Throwable ignored) {
        }
    }

    public static void onEnforcementZoneClear() {
        try {
            if (lastZone != Integer.MIN_VALUE) TripperLog.i(TAG, "RADAR fim da zona de fiscalizacao");
            lastZone = Integer.MIN_VALUE;
            TripperNav.onEnforcementZone(false);
        } catch (Throwable ignored) {
        }
    }

    /** onAverageSpeedCamZoneUpdate: radar de velocidade media (percentual do trecho, distancia restante, velocidade recomendada). */
    public static void onAvgSpeedCam(Object update) {
        try {
            Class<?> c = update.getClass();
            int percent = (Integer) c.getMethod("getPercent").invoke(update);
            int left = (Integer) c.getMethod("getLeftDistance").invoke(update);
            int speed = (Integer) c.getMethod("getRecommendedSpeed").invoke(update);
            avgCamActive = true;
            TripperLog.i(TAG, "RADAR velocidade media: " + percent + "% do trecho, faltam " + left + " m, recomendada " + speed);
            TripperNav.onAvgSpeedCam(true, left);
        } catch (Throwable t) {
            TripperLog.i(TAG, "RADAR velocidade media (nao decodificou): " + t);
        }
    }

    public static void onAvgSpeedCamClear() {
        try {
            if (!avgCamActive) return; // o Waze chama isso a cada posicao; so' loga na transicao
            avgCamActive = false;
            TripperLog.i(TAG, "RADAR fim da velocidade media");
            TripperNav.onAvgSpeedCam(false, -1);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Eventos de alerta do Waze (com.waze.alerters). Os bytes sao so' um AlerterId (provedor, id unico,
     * uuid) - o TIPO do alerta vem em onAlertsUpdate(); aqui registramos o id pra correlacionar.
     */
    public static void onAlertStart(byte[] data) {
        logAlertId("inicio", data);
    }

    public static void onAlertTimeout(byte[] data) {
        logAlertId("timeout", data);
    }

    public static void onAlertEnd(byte[] data) {
        logAlertId("fim", data);
    }

    private static void logAlertId(String event, byte[] data) {
        try {
            if (data == null) return;
            Class<?> c = Class.forName("com.waze.jni.protos.alerters.AlerterId");
            Object id = c.getMethod("parseFrom", byte[].class).invoke(null, (Object) data);
            TripperLog.i(TAG, "ALERTA " + event + ": id=" + c.getMethod("getUniqueId").invoke(id)
                    + " provedor=" + c.getMethod("getProviderId").invoke(id));
        } catch (Throwable t) {
            TripperLog.i(TAG, "ALERTA " + event + ": " + (data == null ? 0 : data.length) + " bytes (nao decodificou: " + t + ")");
        }
    }

    /**
     * updateAlertersRepository: a lista completa de alertas do Waze (NativeAlertRepositoryUpdate, ja
     * decodificada), atualizada ~1x/s, com dezenas de itens - a maioria e' cameras conhecidas por
     * perto que ainda nao estao ativas (distancia vazia). Cada NativeAlertDescriptor traz AlerterInfo
     * com tipo (AlerterType: 10 = CAMERA/radar ...), subtipo, distancia ("Em 270 m") e titulo.
     * Ativo = distancia nao vazia. O radar ativo mais proximo (tipo CAMERA) vai pro Pod via TripperNav;
     * so' os ativos entram no log.
     */
    public static void onAlertsUpdate(Object update) {
        try {
            java.util.List<?> list = (java.util.List<?>) update.getClass().getMethod("getDescriptorsList").invoke(update);
            StringBuilder sb = new StringBuilder();
            int radarMeters = -1;
            for (Object d : list) {
                Class<?> c = d.getClass();
                Object info = c.getMethod("getInfo").invoke(d);
                Class<?> ic = info.getClass();
                String dist = (String) ic.getMethod("getDistanceString").invoke(info);
                if (dist == null || dist.isEmpty()) continue; // nao ativo
                int typeValue = (Integer) ic.getMethod("getTypeValue").invoke(info);
                boolean radar = typeValue == 10; // AlerterType.CAMERA
                if (radar) {
                    int m = parseMeters(dist);
                    if (m >= 0 && (radarMeters < 0 || m < radarMeters)) radarMeters = m;
                }
                Object id = c.getMethod("getAlertId").invoke(d);
                sb.append("\n    ").append(ic.getMethod("getType").invoke(info)).append('(').append(typeValue).append(')')
                        .append(radar ? " [RADAR]" : "")
                        .append(" subtipo=").append(ic.getMethod("getSubType").invoke(info))
                        .append(" dist=\"").append(dist).append('"')
                        .append(" titulo=\"").append(ic.getMethod("getTitleWithDistance").invoke(info)).append('"')
                        .append(" id=").append(id.getClass().getMethod("getUniqueId").invoke(id));
            }
            String v = sb.toString();
            if (!v.equals(lastAlerts)) {
                lastAlerts = v;
                TripperLog.i(TAG, "ALERTAS ativos (" + list.size() + " no total):" + (v.isEmpty() ? " nenhum" : v));
            }
            if (radarMeters != lastRadar) {
                lastRadar = radarMeters;
                TripperLog.i(TAG, radarMeters >= 0 ? "RADAR a frente: " + radarMeters + " m" : "RADAR: nenhum a frente");
                TripperNav.onRadar(radarMeters);
            }
        } catch (Throwable t) {
            TripperLog.i(TAG, "ALERTAS ativos (nao decodificou): " + t);
        }
    }

    /** "Em 270 m" / "Em 1,2 km" / "0.5 mi" / "300 ft" -> metros; -1 se nao reconhecer. */
    static int parseMeters(String text) {
        if (text == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+(?:[.,][0-9]+)?)\\s*(km|m|mi|ft)\\b").matcher(text);
        if (!m.find()) return -1;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        switch (m.group(2)) {
            case "km": return (int) Math.round(v * 1000);
            case "m": return (int) Math.round(v);
            case "mi": return (int) Math.round(v * 1609.344);
            case "ft": return (int) Math.round(v * 0.3048);
            default: return -1;
        }
    }
}
