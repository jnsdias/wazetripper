package com.waze.wazetripper;


/**
 * TripperNight - o modo noturno do Pod segue o tema do proprio Waze (dia/noite do mapa, seja
 * automatico ou manual). Le por reflexao com.waze.nightmode.u.f(), um metodo estatico que devolve
 * true quando o tema e' DIA (deduzido de quem o usa: a integracao com o Google Assistant manda
 * NIGHT_MODE_STATUS_DAY quando f() e' true). Os nomes sao ofuscados e valem pro Waze 5.23.0.2 -
 * na mesma situacao dos hooks smali; se a leitura falhar, assume dia e registra o erro uma vez.
 *
 * O byte [6] dos pacotes de bussola/navegacao e' lido a cada envio (a cada 2s), entao o Pod
 * acompanha o tema do Waze sem precisar de observador.
 */
final class TripperNight {

    private TripperNight() {
    }

    private static final String TAG = "WazeTripper";

    private static java.lang.reflect.Method isDay;
    private static boolean failed;
    private static Boolean last;
    /** true se o tema do Waze e' noite agora. */
    static boolean isNight() {
        try {
            if (failed) return false;
            if (isDay == null) {
                isDay = Class.forName("com.waze.nightmode.u").getMethod("f");
            }
            boolean night = !((Boolean) isDay.invoke(null));
            if (last == null || last != night) {
                last = night;
                TripperLog.i(TAG, "TEMA do Waze: " + (night ? "noite" : "dia"));
            }
            return night;
        } catch (Throwable t) {
            failed = true;
            TripperLog.e(TAG, "TripperNight: leitura do tema do Waze falhou; assumindo dia", t);
            return false;
        }
    }
}
