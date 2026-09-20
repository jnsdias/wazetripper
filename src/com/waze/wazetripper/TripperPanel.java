package com.waze.wazetripper;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * TripperPanel - painel unico (bottom sheet) do WazeTripper: cabecalho com chip de status, cartao
 * do dispositivo (progresso de pareamento, PIN, conectar/cancelar/desconectar/esquecer), cartao de
 * configuracoes (sempre visivel, mesmo desconectado - as preferencias valem na proxima conexao) e
 * um log recolhido em "Detalhes". Substitui os dois dialogs antigos (status/live) do
 * TripperOverlay. UI 100% programatica: nenhum XML, nenhum recurso novo, so android.* framework.
 *
 * Textos com acento usam escapes unicode no literal porque o javac do container nao recebe
 * -encoding UTF-8; nos comentarios do projeto os acentos sao evitados pelo mesmo motivo.
 */
final class TripperPanel {

    private static final int BG = 0xFF1C2130;
    private static final int CARD = 0xFF283044;
    private static final int DIVIDER = 0xFF343E54;
    private static final int TRACK = 0xFF3A445A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF9AA5B8;
    private static final int ACCENT = 0xFF33CCFF;
    private static final int ON_ACCENT = 0xFF0B1220;
    private static final int OK = 0xFF3DDC84;
    private static final int WARN = 0xFFFFB020;
    private static final int DANGER = 0xFFFF6B6B;

    private static final int MAX_LOG_LINES = 8;
    private static final int PAIRING_STEPS = 3;
    private static final String COMPASS_HINT = "Seta de rumo (GPS) quando não há rota";

    static void show(Activity activity, TripperBridge bridge) {
        new TripperPanel(activity, bridge).open();
    }

    private final Activity activity;
    private final TripperBridge bridge;
    private final float density;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private Dialog dialog;

    // cabecalho / cartao do dispositivo
    private ImageView headerIcon;
    private TextView chip;
    private TextView deviceName;
    private TextView deviceSub;
    private LinearLayout stepsRow;
    private final View[] stepBars = new View[PAIRING_STEPS];
    private TextView stepLabel;
    private LinearLayout pinRow;
    private EditText pinField;
    private Button primary;
    private TextView forgetLink;

    // configuracoes
    private TextView seg24;
    private TextView seg12;
    private TextView syncSub;
    private Button syncButton;
    private TextView compassSub;

    // log de trajetos (gravacao em arquivo, ver TripperLog)
    private TextView tripSub;
    private Button tripExport;
    private TextView tripClear;
    private boolean clearArmed;

    // log
    private TextView logToggle;
    private TextView logView;

    private TripperPanel(Activity activity, TripperBridge bridge) {
        this.activity = activity;
        this.bridge = bridge;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    // ---------- montagem ---------------------------------------------------------------------

    private void open() {
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        float r = dp(24);
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(BG);
        sheet.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(sheet);

        root.addView(buildHeader());
        root.addView(buildDeviceCard(), topMargin(16));
        root.addView(sectionTitle("CONFIGURAÇÕES"), topMargin(20));
        root.addView(buildSettingsCard(), topMargin(8));
        root.addView(sectionTitle("LOG DE TRAJETOS"), topMargin(20));
        root.addView(buildTripLogCard(), topMargin(8));
        root.addView(buildLogSection(), topMargin(12));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        dialog.setContentView(scroll);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setCanceledOnTouchOutside(true);

        TripperBridge.Listener listener = new TripperBridge.Listener() {
            @Override
            public void onLog(String line) {
                logLines.addLast(line);
                while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
                logView.setText(String.join("\n", logLines));
            }

            @Override
            public void onStateChanged(TripperBridge.State state) {
                render(state);
            }
        };
        bridge.setListener(listener);
        dialog.setOnDismissListener(d -> {
            bridge.setListener(null);
        });

        renderSegments();
        render(bridge.getState());
        dialog.show();
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int iconBox = dp(40);
        headerIcon = new ImageView(activity);
        headerIcon.setImageDrawable(TripperOverlay.linkIcon((int) (iconBox * 0.74f)));
        headerIcon.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(headerIcon, new LinearLayout.LayoutParams(iconBox, iconBox));

        TextView title = new TextView(activity);
        title.setText("WazeTripper");
        title.setTextColor(TEXT);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView version = new TextView(activity);
        version.setText("v" + Version.NAME);
        version.setTextColor(MUTED);
        version.setTextSize(11f);
        LinearLayout titleBox = new LinearLayout(activity);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(title);
        titleBox.addView(version);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        row.addView(titleBox, tp);

        chip = new TextView(activity);
        chip.setTextSize(12f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        row.addView(chip);

        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(MUTED);
        close.setTextSize(18f);
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> dialog.dismiss());
        row.addView(close);
        return row;
    }

    private View buildDeviceCard() {
        LinearLayout card = card();

        deviceName = new TextView(activity);
        deviceName.setTextColor(TEXT);
        deviceName.setTextSize(17f);
        deviceName.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(deviceName);

        deviceSub = new TextView(activity);
        deviceSub.setTextColor(MUTED);
        deviceSub.setTextSize(13f);
        card.addView(deviceSub);

        stepsRow = new LinearLayout(activity);
        stepsRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bars = new LinearLayout(activity);
        bars.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PAIRING_STEPS; i++) {
            View bar = new View(activity);
            stepBars[i] = bar;
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(4), 1f);
            if (i < PAIRING_STEPS - 1) bp.rightMargin = dp(4);
            bars.addView(bar, bp);
        }
        stepsRow.addView(bars);
        stepLabel = new TextView(activity);
        stepLabel.setTextColor(MUTED);
        stepLabel.setTextSize(12f);
        stepsRow.addView(stepLabel, topMargin(6));
        stepsRow.setVisibility(View.GONE);
        card.addView(stepsRow, topMargin(12));

        pinRow = new LinearLayout(activity);
        pinRow.setOrientation(LinearLayout.HORIZONTAL);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        pinField = new EditText(activity);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinField.setHint("PIN mostrado no Tripper");
        pinField.setHintTextColor(MUTED);
        pinField.setTextColor(TEXT);
        pinField.setTextSize(16f);
        pinField.setSingleLine(true);
        pinField.setPadding(dp(14), dp(10), dp(14), dp(10));
        pinField.setBackground(rounded(BG, 12));
        pinRow.addView(pinField, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button confirm = new Button(activity);
        confirm.setText("Confirmar");
        styleButton(confirm, true);
        confirm.setOnClickListener(v -> bridge.confirmPin(pinField.getText().toString().trim()));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        cp.leftMargin = dp(8);
        pinRow.addView(confirm, cp);
        pinRow.setVisibility(View.GONE);
        card.addView(pinRow, topMargin(12));

        primary = new Button(activity);
        card.addView(primary, buttonParams(14));

        forgetLink = new TextView(activity);
        forgetLink.setText("Esquecer / trocar Tripper");
        forgetLink.setTextColor(DANGER);
        forgetLink.setTextSize(13f);
        forgetLink.setGravity(Gravity.CENTER);
        forgetLink.setPadding(dp(8), dp(12), dp(8), dp(4));
        forgetLink.setOnClickListener(v -> {
            bridge.forget();
            bridge.userConnect();
        });
        card.addView(forgetLink);
        return card;
    }

    private View buildSettingsCard() {
        LinearLayout card = card();

        // Formato de hora: seletor segmentado 24h | 12h (preferencia, vale offline).
        LinearLayout clockRow = new LinearLayout(activity);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        clockRow.addView(labelColumn("Formato de hora", "Relógio do Tripper"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout segmented = new LinearLayout(activity);
        segmented.setOrientation(LinearLayout.HORIZONTAL);
        segmented.setPadding(dp(3), dp(3), dp(3), dp(3));
        segmented.setBackground(rounded(TRACK, 10));
        seg24 = segment("24h", false);
        seg12 = segment("12h", true);
        segmented.addView(seg24);
        segmented.addView(seg12);
        clockRow.addView(segmented);
        card.addView(clockRow);

        addDivider(card);

        // Sincronizar relogio: precisa do link, entao so' habilita conectado.
        LinearLayout syncRow = new LinearLayout(activity);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout syncLabel = labelColumn("Sincronizar relógio", "");
        syncSub = (TextView) syncLabel.getChildAt(1);
        syncRow.addView(syncLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        syncButton = new Button(activity);
        syncButton.setText("Sincronizar");
        styleButton(syncButton, true);
        syncButton.setOnClickListener(v -> {
            boolean sent = bridge.syncClockNow();
            syncSub.setText(sent ? "Hora enviada ao Tripper." : "Sem conexão com o Tripper.");
            syncSub.postDelayed(this::renderSyncHint, 2500);
        });
        syncRow.addView(syncButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        card.addView(syncRow, topMargin(12));

        addDivider(card);

        // Bussola sem rota: preferencia (vale offline); liga sozinha ao conectar (ver TripperBridge).
        LinearLayout compassRow = new LinearLayout(activity);
        compassRow.setOrientation(LinearLayout.HORIZONTAL);
        compassRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout compassLabel = labelColumn("Bússola sem rota", COMPASS_HINT);
        compassSub = (TextView) compassLabel.getChildAt(1);
        compassRow.addView(compassLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch compassSwitch = new Switch(activity);
        compassSwitch.setChecked(TripperPrefs.isCompassEnabled(activity));
        int[][] states = {{android.R.attr.state_checked}, {}};
        compassSwitch.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, MUTED}));
        compassSwitch.setTrackTintList(new ColorStateList(states, new int[]{0x6633CCFF, TRACK}));
        compassSwitch.setOnCheckedChangeListener((b, checked) -> {
            TripperPrefs.setCompassEnabled(activity, checked);
            bridge.refreshMode(); // ligar/desligar a bussola e' decidido (e o reconnect, se preciso) no bridge
            boolean noPermission = checked && activity.checkSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
            compassSub.setText(noPermission ? "Sem permissão de localização no Waze" : COMPASS_HINT);
            compassSub.setTextColor(noPermission ? WARN : MUTED);
        });
        compassRow.addView(compassSwitch);
        card.addView(compassRow, topMargin(12));

        addDivider(card);

        // Reconexao automatica: procura o Pod conhecido ao abrir o Waze e apos quedas, ate 30 min.
        LinearLayout autoRow = new LinearLayout(activity);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.addView(labelColumn("Reconex\u00e3o autom\u00e1tica",
                        "Procura o Tripper ao abrir o Waze e ap\u00f3s quedas (at\u00e9 30 min)"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch autoSwitch = new Switch(activity);
        autoSwitch.setChecked(TripperPrefs.isAutoReconnect(activity));
        autoSwitch.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, MUTED}));
        autoSwitch.setTrackTintList(new ColorStateList(states, new int[]{0x6633CCFF, TRACK}));
        autoSwitch.setOnCheckedChangeListener((b, checked) -> {
            TripperPrefs.setAutoReconnect(activity, checked);
            bridge.onAutoReconnectPrefChanged();
        });
        autoRow.addView(autoSwitch);
        card.addView(autoRow, topMargin(12));

        addDivider(card);

        // O que aparece na parte de baixo da tela de navegacao (Fase 2c).
        card.addView(labelColumn("Informa\u00e7\u00e3o inferior", "Parte de baixo da tela de navega\u00e7\u00e3o"), topMargin(12));
        card.addView(choiceRow(new String[]{"Dist\u00e2ncia", "Tempo", "Chegada"},
                TripperPrefs.bottomInfoMode(activity),
                mode -> TripperPrefs.setBottomInfoMode(activity, mode)), topMargin(8));

        addDivider(card);

        // Radar no Pod: so' aparece a ate 300 m. Compativel = 0x3C + distancia na linha de baixo (como o
        // outro app do Tripper); Experimental = 0x3C + distancia no campo menor, sem tocar na linha de baixo.
        card.addView(labelColumn("Radar no Tripper", "S\u00f3 a at\u00e9 300 m \u00b7 Experimental mant\u00e9m o total na linha de baixo"), topMargin(12));
        card.addView(choiceRow(new String[]{"Desligado", "Compat\u00edvel", "Experimental"},
                TripperPrefs.radarMode(activity),
                mode -> TripperPrefs.setRadarMode(activity, mode)), topMargin(8));

        addDivider(card);

        card.addView(switchRow("\u00cdcone de liga\u00e7\u00e3o", "Mostra no Tripper quando o celular toca ou est\u00e1 em chamada",
                TripperPrefs.isCallIcon(activity), (b, checked) -> {
                    TripperPrefs.setCallIcon(activity, checked);
                    bridge.onCallPrefChanged();
                }), topMargin(12));

        addDivider(card);

        card.addView(switchRow("Intensidade por dist\u00e2ncia", "Destaca o \u00edcone conforme a manobra se aproxima",
                TripperPrefs.isDistanceIntensity(activity),
                (b, checked) -> TripperPrefs.setDistanceIntensity(activity, checked)), topMargin(12));

        addDivider(card);

        // Modo noturno: segue o tema do Waze (so' informativo).
        LinearLayout nightRow = new LinearLayout(activity);
        nightRow.setOrientation(LinearLayout.HORIZONTAL);
        nightRow.setGravity(Gravity.CENTER_VERTICAL);
        nightRow.addView(labelColumn("Modo noturno", "Segue o tema do Waze (bússola e navegação)"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean night = TripperNight.isNight();
        TextView nightState = new TextView(activity);
        nightState.setText(night ? "Noite" : "Dia");
        nightState.setTextColor(night ? ACCENT : WARN);
        nightState.setTextSize(14f);
        nightState.setTypeface(Typeface.DEFAULT_BOLD);
        nightRow.addView(nightState);
        card.addView(nightRow, topMargin(12));

        return card;
    }

    // ---------- log de trajetos ---------------------------------------------------------------

    private View buildTripLogCard() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout label = labelColumn("Log de trajetos", "");
        tripSub = (TextView) label.getChildAt(1);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tripExport = new Button(activity);
        tripExport.setText("Exportar");
        styleButton(tripExport, true);
        tripExport.setOnClickListener(v -> exportTripLog());
        row.addView(tripExport, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        card.addView(row);

        tripClear = new TextView(activity);
        tripClear.setText("Limpar registros");
        tripClear.setTextColor(DANGER);
        tripClear.setTextSize(13f);
        tripClear.setGravity(Gravity.CENTER);
        tripClear.setPadding(dp(8), dp(12), dp(8), dp(4));
        tripClear.setOnClickListener(v -> {
            if (!clearArmed) { // apagar e' definitivo: pede um segundo toque
                clearArmed = true;
                tripClear.setText("Toque de novo para apagar");
                tripClear.postDelayed(() -> {
                    clearArmed = false;
                    tripClear.setText("Limpar registros");
                }, 3000);
                return;
            }
            clearArmed = false;
            TripperLog.clear();
            tripClear.setText("Limpar registros");
            renderTripLog();
        });
        card.addView(tripClear);
        return card;
    }

    private void renderTripLog() {
        if (tripSub == null) return;
        long[] st = TripperLog.stats();
        boolean rec = TripperLog.isRecording();
        if (rec) {
            tripSub.setText("Gravando o trajeto agora");
        } else if (st[0] == 0) {
            tripSub.setText("Grava sozinho durante a navega\u00e7\u00e3o");
        } else {
            tripSub.setText(st[0] + (st[0] == 1 ? " trajeto" : " trajetos") + " \u00b7 " + formatSize(st[1]));
        }
        tripSub.setTextColor(rec ? OK : MUTED);
        tripExport.setEnabled(st[0] > 0);
        tripExport.setAlpha(st[0] > 0 ? 1f : 0.4f);
        tripClear.setVisibility(st[0] > 0 ? View.VISIBLE : View.GONE);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return ((bytes + 512) / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Salva todos os trajetos em Downloads/WazeTripper (MediaStore, sem permissao) e abre o compartilhamento. */
    private void exportTripLog() {
        tripExport.setEnabled(false);
        tripSub.setText("Exportando\u2026");
        final String name = "wazetripper-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        new Thread(() -> {
            Uri saved = null;
            String error = null;
            try {
                saved = saveToDownloads(name);
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
                TripperLog.e("WazeTripper", "exportar log falhou", t);
            }
            final Uri uri = saved;
            final String err = error;
            activity.runOnUiThread(() -> {
                renderTripLog();
                if (uri != null) shareTripLog(uri, name);
                else Toast.makeText(activity, "Falha ao exportar: " + err, Toast.LENGTH_LONG).show();
            });
        }, "wazetripper-export").start();
    }

    private Uri saveToDownloads(String name) throws IOException {
        ContentResolver cr = activity.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WazeTripper");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("o Android recusou criar o arquivo em Downloads");
        try {
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("nao consegui abrir o arquivo em Downloads");
                TripperLog.exportTo(out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri;
        } catch (IOException | RuntimeException e) {
            try {
                cr.delete(uri, null, null); // nao deixa um arquivo pela metade em Downloads
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }

    private void shareTripLog(Uri uri, String name) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Log do WazeTripper");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri(name, uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Toast.makeText(activity, "Salvo em Downloads/WazeTripper", Toast.LENGTH_LONG).show();
        activity.startActivity(Intent.createChooser(send, "Enviar log"));
    }

    /** Linha com titulo, texto de apoio e um interruptor. */
    private LinearLayout switchRow(String title, String sub, boolean checked,
                                   android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labelColumn(title, sub), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(activity);
        sw.setChecked(checked);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{ACCENT, MUTED}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{0x6633CCFF, TRACK}));
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    /** Seletor segmentado de largura total: opcoes iguais, a escolhida em destaque; onSelect recebe o indice. */
    private LinearLayout choiceRow(String[] options, int selected, java.util.function.IntConsumer onSelect) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(3), dp(3), dp(3), dp(3));
        box.setBackground(rounded(TRACK, 10));
        final TextView[] views = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView t = new TextView(activity);
            t.setText(options[i]);
            t.setTextSize(13f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(8), dp(8), dp(8));
            views[i] = t;
            paintSegment(t, i == selected);
            t.setOnClickListener(v -> {
                for (int k = 0; k < views.length; k++) paintSegment(views[k], k == index);
                onSelect.accept(index);
            });
            box.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return box;
    }

    private View buildLogSection() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        logToggle = new TextView(activity);
        logToggle.setText("▸ Detalhes");
        logToggle.setTextColor(MUTED);
        logToggle.setTextSize(13f);
        logToggle.setPadding(dp(4), dp(8), dp(4), dp(8));
        box.addView(logToggle);

        logView = new TextView(activity);
        logView.setTextColor(MUTED);
        logView.setTextSize(11f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMaxLines(MAX_LOG_LINES);
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView.setBackground(rounded(CARD, 12));
        logView.setVisibility(View.GONE);
        box.addView(logView);

        logToggle.setOnClickListener(v -> {
            boolean open = logView.getVisibility() != View.VISIBLE;
            logView.setVisibility(open ? View.VISIBLE : View.GONE);
            logToggle.setText(open ? "▾ Detalhes" : "▸ Detalhes");
        });
        return box;
    }

    // ---------- estado -----------------------------------------------------------------------

    private void render(TripperBridge.State state) {
        boolean saved = bridge.hasSavedDevice();
        String name = bridge.savedDeviceName();
        deviceName.setText(saved && name != null ? name : "Tripper");

        int chipColor;
        String chipText;
        String sub;
        int step = 0;
        switch (state) {
            case SCANNING:
                chipColor = WARN;
                chipText = "Procurando";
                sub = "Procurando o Tripper (RE_DISP)...";
                step = 1;
                break;
            case CONNECTING:
                chipColor = WARN;
                chipText = "Conectando";
                sub = "Conectando ao Tripper...";
                step = 2;
                break;
            case WAITING_PIN:
                chipColor = WARN;
                chipText = "Aguardando PIN";
                sub = "Digite o PIN mostrado no Tripper.";
                step = 3;
                break;
            case CONNECTED:
                chipColor = OK;
                chipText = "Conectado";
                sub = "Pareamento salvo.";
                break;
            case DISCONNECTED:
            default:
                chipColor = DANGER;
                chipText = "Desconectado";
                sub = saved ? "Pareado. Toque em Reconectar." : "Nenhum Tripper pareado.";
                break;
        }

        headerIcon.setBackground(TripperOverlay.circleBackground(TripperOverlay.stateColor(state)));
        chip.setText("● " + chipText);
        chip.setTextColor(chipColor);
        chip.setBackground(rounded((chipColor & 0x00FFFFFF) | 0x33000000, 100));
        deviceSub.setText(sub);

        stepsRow.setVisibility(step > 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < PAIRING_STEPS; i++) {
            stepBars[i].setBackground(rounded(i < step ? ACCENT : TRACK, 2));
        }
        stepLabel.setText(step > 0 ? "Passo " + step + " de " + PAIRING_STEPS + " · " + chipText : "");
        pinRow.setVisibility(state == TripperBridge.State.WAITING_PIN ? View.VISIBLE : View.GONE);

        switch (state) {
            case DISCONNECTED:
                primary.setText(saved ? "Reconectar" : "Conectar");
                styleButton(primary, true);
                primary.setOnClickListener(v -> bridge.userConnect());
                break;
            case CONNECTED:
                primary.setText("Desconectar");
                styleButton(primary, false);
                primary.setOnClickListener(v -> bridge.userDisconnect());
                break;
            default:
                primary.setText("Cancelar");
                styleButton(primary, false);
                primary.setOnClickListener(v -> bridge.userDisconnect());
                break;
        }

        forgetLink.setVisibility(saved ? View.VISIBLE : View.GONE);
        renderSyncHint();
        renderTripLog();
    }

    private void renderSyncHint() {
        boolean connected = bridge.getState() == TripperBridge.State.CONNECTED;
        syncSub.setText(connected ? "Envia a hora do celular ao Tripper" : "Disponível quando conectado");
        syncButton.setEnabled(connected);
        syncButton.setAlpha(connected ? 1f : 0.4f);
    }

    private void renderSegments() {
        boolean is12h = TripperPrefs.is12h(activity);
        paintSegment(seg24, !is12h);
        paintSegment(seg12, is12h);
    }

    private void paintSegment(TextView seg, boolean selected) {
        seg.setBackground(selected ? rounded(ACCENT, 8) : null);
        seg.setTextColor(selected ? ON_ACCENT : MUTED);
    }

    private TextView segment(String label, boolean is12h) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextSize(13f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        t.setOnClickListener(v -> {
            if (TripperPrefs.is12h(activity) == is12h) return;
            TripperPrefs.set12h(activity, is12h);
            renderSegments();
            // Conectado: reflete no Tripper na hora.
            if (bridge.getState() == TripperBridge.State.CONNECTED) bridge.syncClockNow();
        });
        return t;
    }

    // ---------- helpers de layout ------------------------------------------------------------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(rounded(CARD, 16));
        return c;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), 0, 0, 0);
        return t;
    }

    /** Coluna [titulo, subtitulo]; o subtitulo e' o filho 1 (o sync atualiza esse texto). */
    private LinearLayout labelColumn(String title, String sub) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(15f);
        col.addView(t);
        TextView s = new TextView(activity);
        s.setText(sub);
        s.setTextColor(MUTED);
        s.setTextSize(12f);
        col.addView(s);
        return col;
    }

    private void addDivider(LinearLayout parent) {
        View v = new View(activity);
        v.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        parent.addView(v, lp);
    }

    private void styleButton(Button b, boolean filled) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (filled) {
            bg.setColor(ACCENT);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), TRACK);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        b.setTextColor(filled ? ON_ACCENT : TEXT);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(16), 0, dp(16), 0);
    }

    private LinearLayout.LayoutParams buttonParams(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams topMargin(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private Drawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return (int) (value * density);
    }
}
