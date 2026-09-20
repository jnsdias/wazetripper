package com.waze.wazetripper;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TripperLog - log do WazeTripper: escreve no logcat (como o android.util.Log) e, durante a
 * navegacao, grava tudo num arquivo por trajeto (trip-AAAAMMDD-HHMMSS.log em filesDir) pra ser
 * exportado e conferido depois: icones sem traducao, manobras erradas, radares, quedas de link.
 *
 * O trajeto comeca e termina com a navegacao do Waze (TripperNav.onNavState). Fora de trajeto as
 * linhas so' vao pro logcat e pra uma memoria das ultimas RING_LINES, despejada no inicio do
 * arquivo (os primeiros callbacks da rota costumam chegar antes do estado "navegando").
 *
 * Linhas "GEO ..." (geometria da rota, com latitude/longitude) nunca vao pro arquivo: so' servem
 * ao simulate_route.py via logcat, e o arquivo exportado costuma ser enviado a terceiros.
 *
 * Todo o estado mutavel vive numa unica thread ("wazetripper-log"): quem chama nunca bloqueia, e
 * um erro de disco so' perde a linha - nunca derruba o Waze. Cada linha e' descarregada (flush) na
 * hora, entao um Waze morto no meio da rota nao perde nada.
 */
final class TripperLog {

    private TripperLog() {
    }

    private static final String TAG = "WazeTripper";
    private static final String DIR_NAME = "wazetripper-logs";
    private static final String PREFIX = "trip-";
    private static final String SUFFIX = ".log";
    private static final int RING_LINES = 200;
    private static final int MAX_TRIPS = 30;
    private static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wazetripper-log");
        t.setDaemon(true);
        return t;
    });

    private static volatile File dir;
    private static volatile String appVersion = "?";
    private static volatile boolean recording;
    private static volatile File activeFile;

    // So' a thread "io" mexe nestes:
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static Writer writer;
    private static long activeBytes;
    private static long tripStartMs;
    private static boolean capped;

    // ---------- API de log (mesma forma do android.util.Log) ----------------------------------

    static void i(String tag, String msg) {
        Log.i(tag, msg);
        record('I', msg);
    }

    static void w(String tag, String msg) {
        Log.w(tag, msg);
        record('W', msg);
    }

    static void e(String tag, String msg) {
        Log.e(tag, msg);
        record('E', msg);
    }

    static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        record('E', msg + "\n" + Log.getStackTraceString(t));
    }

    // ---------- ciclo de vida -----------------------------------------------------------------

    /** Define a pasta dos arquivos (idempotente). Chamado quando o bridge e' criado, no startup do Waze. */
    static void init(Context ctx) {
        if (dir != null) return;
        try {
            File d = new File(ctx.getFilesDir(), DIR_NAME);
            d.mkdirs();
            dir = d;
            try {
                appVersion = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            io.execute(TripperLog::prune);
        } catch (Throwable t) {
            Log.e(TAG, "TripperLog.init() falhou", t);
        }
    }

    /** Comeca a gravar um trajeto (no-op se ja esta gravando). header = linhas de contexto ("\n"). */
    static void startTrip(String header) {
        final long now = System.currentTimeMillis();
        try {
            io.execute(() -> openTrip(now, header));
        } catch (Throwable ignored) {
        }
    }

    /** Encerra o trajeto em gravacao (no-op se nao ha). */
    static void endTrip() {
        final long now = System.currentTimeMillis();
        try {
            io.execute(() -> closeTrip(now));
        } catch (Throwable ignored) {
        }
    }

    static boolean isRecording() {
        return recording;
    }

    // ---------- consulta / exportacao / limpeza (chamadas pelo painel) ------------------------

    /** {quantidade de trajetos gravados, bytes no total}. */
    static long[] stats() {
        File[] files = tripFiles();
        long bytes = 0;
        for (File f : files) bytes += f.length();
        return new long[]{files.length, bytes};
    }

    /**
     * Escreve todos os trajetos (do mais antigo ao mais novo) em out, com um cabecalho do
     * aparelho/versao. Nao fecha out. Bloqueia: chamar fora da main thread. Devolve quantos trajetos.
     */
    static int exportTo(OutputStream out) throws IOException {
        File[] files = tripFiles();
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write("WazeTripper - log exportado em " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n");
        w.write(deviceLine() + "\n");
        w.write("Trajetos: " + files.length + "\n");
        char[] buf = new char[8192];
        for (File f : files) {
            w.write("\n########## " + f.getName() + " ##########\n");
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                int n;
                while ((n = r.read(buf)) > 0) w.write(buf, 0, n);
            }
        }
        w.flush();
        return files.length;
    }

    /** Apaga todos os trajetos gravados, menos o que esta em gravacao agora. */
    static void clear() {
        File active = activeFile;
        for (File f : tripFiles()) {
            if (!f.equals(active)) f.delete();
        }
    }

    // ---------- internos (thread "io", exceto onde dito) --------------------------------------

    private static void record(char level, String msg) {
        final long now = System.currentTimeMillis();
        try {
            io.execute(() -> write(now, level, msg));
        } catch (Throwable ignored) {
        }
    }

    private static void write(long ts, char level, String msg) {
        try {
            if (msg == null || msg.startsWith("GEO")) return;
            String line = clock.format(new Date(ts)) + " " + level + " " + msg;
            if (writer == null) {
                ring.addLast(line);
                while (ring.size() > RING_LINES) ring.removeFirst();
                return;
            }
            writeLine(line);
        } catch (Throwable ignored) {
        }
    }

    private static void writeLine(String line) throws IOException {
        if (capped) return;
        String out = line + "\n";
        activeBytes += out.getBytes(StandardCharsets.UTF_8).length;
        if (activeBytes > MAX_FILE_BYTES) {
            capped = true;
            writer.write("[limite de 2 MB por trajeto atingido: linhas seguintes descartadas]\n");
            writer.flush();
            return;
        }
        writer.write(out);
        writer.flush();
    }

    private static void openTrip(long now, String header) {
        try {
            if (writer != null) return; // ja gravando
            File d = dir;
            if (d == null) return;
            d.mkdirs();
            File f = new File(d, PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now)) + SUFFIX);
            writer = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
            activeFile = f;
            activeBytes = 0;
            capped = false;
            tripStartMs = now;
            recording = true;
            writeLine("=== WazeTripper: trajeto iniciado em " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now)) + " ===");
            writeLine(deviceLine());
            if (header != null) {
                for (String h : header.split("\n")) writeLine(h);
            }
            writeLine("--- ultimas linhas antes do inicio ---");
            for (String l : ring) writeLine(l);
            ring.clear();
            writeLine("--- trajeto ---");
        } catch (Throwable t) {
            Log.e(TAG, "TripperLog: nao consegui abrir o arquivo do trajeto", t);
            closeWriter();
            recording = false;
            activeFile = null;
        }
    }

    private static void closeTrip(long now) {
        try {
            if (writer == null) return;
            long secs = Math.max(0, (now - tripStartMs) / 1000);
            writer.write("=== trajeto encerrado em " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now))
                    + " (duracao " + secs / 60 + " min " + secs % 60 + " s) ===\n");
            writer.flush();
        } catch (Throwable ignored) {
        } finally {
            closeWriter();
            recording = false;
            activeFile = null;
            prune();
        }
    }

    private static void closeWriter() {
        try {
            if (writer != null) writer.close();
        } catch (Throwable ignored) {
        }
        writer = null;
    }

    /** Apaga os trajetos mais antigos alem de MAX_TRIPS / MAX_TOTAL_BYTES (nunca o que esta gravando). */
    private static void prune() {
        try {
            File[] files = tripFiles();
            long total = 0;
            for (File f : files) total += f.length();
            File active = activeFile;
            int count = files.length;
            for (File f : files) { // do mais antigo pro mais novo
                if (count <= MAX_TRIPS && total <= MAX_TOTAL_BYTES) break;
                if (f.equals(active)) continue;
                total -= f.length();
                count--;
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Arquivos de trajeto, do mais antigo pro mais novo (o nome carrega a data). Qualquer thread. */
    private static File[] tripFiles() {
        File d = dir;
        File[] files = d == null ? null : d.listFiles((dd, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    private static String deviceLine() {
        return "App: WazeTripper v" + Version.NAME + " sobre Waze " + appVersion + " | Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ") | " + Build.MANUFACTURER + " " + Build.MODEL;
    }
}
