package com.waze.wazetripper;

import android.util.Log;

import java.util.Locale;

/**
 * TripperText - idioma do menu do WazeTripper: segue o idioma escolhido dentro do Waze
 * (Configuracoes > Geral > Idioma), com o idioma do sistema como reserva. Portugues e espanhol tem
 * traducao propria; qualquer outro idioma cai em ingles.
 *
 * O idioma do Waze vem de NativeManager.getInstance().getLocale(), chamado por reflexao porque o
 * pacote e' compilado so' contra o android.jar (as classes do Waze nao existem em tempo de
 * compilacao). As classes do Waze nao sao ofuscadas nesta versao (5.23.0.2).
 *
 * Os textos ficam em codigo, nao em recurso XML, pra nao estender o pipeline de empacotamento
 * (graft.py). Textos com acento usam escapes unicode: o javac do container nao recebe
 * -encoding UTF-8. Os logs de diagnostico (TripperBridge, TripperNav) ficam em portugues.
 */
final class TripperText {

    private static final String TAG = "WazeTripper";

    private static final int PT = 0;
    private static final int EN = 1;
    private static final int ES = 2;

    private static int lang = PT;

    private TripperText() {
    }

    /** Le o idioma do Waze de novo (chamado cada vez que o painel abre, pra refletir uma troca). */
    static void refresh() {
        Locale locale = null;
        String source = "Waze";
        try {
            Class<?> nativeManager = Class.forName("com.waze.NativeManager");
            Object instance = nativeManager.getMethod("getInstance").invoke(null);
            if (instance != null) {
                locale = (Locale) nativeManager.getMethod("getLocale").invoke(instance);
            }
        } catch (Throwable t) {
            Log.e(TAG, "idioma do Waze indisponivel, usando o do sistema", t);
        }
        if (locale == null) {
            locale = Locale.getDefault();
            source = "sistema";
        }
        String code = locale.getLanguage();
        lang = "pt".equals(code) ? PT : "es".equals(code) ? ES : EN;
        Log.e(TAG, "idioma do menu: " + code + " (" + source + ") -> " + (lang == PT ? "pt" : lang == ES ? "es" : "en"));
    }

    /** Devolve o texto no idioma atual: portugues, ingles, espanhol. */
    static String tr(String pt, String en, String es) {
        switch (lang) {
            case PT:
                return pt;
            case ES:
                return es;
            default:
                return en;
        }
    }
}
