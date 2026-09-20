package com.waze.wazetripper;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

/**
 * TripperBridge — ponte BLE
 * process-wide dentro do Waze patchado, adaptada de um cliente BLE anterior do
 * autor (validado em hardware real) e seguindo o mesmo desenho de singleton sem Service do
 * ClusterBridge do wazeology (nenhum Foreground Service - o link vive enquanto o processo do
 * Waze viver; ver o DEVELOPMENT.md do wazeology, secao 5).
 *
 * Diferenca chave em relacao ao ClusterBridge/BleClient do wazeology: a Kawasaki e' so GATT
 * server (o link e' client-only). O Tripper Pod e' bidirecional: o celular conecta como
 * client no servico do Pod E hospeda seu proprio BluetoothGattServer local, pelo qual o Pod
 * escreve respostas de volta - tudo pela MESMA conexao BLE ja estabelecida (nao precisa de
 * advertising nem de permissao nova; ver docs/PROTOCOL.md).
 *
 * A navegacao real chega pelo TripperNav (hooks em NavHooks); esta classe cuida so' do link BLE.
 */
final class TripperBridge {

    private static final UUID SERVICE_UUID = UUID.fromString("01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0");
    private static final UUID CHAR_UUID = UUID.fromString("01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String DEVICE_NAME = "RE_DISP";
    private static final long SCAN_TIMEOUT_MS = 15000L;

    enum State {DISCONNECTED, SCANNING, CONNECTING, WAITING_PIN, CONNECTED}

    /** Callbacks pro TripperOverlay (janela/dialog). Sempre entregues na main thread. */
    interface Listener {
        void onLog(String line);

        void onStateChanged(State state);
    }

    /** Observador permanente de estado (o botao flutuante); alem do Listener do painel, que e' temporario. */
    interface StateObserver {
        void onStateChanged(State state);
    }

    private static volatile TripperBridge INSTANCE;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothManager bluetoothManager;

    private volatile Listener listener;
    private volatile StateObserver stateObserver;
    private volatile State state = State.DISCONNECTED;

    private BluetoothGattServer gattServer;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

    private boolean scanning;
    private boolean autoScan;               // o scan em andamento e' o continuo da reconexao automatica
    private Runnable scanTimeout;

    // Reconexao automatica (ver armAuto/autoAttempt)
    private static final long AUTO_INTERVAL_MS = 30_000L;
    private static final long AUTO_WINDOW_MS = 30 * 60_000L;
    private static final long AUTO_AFTER_DROP_MS = 5_000L;
    private static final long AUTO_AFTER_TRIPPER_DROP_MS = 1_000L; // queda iniciada pelo Tripper: ele anuncia de novo na hora
    private final Runnable autoTick = this::autoAttempt;
    private volatile boolean userStopped;   // o usuario tocou em Desconectar/Cancelar: nao procura mais sozinho
    private boolean autoStartedOnce;
    private long autoDeadline;              // elapsedRealtime em que a janela de 30 min acaba
    private boolean autoLogged;             // 1 linha de log por janela, nao uma a cada 30 s
    private boolean autoGaveUp;
    private boolean autoBtOffLogged;
    private CompassManager compass; // so' tocado na main thread (ver applyMode)
    private String currentDeviceMac;
    private String currentDeviceName;

    private final ArrayDeque<byte[]> sendQueue = new ArrayDeque<>();
    private boolean writePending;
    private long lastSendMillis;
    private Runnable keepAliveRunnable;

    private TripperBridge(Context appCtx) {
        this.appContext = appCtx;
        TripperLog.init(appCtx);
        this.bluetoothManager = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    // ---------- singleton (mesmo padrao do ClusterBridge - reflection via ActivityThread) ----

    /** Contexto da aplicacao (pra ler preferencias fora do bridge). */
    Context context() {
        return appContext;
    }

    /** A instancia se ja foi criada (o overlay a cria no startup do Waze), senao null. */
    static TripperBridge peek() {
        return INSTANCE;
    }

    static TripperBridge get(Context ctx) {
        TripperBridge local = INSTANCE;
        if (local == null) {
            synchronized (TripperBridge.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new TripperBridge(ctx.getApplicationContext());
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    // ---------- API pro TripperOverlay -------------------------------------------------------

    void setListener(Listener l) {
        this.listener = l;
        if (l != null) {
            final Listener ll = l;
            final State s = state;
            main.post(() -> ll.onStateChanged(s));
        }
    }

    /** Substitui o observador de estado (o mais recente vence, ex.: Activity recriada). */
    void setStateObserver(StateObserver o) {
        this.stateObserver = o;
    }

    State getState() {
        return state;
    }

    boolean hasSavedDevice() {
        return TripperPrefs.hasSavedDevice(appContext);
    }

    String savedDeviceName() {
        return TripperPrefs.pairedName(appContext);
    }

    /** Comeca a escanear+conectar (novo pareamento OU reconexao com dispositivo conhecido). */
    @SuppressLint("MissingPermission")
    void start() {
        if (bluetoothGatt != null || scanning) return;
        startGattServer();
        startScan(SCAN_TIMEOUT_MS, false);
    }

    /** Scan continuo da reconexao automatica: dura a janela inteira (o Pod so' anuncia por <30 s ao ligar a moto). */
    private void startAutoScan(long durationMs) {
        if (bluetoothGatt != null || scanning) return;
        startGattServer();
        startScan(durationMs, true);
    }

    // ---------- reconexao automatica ---------------------------------------------------------
    // Ao abrir o Waze e apos uma queda, procura o Pod conhecido a cada AUTO_INTERVAL_MS (scan de
    // 15 s), por ate AUTO_WINDOW_MS; depois para (o botao Reconectar do painel continua valendo).
    // Parar antes: interruptor no painel, ou Desconectar/Cancelar (userDisconnect). Nada disso roda
    // sem pareamento salvo, e fechar o Waze mata o processo (e as tentativas) junto.

    /** Chamado uma vez por processo, no startup do Waze (TripperOverlay). */
    void startAutoOnce() {
        if (autoStartedOnce) return;
        autoStartedOnce = true;
        TripperCall.start(appContext, this);
        armAuto(1500);
    }

    /** Botao Conectar/Reconectar do painel: conexao manual; religa a automatica pra quedas futuras. */
    void userConnect() {
        userStopped = false;
        start();
    }

    /** Botao Desconectar/Cancelar do painel: para de procurar sozinho ate o proximo Reconectar. */
    void userDisconnect() {
        userStopped = true;
        main.removeCallbacks(autoTick);
        disconnect();
    }

    /** O usuario mexeu no interruptor "Reconexao automatica" do painel. */
    void onAutoReconnectPrefChanged() {
        if (TripperPrefs.isAutoReconnect(appContext)) {
            userStopped = false;
            if (state == State.DISCONNECTED) armAuto(0);
        } else {
            main.removeCallbacks(autoTick);
        }
    }

    /** Abre uma janela nova de 30 min e agenda a primeira tentativa (no-op se desligada/sem pareamento). */
    private void armAuto(long delayMs) {
        main.removeCallbacks(autoTick);
        if (userStopped || !TripperPrefs.isAutoReconnect(appContext) || !hasSavedDevice()) return;
        autoDeadline = SystemClock.elapsedRealtime() + AUTO_WINDOW_MS;
        autoLogged = false;
        autoBtOffLogged = false;
        autoGaveUp = false;
        main.postDelayed(autoTick, delayMs);
    }

    /** Uma tentativa (main thread). Nunca deixa excecao escapar: uma falha aqui derrubaria o Waze. */
    private void autoAttempt() {
        try {
            if (userStopped || !TripperPrefs.isAutoReconnect(appContext) || !hasSavedDevice()) return;
            if (state == State.CONNECTED) return; // conectou; a proxima queda reabre a janela
            long remaining = autoDeadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                giveUpAuto();
                return;
            }
            boolean scanningAuto = state == State.SCANNING && autoScan;
            if (state == State.DISCONNECTED || scanningAuto) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                        || appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
                    log("Reconexao automatica: o Waze nao tem permissao de dispositivos proximos; desativada nesta sessao.");
                    return;
                }
                android.bluetooth.BluetoothAdapter adapter = bluetoothManager.getAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    if (scanningAuto) {
                        stopScan();
                        setState(State.DISCONNECTED);
                    }
                    if (!autoBtOffLogged) {
                        autoBtOffLogged = true;
                        log("Reconexao automatica: Bluetooth desligado; tento de novo quando ligar.");
                    }
                } else {
                    autoBtOffLogged = false;
                    // O scan e' um so' e dura a janela toda (o Pod anuncia por menos de 30 s ao ligar a
                    // moto, entao nao pode haver buracos). A cada tick ele e' reiniciado (1x/30 s, dentro
                    // do limite de 5 starts/30 s do Android): cura um scan que morreu se o Bluetooth foi
                    // desligado e religado.
                    if (scanningAuto) stopScan();
                    if (!autoLogged) {
                        autoLogged = true;
                        log("Reconexao automatica: procurando o Tripper conhecido (scan continuo por ate 30 min)...");
                    }
                    startAutoScan(remaining);
                }
            }
            main.postDelayed(autoTick, AUTO_INTERVAL_MS);
        } catch (Throwable t) {
            TripperLog.e("WazeTripper", "reconexao automatica falhou", t);
        }
    }

    private void giveUpAuto() {
        if (autoGaveUp) return;
        autoGaveUp = true;
        log("Reconexao automatica: parei de procurar apos 30 min. Toque em Reconectar no painel.");
    }

    /** Chamado pela UI quando o usuario confirma o PIN mostrado no Pod. */
    void confirmPin(String pin) {
        if (pin == null || pin.trim().isEmpty()) {
            log("PIN vazio - nada enviado.");
            return;
        }
        enqueuePacket(TripperProtocol.buildPinPacket(pin));
        if (currentDeviceMac != null) {
            TripperPrefs.markPaired(appContext, currentDeviceMac, currentDeviceName);
            log("Tripper salvo como pareado (" + currentDeviceMac + ").");
        }
        setState(State.CONNECTED);
    }

    /**
     * Reenvia o horario do celular pro Pod (usa o formato 12h/24h salvo). false se nao ha link.
     * Reenvio sob demanda do pacote de hora (SET_TIME).
     */
    boolean syncClockNow() {
        if (bluetoothGatt == null || state != State.CONNECTED) return false;
        enqueuePacket(TripperProtocol.buildSetTimeNowPacket(TripperPrefs.is12h(appContext)));
        return true;
    }

    /** Envia um pacote de navegacao (ver TripperNav). Ignorado se nao ha link. */
    void sendNavPacket(byte[] packet) {
        if (bluetoothGatt == null || callActive) return; // ligacao tem prioridade sobre a tela
        enqueuePacket(packet);
    }

    /** Envia a direcao da bussola (ver CompassManager). Ignorado se nao ha link. */
    void sendCompassPacket(byte direction) {
        if (bluetoothGatt == null || callActive) return;
        enqueuePacket(TripperProtocol.buildCompassPacket(direction, TripperNight.isNight()));
    }

    /** O que esta "no controle" da tela do Pod. Prioridade: navegacao > bussola > relogio nativo. */
    private enum Mode {NONE, CLOCK, COMPASS, NAV}

    private Mode mode = Mode.NONE; // so' tocado na main thread (ver applyMode)

    // ---------- icone de ligacao -------------------------------------------------------------
    // Comando proprio do Pod (CMD_KEEPALIVE 0x40, sub-byte 0x05): o Pod desenha o icone sozinho, como
    // sobreposicao (nao e' um codigo de icone que caiba na seta pequena). Enquanto durar a ligacao as
    // telas de bussola/navegacao ficam suspensas e o keepalive reenvia o icone; ao terminar, a tela
    // volta no proximo reenvio (a cada 2 s) ou na hora, na navegacao.

    private volatile boolean callActive;

    boolean isCallActive() {
        return callActive;
    }

    /** Chamado pelo TripperCall (qualquer thread): a ligacao comecou/terminou. So' vale com o interruptor ligado. */
    void setCallActive(boolean active) {
        main.post(() -> {
            boolean on = active && TripperPrefs.isCallIcon(appContext);
            if (on == callActive) return;
            callActive = on;
            log(on ? "Ligacao: icone enviado ao Tripper (tamanho/posicao sao decididos pelo firmware)."
                    : "Ligacao: icone retirado; a tela volta.");
            if (state != State.CONNECTED || bluetoothGatt == null || writeCharacteristic == null) return;
            if (on) {
                enqueuePacket(TripperProtocol.buildCallIconPacket());
            } else if (mode == Mode.NAV) {
                TripperNav.resendNow();
            }
        });
    }

    /** O interruptor "Icone de ligacao" do painel mudou. */
    void onCallPrefChanged() {
        if (!TripperPrefs.isCallIcon(appContext)) setCallActive(false);
    }

    // ---------- voltar ao relogio ------------------------------------------------------------

    /**
     * Saindo de uma tela (bussola/navegacao) pro relogio nativo: nao manda mais nenhuma tela, so' o keepalive.
     * O Tripper fica na ultima tela ~5 s (watchdog) e derruba o link sozinho; como ele volta a anunciar na
     * hora, a reconexao automatica (armAuto) o recupera ja no relogio. Testado no Tripper: mandar a tela 0x1C
     * uma vez so' mostra o icone de recalculando e o link cai igual; derrubar o link daqui e reconectar nao
     * funciona (o Tripper nao volta a anunciar); ping, handshake repetido e tela ociosa tambem nao seguram o link.
     */
    private void leaveScreenForClock() {
        log("Voltando ao relogio: sem telas, so' keepalive; o Tripper deve cair em ~5 s e reconecto em seguida.");
    }

    /**
     * Reavalia o modo da tela do Pod. Chamado a cada mudanca de estado da conexao, quando a
     * navegacao comeca/termina e quando o usuario mexe no switch da bussola.
     */
    void refreshMode() {
        main.post(this::applyMode);
    }

    private void applyMode() {
        Mode next;
        if (state != State.CONNECTED) {
            next = Mode.NONE;
        } else if (TripperNav.isNavigating()) {
            next = Mode.NAV;
        } else if (TripperPrefs.isCompassEnabled(appContext)) {
            next = Mode.COMPASS;
        } else {
            next = Mode.CLOCK;
        }

        if (next == mode) {
            // A bussola pode ter falhado ao iniciar (ex.: sem permissao); tenta de novo (no-op se ja ativa).
            if (mode == Mode.COMPASS && compass != null) compass.start();
            return;
        }
        Mode prev = mode;
        mode = next;
        log("Modo da tela do Tripper: " + prev + " -> " + next);

        if (prev == Mode.COMPASS && compass != null) compass.stop();
        if (prev == Mode.NAV) TripperNav.setSending(false);

        if (next == Mode.COMPASS) {
            if (compass == null) compass = new CompassManager(appContext, this);
            compass.start();
        } else if (next == Mode.NAV) {
            TripperNav.setSending(true);
        }

        // Saindo de uma tela (bussola/navegacao) pro relogio nativo: ver leaveScreenForClock().
        if (next == Mode.CLOCK && (prev == Mode.COMPASS || prev == Mode.NAV)) {
            leaveScreenForClock();
        }
    }

    @SuppressLint("MissingPermission")
    void disconnect() {
        stopScan();
        stopKeepAlive();
        sendQueue.clear();
        writePending = false;
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
        }
        bluetoothGatt = null;
        writeCharacteristic = null;
        currentDeviceMac = null;
        currentDeviceName = null;
        stopGattServer();
        setState(State.DISCONNECTED);
    }

    void forget() {
        main.removeCallbacks(autoTick);
        disconnect();
        TripperPrefs.forget(appContext);
        log("Pareamento esquecido.");
    }

    // ---------- GATT Server local (canal de volta do Pod) -----------------------------------
    // Mesma logica de um cliente BLE anterior do autor, portada. Nao anuncia nada
    // (sem BluetoothLeAdvertiser): o Pod escreve de volta pela mesma conexao ja estabelecida
    // do lado client, entao BLUETOOTH_ADVERTISE nao e' necessario (ver docs/PROTOCOL.md).

    @SuppressLint("MissingPermission")
    private void startGattServer() {
        if (gattServer != null) return;
        BluetoothGattServer server = bluetoothManager.openGattServer(appContext, gattServerCallback);
        if (server == null) {
            log("GATT Server: openGattServer() retornou null.");
            return;
        }
        gattServer = server;
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        service.addCharacteristic(characteristic);
        boolean ok = server.addService(service);
        log("GATT Server local: " + (ok ? "aberto" : "falhou ao adicionar o servico"));
    }

    @SuppressLint("MissingPermission")
    private void stopGattServer() {
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String estado = newState == BluetoothProfile.STATE_CONNECTED ? "conectou" : "desconectou";
            log("[Server] Tripper " + estado + " no nosso GATT Server (" + device.getAddress() + ", status=" + status + ").");
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                  BluetoothGattCharacteristic characteristic,
                                                  boolean preparedWrite, boolean responseNeeded,
                                                  int offset, byte[] value) {
            byte[] bytes = value != null ? value : new byte[0];
            log("<- [Tripper via Server] " + TripperProtocol.toHexString(bytes));
            if (responseNeeded && gattServer != null) {
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
                } catch (Exception e) {
                    log("GATT Server: sendResponse falhou: " + e.getMessage());
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                 BluetoothGattCharacteristic characteristic) {
            if (gattServer == null) return;
            try {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
            } catch (Exception e) {
                log("GATT Server: sendResponse (read) falhou: " + e.getMessage());
            }
        }
    };

    // ---------- Scan / conexao como client ---------------------------------------------------

    @SuppressLint("MissingPermission")
    private void startScan(long timeoutMs, boolean auto) {
        android.bluetooth.BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("Bluetooth do celular desligado ou indisponivel.");
            setState(State.DISCONNECTED);
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("BluetoothLeScanner indisponivel.");
            setState(State.DISCONNECTED);
            return;
        }

        ScanFilter filter = new ScanFilter.Builder().setDeviceName(DEVICE_NAME).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        autoScan = auto;
        setState(State.SCANNING);
        if (!auto) log("Iniciando scan por \"" + DEVICE_NAME + "\"...");

        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);

        scanTimeout = () -> {
            scanTimeout = null;
            if (scanning) {
                scanner.stopScan(scanCallback);
                scanning = false;
                autoScan = false;
                setState(State.DISCONNECTED);
                if (auto) giveUpAuto();
                else log("Scan encerrado - \"" + DEVICE_NAME + "\" nao encontrado em 15s.");
            }
        };
        main.postDelayed(scanTimeout, timeoutMs);
    }

    /** Para um scan em andamento e cancela o timeout de 15s dele (usado pelo disconnect/cancelar). */
    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanTimeout != null) {
            main.removeCallbacks(scanTimeout);
            scanTimeout = null;
        }
        if (!scanning) return;
        scanning = false;
        autoScan = false;
        android.bluetooth.BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter != null && adapter.getBluetoothLeScanner() != null) {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning) return;
            scanning = false;
            autoScan = false;
            if (scanTimeout != null) {
                main.removeCallbacks(scanTimeout);
                scanTimeout = null;
            }
            android.bluetooth.BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().stopScan(this);
            }
            currentDeviceMac = result.getDevice().getAddress();
            currentDeviceName = result.getDevice().getName();
            log("Encontrado: " + currentDeviceName + " (" + currentDeviceMac + ") - conectando...");
            setState(State.CONNECTING);
            bluetoothGatt = result.getDevice().connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            autoScan = false;
            if (scanTimeout != null) {
                main.removeCallbacks(scanTimeout);
                scanTimeout = null;
            }
            setState(State.DISCONNECTED);
            log("Scan falhou, codigo " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Conectado. Descobrindo servicos...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stopKeepAlive();
                sendQueue.clear();
                writePending = false;
                log("Desconectado do Tripper (status=" + status + ").");
                writeCharacteristic = null;
                bluetoothGatt = null;
                currentDeviceMac = null;
                currentDeviceName = null;
                setState(State.DISCONNECTED);
                // Status 19 = o proprio Tripper fechou o link (watchdog): ele volta a anunciar na hora, entao a espera e' curta.
                armAuto(status == 19 ? AUTO_AFTER_TRIPPER_DROP_MS : AUTO_AFTER_DROP_MS);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                log("Servico " + SERVICE_UUID + " nao encontrado!");
                return;
            }
            BluetoothGattCharacteristic ch = service.getCharacteristic(CHAR_UUID);
            if (ch == null) {
                log("Caracteristica " + CHAR_UUID + " nao encontrada!");
                return;
            }
            writeCharacteristic = ch;

            int props = ch.getProperties();
            boolean supportsWriteNoResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
            writeType = supportsWriteNoResponse
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

            gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
            if (cccd == null && !ch.getDescriptors().isEmpty()) {
                cccd = ch.getDescriptors().get(0);
            }
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(cccd);
                }
            }

            log("Pronto. properties=0x" + Integer.toHexString(props) + " - escrita "
                    + (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? "SEM" : "COM") + " resposta.");

            // Delay de 200ms antes do handshake - igual ao app real, confirmado necessario no
            // TripperTester (escrever rapido demais aqui faz o Pod derrubar a conexao).
            main.postDelayed(TripperBridge.this::startHandshake, 200);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            writePending = false;
            pumpQueue();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            log("RX (notify): " + TripperProtocol.toHexString(value));
        }

        // Overload pre-API 33 (minSdk do Waze patchado e' 32, ver apktool.yml do wazeology) -
        // o metodo com 'value' explicito so existe a partir do Tiramisu.
        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) {
                log("RX (notify): " + TripperProtocol.toHexString(value));
            }
        }
    };

    /**
     * Mesma logica de um cliente BLE anterior do autor. Dispositivo conhecido (MAC
     * salvo em TripperPrefs): ramo rapido (HANDSHAKE_RESUME + SET_TIME + PING_FW x2, timings
     * 200/150/300ms) direto pra CONNECTED, sem PIN. Dispositivo novo: HANDSHAKE_SHOW_PIN, Pod
     * mostra o PIN na tela dele, app fica em WAITING_PIN ate confirmPin() ser chamado.
     */
    private void startHandshake() {
        boolean known = currentDeviceMac != null && currentDeviceMac.equals(TripperPrefs.pairedMac(appContext));

        if (known) {
            log("Handshake: Tripper ja pareado - reconectando sem PIN (21 00)...");
            enqueuePacket(TripperProtocol.HANDSHAKE_RESUME);
            startKeepAlive();
            main.postDelayed(() -> {
                enqueuePacket(TripperProtocol.buildSetTimeNowPacket(TripperPrefs.is12h(appContext)));
                main.postDelayed(() -> {
                    enqueuePacket(TripperProtocol.PING_FW);
                    enqueuePacket(TripperProtocol.PING_FW);
                    main.postDelayed(() -> {
                        setState(State.CONNECTED);
                    }, 300);
                }, 150);
            }, 200);
        } else {
            log("Handshake: Tripper novo - mandando SHOW PIN (0x21 01)...");
            enqueuePacket(TripperProtocol.HANDSHAKE_SHOW_PIN);
            startKeepAlive();
            setState(State.WAITING_PIN);
        }
    }

    /** O Pod derruba a conexao 5s depois do ultimo pacote recebido - keepalive com PING_FW. */
    private void startKeepAlive() {
        stopKeepAlive();
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (bluetoothGatt == null) return;
                long idleMs = System.currentTimeMillis() - lastSendMillis;
                if (idleMs >= 2000) {
                    // Com ligacao ativa o keepalive reenvia o icone dela (como outro app do Tripper).
                    enqueuePacket(callActive ? TripperProtocol.buildCallIconPacket() : TripperProtocol.PING_FW);
                }
                main.postDelayed(this, 1000);
            }
        };
        keepAliveRunnable = runnable;
        main.postDelayed(runnable, 1000);
    }

    private void stopKeepAlive() {
        if (keepAliveRunnable != null) {
            main.removeCallbacks(keepAliveRunnable);
            keepAliveRunnable = null;
        }
    }

    // ---------- Envio (fila) -----------------------------------------------------------------

    private void enqueuePacket(byte[] bytes) {
        if (bluetoothGatt == null || writeCharacteristic == null) {
            log("Nao conectado - pacote descartado.");
            return;
        }
        main.post(() -> {
            sendQueue.addLast(bytes);
            pumpQueue();
        });
    }

    @SuppressLint("MissingPermission")
    private void pumpQueue() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::pumpQueue);
            return;
        }
        if (writePending) return;
        BluetoothGatt gatt = bluetoothGatt;
        BluetoothGattCharacteristic ch = writeCharacteristic;
        if (gatt == null || ch == null) return;
        byte[] bytes = sendQueue.pollFirst();
        if (bytes == null) return;

        writePending = true;
        lastSendMillis = System.currentTimeMillis();
        log("TX: " + TripperProtocol.toHexString(bytes));
        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = gatt.writeCharacteristic(ch, bytes, writeType) == BluetoothStatusCodes.SUCCESS;
        } else {
            ch.setWriteType(writeType);
            ch.setValue(bytes);
            ok = gatt.writeCharacteristic(ch);
        }
        if (!ok) {
            log("writeCharacteristic() falhou na hora - liberando pra tentar o proximo.");
            writePending = false;
            main.post(this::pumpQueue);
        }
    }

    // ---------- util ---------------------------------------------------------------------------

    private void setState(State s) {
        state = s;
        refreshMode();
        StateObserver so = stateObserver;
        if (so != null) {
            main.post(() -> so.onStateChanged(s));
        }
        Listener l = listener;
        if (l != null) {
            main.post(() -> l.onStateChanged(s));
        }
    }

    void log(String msg) {
        // Grava sempre (logcat + arquivo do trajeto): o painel pode estar fechado, e o ouvinte so' mostra.
        TripperLog.i("WazeTripper", "BLE " + msg);
        Listener l = listener;
        if (l != null) {
            main.post(() -> l.onLog(msg));
        }
    }
}
