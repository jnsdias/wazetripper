package com.waze.wazetripper;

import java.util.Calendar;
import java.util.Locale;

/**
 * TripperProtocol: montagem de pacotes de 20 bytes do protocolo BLE do Tripper Pod (Royal
 * Enfield Meteor 350). Porta direta, em Java puro (sem Kotlin, seguindo a
 * convenção do wazeology). Cobre CRC, pacotes fixos de handshake, PIN, SET_TIME, bússola, o
 * pacote de navegação e a tradução das manobras do Waze. Formato e tabelas: docs/PROTOCOL.md.
 *
 * Layout do pacote de navegação (CMD_NAVIGATE = 0x10 0x11), confirmado em produção:
 *   [0]=0x10 [1]=0x11 [2]=manobra [3-4]=distância (big-endian) [5]=unidade [6]=intensidade/noturno
 *   [7]=proxima manobra [8-9]=distancia ate o radar (0xFFFF = sem dado; hipotese, ver docs/PROTOCOL.md)
 *   [10]=0x41 fixo [11-13]=info de baixo (0xFF/0xFF/0xFF = sem dado)
 *   [14-17]=0x00 [18-19]=CRC-16/CCITT-FALSE dos bytes [0..17].
 */
final class TripperProtocol {

    private TripperProtocol() {
    }

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, MSB-first, sem XOR final. */
    static int crc16(byte[] buf) {
        int crc = 0xFFFF;
        for (byte b : buf) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

    static byte[] withCrc(byte[] first18) {
        if (first18.length != 18) {
            throw new IllegalArgumentException("esperava 18 bytes, recebi " + first18.length);
        }
        int crc = crc16(first18);
        byte[] out = new byte[20];
        System.arraycopy(first18, 0, out, 0, 18);
        out[18] = (byte) ((crc >> 8) & 0xFF);
        out[19] = (byte) (crc & 0xFF);
        return out;
    }

    static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    static String toHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b));
        }
        return sb.toString();
    }

    // ---------- Pacotes fixos ----------

    /** Handshake "dispositivo novo" (0x21 01): Pod mostra o PIN na tela dele. */
    static final byte[] HANDSHAKE_SHOW_PIN =
            hex("21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7");

    /** Handshake "reconexão" (0x21 00): dispositivo já pareado antes, sem pedir PIN de novo. */
    static final byte[] HANDSHAKE_RESUME =
            hex("21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45");

    static final byte[] PING_FW =
            hex("03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9");

    /** Confirmação de PIN (CMD 0x20): [1..6]=PIN em ASCII (até 6 chars), resto 0x00. */
    static byte[] buildPinPacket(String pin) {
        byte[] buf = new byte[18];
        buf[0] = 0x20;
        byte[] pinBytes = pin.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(pinBytes.length, 6);
        System.arraycopy(pinBytes, 0, buf, 1, n);
        return withCrc(buf);
    }

    /**
     * CMD_SET_TIME (0x50): [1]=hora, [2]=minuto. Em 24h a hora e' crua (0-23); em 12h e' a hora
     * (1-12) com o bit 0x40 somado quando e' AM (nada somado em PM). Mesmo esquema dos
     * outros apps do Tripper.
     */
    static byte[] buildSetTimeNowPacket(boolean is12h) {
        Calendar now = Calendar.getInstance();
        return buildSetTimePacket(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), is12h);
    }

    /** Nucleo deterministico do SET_TIME (testavel sem depender do relogio; ver PacketsCheck). */
    static byte[] buildSetTimePacket(int hour24, int minute, boolean is12h) {
        byte[] buf = new byte[18];
        buf[0] = 0x50;
        if (!is12h) {
            buf[1] = (byte) hour24;
        } else {
            int hour12 = hour24 % 12;
            if (hour12 == 0) hour12 = 12;
            buf[1] = (byte) (hour12 | (hour24 < 12 ? 0x40 : 0x00));
        }
        buf[2] = (byte) minute;
        return withCrc(buf);
    }

    /**
     * Direcao da bussola (setor 0-7, N/NE/E/SE/S/SW/W/NW) a partir de um rumo em graus (0=Norte),
     * convertida no byte que o Pod entende. Tabela de direcoes
     * (nao e' uma escala linear).
     */
    static byte bearingToDirection(float bearing) {
        int sector = (int) (((bearing + 22.5f) % 360f) / 45f);
        sector = Math.max(0, Math.min(7, sector));
        int value;
        switch (sector) {
            case 0: value = 0x10; break; // N
            case 1: value = 0x50; break; // NE
            case 2: value = 0x20; break; // E
            case 3: value = 0x70; break; // SE
            case 4: value = 0x40; break; // S
            case 5: value = 0x80; break; // SW
            case 6: value = 0x30; break; // W
            default: value = 0x60; break; // NW
        }
        return (byte) value;
    }

    /**
     * "Bussola sem rota": reaproveita CMD_NAVIGATE (0x10 0x11) com a tela especial 0x41 no byte
     * [2]; [14]=direcao, [7]/[11..13]=0xFF (sem dado de distancia/ETA). Formato validado no Pod;
     * nightMode liga o byte [6] (tema noturno).
     */
    static byte[] buildCompassPacket(byte direction, boolean nightMode) {
        byte[] buf = new byte[18];
        buf[0] = 0x10;
        buf[1] = 0x11;
        buf[2] = 0x41;
        buf[6] = (byte) (nightMode ? 1 : 0);
        buf[7] = (byte) 0xFF;
        buf[11] = (byte) 0xFF;
        buf[12] = (byte) 0xFF;
        buf[13] = (byte) 0xFF;
        buf[14] = direction;
        return withCrc(buf);
    }

    /**
     * Codifica uma distância (metros) em [byteAlto, byteBaixo, unidade]. Até 999m manda o valor
     * em metros (unidade 1); acima disso, em "km vezes 10" (unidade 2).
     */
    static int[] encodeDistance(int meters) {
        int value;
        int unit;
        if (meters <= 999) {
            value = meters;
            unit = 1;
        } else {
            value = meters / 100;
            unit = 2;
        }
        int v = Math.max(0, Math.min(value, 0xFFFF));
        return new int[]{(v >> 8) & 0xFF, v & 0xFF, unit};
    }

    /**
     * Pacote de navegação (CMD_NAVIGATE). next é o byte [7]. bottomInfo é [b11, b12, b13]; use {0xFF,0xFF,0xFF}
     * quando não há informação de baixo de tela ainda (sentinela).
     */
    static byte[] buildNavPacket(int maneuver, int distanceM, int next, int nextDistanceM, int[] bottomInfo, int byte6) {
        int[] dist = encodeDistance(distanceM);
        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = (byte) maneuver;
        pkt[3] = (byte) dist[0];
        pkt[4] = (byte) dist[1];
        pkt[5] = (byte) dist[2];
        pkt[6] = (byte) byte6; // intensidade por distancia (+1 a noite) ou so' o flag noturno
        pkt[7] = (byte) next; // proxima manobra (seta pequena); 0xFF = sem dado; 0x3C = radar a frente
        // [8-9]: distancia menor, ao lado da seta pequena (so' usada pelo radar; 0xFFFF = sem dado).
        // HIPOTESE nao verificada no Pod: nas outras telas estes bytes vao sempre em FF FF.
        int nd = nextDistanceM < 0 ? 0xFFFF : Math.min(nextDistanceM, 0xFFFE);
        pkt[8] = (byte) ((nd >> 8) & 0xFF);
        pkt[9] = (byte) (nd & 0xFF);
        pkt[10] = 0x41;
        pkt[11] = (byte) bottomInfo[0];
        pkt[12] = (byte) bottomInfo[1];
        pkt[13] = (byte) bottomInfo[2];
        return withCrc(pkt);
    }

    /**
     * Byte de manobra do Pod (byte [2] do pacote de navegacao) a partir do nome do
     * Instruction$Type do Waze; -1 se nao ha traducao (quem chama mantem a manobra anterior).
     * Tabela em docs/PROTOCOL.md (mao direita = Brasil). exit e' o ordinal da
     * saida da rotatoria (<= 0 quando nao ha). Linhas de baixa confianca na tabela
     * (parada, destino intermediario, faixa HOV) ainda precisam de teste fisico no Pod.
     */
    static int maneuverByte(String type, int exit) {
        if (type == null) return -1;
        switch (type) {
            case "TURN_LEFT":
            case "PREPARE_TURN_LEFT":
                return 0x14;
            case "TURN_RIGHT":
            case "PREPARE_TURN_RIGHT":
                return 0x15;
            case "SHARP_LEFT":
                return 0x16;
            case "SHARP_RIGHT":
                return 0x17;
            case "SLIGHT_LEFT":
                return 0x18;
            case "SLIGHT_RIGHT":
                return 0x19;
            case "KEEP_LEFT":
                return 0x28;
            case "KEEP_RIGHT":
                return 0x27;
            case "CONTINUE_STRAIGHT":
            case "ENTER_HOV_LANE":
                return MANEUVER_STRAIGHT;
            case "U_TURN":
                return 0x3D;
            case "EXIT_LEFT":
            case "PREPARE_EXIT_LEFT":
                return 0x2E;
            case "EXIT_RIGHT":
            case "PREPARE_EXIT_RIGHT":
                return 0x2D;
            case "APPROACHING_DESTINATION":
            case "LAST_DIRECTION":
            case "APPROACHING_STOP_POINT":
            case "WAYPOINT_DELAY":
                return 0x00; // DESTINATION
            default:
                if (type.startsWith("ROUNDABOUT")) {
                    int n = Math.max(1, Math.min(9, exit));
                    return 0x32 + (n - 1);
                }
                return -1; // NAV_INSTR_NONE, UNKNOWN, UNRECOGNIZED
        }
    }

    /**
     * Byte [7] quando ha radar a frente: substitui a seta da proxima manobra pelo DEPART (0x3C), o mesmo
     * que outros apps do Pod usam para radar. O 0x45 ("rota iniciada") foi testado nessa posicao e o Pod
     * nao desenhou nenhum icone; ele so' funciona como icone grande (byte [2]).
     */
    static final int NEXT_RADAR = 0x3C;
    /** Byte [7] sem dado de proxima manobra. */
    static final int NEXT_NONE = 0xFF;

    /**
     * Byte [7] (seta pequena da proxima manobra). Igual ao byte [2] (maneuverByte), exceto: manter/sair
     * de via (KEEP_*, EXIT_*) usa os icones genericos 0x2B (esquerda) / 0x2C (direita), como na
     * producao (ver docs/PROTOCOL.md); e LAST_DIRECTION/NAV_INSTR_NONE significam "sem proxima
     * manobra" no Waze (nao "destino"), entao viram NEXT_NONE.
     */
    static int nextManeuverByte(String type, int exit) {
        if (type == null) return NEXT_NONE;
        switch (type) {
            case "KEEP_LEFT":
            case "EXIT_LEFT":
            case "PREPARE_EXIT_LEFT":
                return 0x2B;
            case "KEEP_RIGHT":
            case "EXIT_RIGHT":
            case "PREPARE_EXIT_RIGHT":
                return 0x2C;
            case "LAST_DIRECTION":
                return NEXT_NONE;
            default:
                int b = maneuverByte(type, exit);
                return b >= 0 ? b : NEXT_NONE;
        }
    }

    /** O que mostrar na parte de baixo da tela de navegacao (bytes [11-13]). */
    static final int BOTTOM_TOTAL_DISTANCE = 0;
    static final int BOTTOM_TIME_REMAINING = 1;
    static final int BOTTOM_ARRIVAL_TIME = 2;

    /**
     * Bytes [11-13] conforme o modo escolhido; {0xFF,0xFF,0xFF} quando o dado ainda nao existe.
     * O horario de chegada em 12h usa o esquema proprio do
     * ETA: Calendar.HOUR (0-11) com 0x40 somado se AM ou 0x80 se PM (diferente do
     * relogio, buildSetTimeNowPacket).
     */
    static int[] buildBottomInfoBytes(int mode, int etaMinutes, int totalDistanceM, boolean is12h) {
        return buildBottomInfoBytes(mode, etaMinutes, totalDistanceM, is12h, System.currentTimeMillis());
    }

    /** Igual ao anterior, com o "agora" explicito (hora de chegada = agora + etaMinutes); testavel. */
    static int[] buildBottomInfoBytes(int mode, int etaMinutes, int totalDistanceM, boolean is12h, long nowMillis) {
        int[] sentinel = {0xFF, 0xFF, 0xFF};
        switch (mode) {
            case BOTTOM_TIME_REMAINING:
                if (etaMinutes <= 0) return sentinel;
                return new int[]{Math.min(etaMinutes / 60, 23), Math.min(etaMinutes % 60, 59), 0};
            case BOTTOM_ARRIVAL_TIME: {
                if (etaMinutes <= 0) return sentinel;
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(nowMillis);
                cal.add(Calendar.MINUTE, etaMinutes);
                int minute = cal.get(Calendar.MINUTE);
                if (!is12h) return new int[]{cal.get(Calendar.HOUR_OF_DAY), minute, 0};
                int hour12 = cal.get(Calendar.HOUR); // 0-11
                boolean pm = cal.get(Calendar.AM_PM) == Calendar.PM;
                return new int[]{hour12 | (pm ? 0x80 : 0x40), minute, 0};
            }
            case BOTTOM_TOTAL_DISTANCE:
            default:
                return totalDistanceM >= 0 ? encodeDistance(totalDistanceM) : sentinel;
        }
    }

    /** Byte de manobra "seguir em frente": confirmado (KNOWN + catálogo + produção). */
    static final int MANEUVER_STRAIGHT = 0x09;

    /** Icone GRANDE de "rota iniciada" (byte [2]); mostrado ~2 s ao comecar a navegacao. */
    static final int MANEUVER_ROUTE_STARTED = 0x45;
    /** Tela de "carregando"/"recalculando" (byte [2] = 0x1C, o icone RE_ROUTE da tabela validada no Pod). */
    static final int SCREEN_LOADING = 0x1C;

    /** Pacote de uma tela sem dados (so' o byte [2] e o flag noturno), ex.: carregando/recalculando. */
    static byte[] buildScreenPacket(int screen, boolean night) {
        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = (byte) screen;
        pkt[6] = (byte) (night ? 1 : 0);
        return withCrc(pkt);
    }

    /** Icone de ligacao: CMD_KEEPALIVE (0x40) com sub-byte 0x05 (nao e' CMD_NAVIGATE); reenviado enquanto durar. */
    static byte[] buildCallIconPacket() {
        byte[] pkt = new byte[18];
        pkt[0] = 0x40;
        pkt[1] = 0x05;
        return withCrc(pkt);
    }

    /** Byte [6] do pacote de navegacao: intensidade por faixa de distancia ate a manobra (+1 a noite). */
    static int distanceIntensity(int meters, boolean night) {
        int v;
        if (meters <= 10) v = 0x50;
        else if (meters <= 20) v = 0x40;
        else if (meters <= 45) v = 0x30;
        else if (meters <= 70) v = 0x20;
        else if (meters <= 95) v = 0x10;
        else v = 0x00;
        return v + (night ? 1 : 0);
    }

}
