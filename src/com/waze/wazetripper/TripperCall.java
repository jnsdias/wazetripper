package com.waze.wazetripper;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * TripperCall - detecta ligacao (celular tocando ou em chamada) sem pedir nenhuma permissao: o modo de
 * audio do sistema (AudioManager) vira MODE_RINGTONE enquanto toca e MODE_IN_CALL durante a chamada.
 * Ligacoes por app (MODE_IN_COMMUNICATION, ex.: WhatsApp) sao ignoradas de proposito: o Waze usa
 * esse modo com o audio Bluetooth. No Android 12+ usa o listener do sistema; antes, consulta a cada 2 s.
 * O TripperBridge decide o que fazer (icone de ligacao no Pod); o interruptor do painel e' checado la.
 */
final class TripperCall {

    private TripperCall() {
    }

    private static boolean started;

    static void start(Context ctx, TripperBridge bridge) {
        if (started) return;
        started = true;
        try {
            final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.addOnModeChangedListener(ctx.getMainExecutor(), mode -> bridge.setCallActive(isCall(mode)));
            } else {
                final Handler h = new Handler(Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        bridge.setCallActive(isCall(am.getMode()));
                        h.postDelayed(this, 2000);
                    }
                });
            }
            bridge.setCallActive(isCall(am.getMode()));
        } catch (Throwable t) {
            TripperLog.e("WazeTripper", "TripperCall.start() falhou", t);
        }
    }

    private static boolean isCall(int mode) {
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL;
    }
}
