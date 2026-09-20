package com.waze.wazetripper;

import java.util.Calendar;

/**
 * Verificacao, sem hardware, do layout exato dos pacotes do Tripper (roda na JVM do computador; ver
 * scripts/framecheck.sh, que tambem trava o build).
 *
 * As esperas vem de tres fontes, marcadas em cada verificacao:
 *  - REAL: pacote capturado do log de um Tripper de verdade (o aparelho recebeu e desenhou);
 *  - EXT:  valor de referencia externo (o valor de verificacao padrao do CRC-16/CCITT-FALSE, pacotes
 *          fixos documentados de outros apps do Tripper);
 *  - LOCK: trava de regressao - o layout ainda nao foi visto no Tripper; o teste so' impede que ele
 *          mude sem querer.
 */
public class PacketsCheck {

    static int failures = 0;
    static int checks = 0;

    static void eq(String what, String got, String want) {
        checks++;
        boolean ok = got.equals(want);
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            System.out.println("   got:  " + got);
            System.out.println("   want: " + want);
            failures++;
        }
    }

    static void yes(String what, boolean cond) {
        checks++;
        System.out.println((cond ? "PASS " : "FAIL ") + what);
        if (!cond) failures++;
    }

    static String h(byte[] b) {
        return TripperProtocol.toHexString(b);
    }

    static String h(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int x : v) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", x & 0xFF));
        }
        return sb.toString();
    }

    /** CRC-16/CCITT-FALSE bit a bit, independente da implementacao de producao. */
    static int refCrc(byte[] b, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    /** Todo pacote tem 20 bytes e termina com o CRC dos 18 primeiros (big-endian). */
    static void wellFormed(String what, byte[] p) {
        int crc = refCrc(p, 18);
        yes(what + " tem 20 bytes e CRC valido",
                p.length == 20 && (p[18] & 0xFF) == ((crc >> 8) & 0xFF) && (p[19] & 0xFF) == (crc & 0xFF));
    }

    static byte[] nav(int man, int dist, int next, int nextDist, int[] bottom, int b6) {
        return TripperProtocol.buildNavPacket(man, dist, next, nextDist, bottom, b6);
    }

    public static void main(String[] args) {

        // ---------- CRC ----------
        yes("EXT crc16('123456789') = 0x29B1 (valor padrao do CRC-16/CCITT-FALSE)",
                TripperProtocol.crc16("123456789".getBytes()) == 0x29B1);
        yes("crc16 de producao == referencia bit a bit (padrao 0..255)", crcAgrees());
        eq("EXT STOP_NAV de outro app do Tripper (CRC F7 82)",
                h(TripperProtocol.withCrc(TripperProtocol.hex("10 11 1C 00 00 01 00 FF 00 00 00 00 00 00 00 00 00 00"))),
                "10 11 1C 00 00 01 00 FF 00 00 00 00 00 00 00 00 00 00 F7 82");

        // ---------- pacotes fixos ----------
        eq("REAL PING_FW", h(TripperProtocol.PING_FW), "03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9");
        eq("REAL HANDSHAKE_RESUME", h(TripperProtocol.HANDSHAKE_RESUME), "21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45");
        eq("REAL HANDSHAKE_SHOW_PIN", h(TripperProtocol.HANDSHAKE_SHOW_PIN), "21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7");
        wellFormed("PING_FW", TripperProtocol.PING_FW);
        wellFormed("HANDSHAKE_RESUME", TripperProtocol.HANDSHAKE_RESUME);
        wellFormed("HANDSHAKE_SHOW_PIN", TripperProtocol.HANDSHAKE_SHOW_PIN);

        // ---------- PIN ----------
        byte[] pin = TripperProtocol.buildPinPacket("1234");
        wellFormed("PIN", pin);
        eq("PIN 1234 (cmd 0x20 + ASCII)", h(java.util.Arrays.copyOfRange(pin, 0, 7)), "20 31 32 33 34 00 00");
        eq("PIN mais longo que 6 e' truncado", h(java.util.Arrays.copyOfRange(TripperProtocol.buildPinPacket("12345678"), 0, 8)),
                "20 31 32 33 34 35 36 00");

        // ---------- hora (SET_TIME) ----------
        eq("REAL SET_TIME 13:55 em 12h (PM: sem bit extra)", h(TripperProtocol.buildSetTimePacket(13, 55, true)),
                "50 01 37 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 51 A8");
        eq("REAL SET_TIME 13:54 em 12h", h(TripperProtocol.buildSetTimePacket(13, 54, true)),
                "50 01 36 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 F9 8C");
        yes("SET_TIME 24h: hora crua", (TripperProtocol.buildSetTimePacket(13, 55, false)[1] & 0xFF) == 13);
        yes("SET_TIME 12h AM soma 0x40 (09:05 -> 0x49)", (TripperProtocol.buildSetTimePacket(9, 5, true)[1] & 0xFF) == 0x49);
        yes("SET_TIME 12h meia-noite -> 12 AM (0x4C)", (TripperProtocol.buildSetTimePacket(0, 0, true)[1] & 0xFF) == 0x4C);
        yes("SET_TIME 12h meio-dia -> 12 PM (0x0C)", (TripperProtocol.buildSetTimePacket(12, 0, true)[1] & 0xFF) == 0x0C);
        wellFormed("SET_TIME", TripperProtocol.buildSetTimePacket(13, 55, true));

        // ---------- bussola ----------
        eq("REAL bussola SE (0x70), noite", h(TripperProtocol.buildCompassPacket((byte) 0x70, true)),
                "10 11 41 00 00 00 01 FF 00 00 00 FF FF FF 70 00 00 00 CE D1");
        eq("REAL bussola SW (0x80), noite", h(TripperProtocol.buildCompassPacket((byte) 0x80, true)),
                "10 11 41 00 00 00 01 FF 00 00 00 FF FF FF 80 00 00 00 51 9C");
        eq("REAL bussola NE (0x50), noite", h(TripperProtocol.buildCompassPacket((byte) 0x50, true)),
                "10 11 41 00 00 00 01 FF 00 00 00 FF FF FF 50 00 00 00 F9 9F");
        eq("REAL bussola N (0x10), noite", h(TripperProtocol.buildCompassPacket((byte) 0x10, true)),
                "10 11 41 00 00 00 01 FF 00 00 00 FF FF FF 10 00 00 00 97 03");
        eq("LOCK bussola N, dia", h(TripperProtocol.buildCompassPacket((byte) 0x10, false)),
                "10 11 41 00 00 00 00 FF 00 00 00 FF FF FF 10 00 00 00 94 76");
        // rumos vistos no log (REAL) e as bordas dos setores de 45 graus
        yes("REAL rumo 141 -> SE 0x70", TripperProtocol.bearingToDirection(141f) == (byte) 0x70);
        yes("REAL rumo 235 -> SW 0x80", TripperProtocol.bearingToDirection(235f) == (byte) 0x80);
        yes("REAL rumo 51 -> NE 0x50", TripperProtocol.bearingToDirection(51f) == (byte) 0x50);
        yes("REAL rumo 10 -> N 0x10", TripperProtocol.bearingToDirection(10f) == (byte) 0x10);
        yes("rumo 22.4 -> N", TripperProtocol.bearingToDirection(22.4f) == (byte) 0x10);
        yes("rumo 22.6 -> NE", TripperProtocol.bearingToDirection(22.6f) == (byte) 0x50);
        yes("rumo 359.9 -> N (volta do circulo)", TripperProtocol.bearingToDirection(359.9f) == (byte) 0x10);
        yes("rumo 90 -> E 0x20", TripperProtocol.bearingToDirection(90f) == (byte) 0x20);
        yes("rumo 180 -> S 0x40", TripperProtocol.bearingToDirection(180f) == (byte) 0x40);
        yes("rumo 270 -> W 0x30", TripperProtocol.bearingToDirection(270f) == (byte) 0x30);
        yes("rumo 315 -> NW 0x60", TripperProtocol.bearingToDirection(315f) == (byte) 0x60);

        // ---------- codificacao da distancia ----------
        eq("encodeDistance(0)", h(TripperProtocol.encodeDistance(0)), "00 00 01");
        eq("encodeDistance(68)", h(TripperProtocol.encodeDistance(68)), "00 44 01");
        eq("encodeDistance(260)", h(TripperProtocol.encodeDistance(260)), "01 04 01");
        eq("encodeDistance(999) ainda em metros", h(TripperProtocol.encodeDistance(999)), "03 E7 01");
        eq("encodeDistance(1000) vira km*10 (unidade 2)", h(TripperProtocol.encodeDistance(1000)), "00 0A 02");
        eq("encodeDistance(2942) -> 29 (2,9 km)", h(TripperProtocol.encodeDistance(2942)), "00 1D 02");
        eq("encodeDistance(22900) -> 229 (22,9 km)", h(TripperProtocol.encodeDistance(22900)), "00 E5 02");
        eq("encodeDistance limita em 0xFFFF", h(TripperProtocol.encodeDistance(99_999_999)), "FF FF 02");

        // ---------- pacote de navegacao (todos REAL, do log de um trajeto) ----------
        int[] tot2542 = {0x00, 0x19, 0x02};
        eq("REAL nav: virar a esquerda, 167 m, total 2,5 km, noite",
                h(nav(0x14, 167, 0xFF, -1, tot2542, 0x01)),
                "10 11 14 00 A7 01 01 FF FF FF 41 00 19 02 00 00 00 00 19 1A");
        eq("REAL nav: sem distancia ainda (-1 vira 0)",
                h(nav(0x14, 0, 0xFF, -1, tot2542, 0x01)),
                "10 11 14 00 00 01 01 FF FF FF 41 00 19 02 00 00 00 00 A3 AD");
        eq("REAL nav: rodape em tempo restante (8 min)",
                h(nav(0x14, 166, 0xFF, -1, new int[]{0x00, 0x08, 0x00}, 0x01)),
                "10 11 14 00 A6 01 01 FF FF FF 41 00 08 00 00 00 00 00 79 DC");
        eq("REAL nav: rodape em distancia total (2942 m)",
                h(nav(0x14, 166, 0xFF, -1, new int[]{0x00, 0x1D, 0x02}, 0x01)),
                "10 11 14 00 A6 01 01 FF FF FF 41 00 1D 02 00 00 00 00 64 DA");
        eq("REAL nav: chegando ao destino, a 68 m (intensidade 0x21), rodape 68 m",
                h(nav(0x00, 68, 0xFF, -1, new int[]{0x00, 0x44, 0x01}, 0x21)),
                "10 11 00 00 44 01 21 FF FF FF 41 00 44 01 00 00 00 00 68 CA");
        eq("REAL nav: hora de chegada em 24h (17:28)",
                h(nav(0x00, 68, 0xFF, -1, new int[]{0x11, 0x1C, 0x00}, 0x21)),
                "10 11 00 00 44 01 21 FF FF FF 41 11 1C 00 00 00 00 00 30 57");
        eq("REAL nav: hora de chegada em 12h (5:28 PM -> 0x85)",
                h(nav(0x00, 68, 0xFF, -1, new int[]{0x85, 0x1C, 0x00}, 0x21)),
                "10 11 00 00 44 01 21 FF FF FF 41 85 1C 00 00 00 00 00 6D 13");
        eq("REAL nav: radar modo compativel (seta 0x3C, distancia do radar no rodape)",
                h(nav(0x14, 174, 0x3C, -1, new int[]{0x01, 0x04, 0x01}, 0x01)),
                "10 11 14 00 AE 01 01 3C FF FF 41 01 04 01 00 00 00 00 3E E1");
        eq("REAL nav: radar (layout antigo, seta 0x45, rodape 250 m)",
                h(nav(0x14, 167, 0x45, -1, new int[]{0x00, 0xFA, 0x01}, 0x01)),
                "10 11 14 00 A7 01 01 45 FF FF 41 00 FA 01 00 00 00 00 C6 C0");
        // modo experimental do radar: distancia nos bytes [8-9] (hipotese, ainda nao vista no Tripper)
        byte[] exp = nav(0x14, 174, 0x3C, 260, tot2542, 0x01);
        yes("LOCK radar experimental: [8-9] = distancia (260 = 01 04)", (exp[8] & 0xFF) == 0x01 && (exp[9] & 0xFF) == 0x04);
        byte[] expNone = nav(0x14, 174, 0x3C, -1, tot2542, 0x01);
        yes("LOCK radar experimental sem distancia: [8-9] = FF FF", (expNone[8] & 0xFF) == 0xFF && (expNone[9] & 0xFF) == 0xFF);
        byte[] expBig = nav(0x14, 174, 0x3C, 70_000, tot2542, 0x01);
        yes("LOCK radar experimental limita em 0xFFFE", (expBig[8] & 0xFF) == 0xFF && (expBig[9] & 0xFF) == 0xFE);
        wellFormed("nav", exp);

        // ---------- telas especiais ----------
        eq("EXT tela ociosa 0x3C (CRC 10 50)",
                h(TripperProtocol.withCrc(TripperProtocol.hex("10 11 3C 00 00 04 40 15 00 00 41 00 04 03 00 00 00 00"))),
                "10 11 3C 00 00 04 40 15 00 00 41 00 04 03 00 00 00 00 10 50");
        eq("LOCK rota iniciada (0x45 grande, distancia 0, rodape FF)",
                h(nav(TripperProtocol.MANEUVER_ROUTE_STARTED, 0, TripperProtocol.NEXT_NONE, -1, new int[]{0xFF, 0xFF, 0xFF}, 0)),
                "10 11 45 00 00 01 00 FF FF FF 41 FF FF FF 00 00 00 00 1A 5A");
        eq("LOCK recalculando (tela 0x1C, dia)", h(TripperProtocol.buildScreenPacket(TripperProtocol.SCREEN_LOADING, false)),
                "10 11 1C 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0B EA");
        yes("LOCK recalculando a noite: byte [6] = 1", (TripperProtocol.buildScreenPacket(TripperProtocol.SCREEN_LOADING, true)[6] & 0xFF) == 1);
        eq("LOCK icone de ligacao (CMD 0x40, sub 0x05)", h(TripperProtocol.buildCallIconPacket()),
                "40 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ED 5E");
        wellFormed("rota iniciada", nav(0x45, 0, 0xFF, -1, new int[]{0xFF, 0xFF, 0xFF}, 0));
        wellFormed("recalculando", TripperProtocol.buildScreenPacket(0x1C, false));
        wellFormed("icone de ligacao", TripperProtocol.buildCallIconPacket());

        // ---------- intensidade por distancia (byte [6]) ----------
        int[][] faixas = {{0, 0x50}, {10, 0x50}, {11, 0x40}, {20, 0x40}, {21, 0x30}, {45, 0x30}, {46, 0x20},
                {70, 0x20}, {71, 0x10}, {95, 0x10}, {96, 0x00}, {500, 0x00}, {Integer.MAX_VALUE, 0x00}};
        for (int[] f : faixas) {
            yes("intensidade dia " + f[0] + " m -> 0x" + Integer.toHexString(f[1]),
                    TripperProtocol.distanceIntensity(f[0], false) == f[1]);
        }
        yes("REAL intensidade a 68 m de noite = 0x21", TripperProtocol.distanceIntensity(68, true) == 0x21);
        yes("REAL intensidade a 166 m de noite = 0x01", TripperProtocol.distanceIntensity(166, true) == 0x01);

        // ---------- rodape (distancia total / tempo restante / hora de chegada) ----------
        eq("REAL rodape total 2942 m", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_TOTAL_DISTANCE, 5, 2942, false)), "00 1D 02");
        eq("rodape total desconhecido -> FF FF FF", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_TOTAL_DISTANCE, 5, -1, false)), "FF FF FF");
        eq("REAL rodape tempo restante 8 min", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_TIME_REMAINING, 8, 0, false)), "00 08 00");
        eq("rodape tempo restante 125 min = 2 h 05", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_TIME_REMAINING, 125, 0, false)), "02 05 00");
        eq("rodape tempo restante sem dado -> FF FF FF", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_TIME_REMAINING, 0, 0, false)), "FF FF FF");
        long t1700 = at(17, 0);
        eq("REAL rodape chegada 24h (17:00 + 28 min)", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_ARRIVAL_TIME, 28, 0, false, t1700)), "11 1C 00");
        eq("REAL rodape chegada 12h PM (5:28 PM)", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_ARRIVAL_TIME, 28, 0, true, t1700)), "85 1C 00");
        eq("rodape chegada 12h AM (09:05 + 10 min = 9:15 AM)", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_ARRIVAL_TIME, 10, 0, true, at(9, 5))), "49 0F 00");
        eq("rodape chegada passando da meia-noite, 24h (23:50 + 20 min)", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_ARRIVAL_TIME, 20, 0, false, at(23, 50))), "00 0A 00");
        eq("rodape chegada passando da meia-noite, 12h (0:10 AM)", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_ARRIVAL_TIME, 20, 0, true, at(23, 50))), "40 0A 00");
        eq("rodape chegada sem dado -> FF FF FF", h(TripperProtocol.buildBottomInfoBytes(TripperProtocol.BOTTOM_ARRIVAL_TIME, 0, 0, false, t1700)), "FF FF FF");

        // ---------- traducao das manobras do Waze ----------
        String[][] man = {
                {"TURN_LEFT", "14"}, {"PREPARE_TURN_LEFT", "14"}, {"TURN_RIGHT", "15"}, {"PREPARE_TURN_RIGHT", "15"},
                {"SHARP_LEFT", "16"}, {"SHARP_RIGHT", "17"}, {"SLIGHT_LEFT", "18"}, {"SLIGHT_RIGHT", "19"},
                {"KEEP_LEFT", "28"}, {"KEEP_RIGHT", "27"}, {"CONTINUE_STRAIGHT", "09"}, {"ENTER_HOV_LANE", "09"},
                {"U_TURN", "3D"}, {"EXIT_LEFT", "2E"}, {"PREPARE_EXIT_LEFT", "2E"}, {"EXIT_RIGHT", "2D"},
                {"PREPARE_EXIT_RIGHT", "2D"}, {"APPROACHING_DESTINATION", "00"}, {"LAST_DIRECTION", "00"},
                {"APPROACHING_STOP_POINT", "00"}, {"WAYPOINT_DELAY", "00"}};
        for (String[] m : man) {
            yes("manobra " + m[0] + " -> 0x" + m[1], TripperProtocol.maneuverByte(m[0], -1) == Integer.parseInt(m[1], 16));
        }
        yes("rotatoria, saida 1 -> 0x32", TripperProtocol.maneuverByte("ROUNDABOUT_ENTER", 1) == 0x32);
        yes("rotatoria, saida 2 -> 0x33", TripperProtocol.maneuverByte("ROUNDABOUT_ENTER", 2) == 0x33);
        yes("rotatoria, saida 9 -> 0x3A", TripperProtocol.maneuverByte("ROUNDABOUT_ENTER", 9) == 0x3A);
        yes("rotatoria, saida 12 limita em 9 -> 0x3A", TripperProtocol.maneuverByte("ROUNDABOUT_ENTER", 12) == 0x3A);
        yes("rotatoria, sem saida (0 ou -1) -> saida 1", TripperProtocol.maneuverByte("ROUNDABOUT_EXIT", 0) == 0x32
                && TripperProtocol.maneuverByte("ROUNDABOUT_EXIT", -1) == 0x32);
        yes("NAV_INSTR_NONE sem traducao (-1)", TripperProtocol.maneuverByte("NAV_INSTR_NONE", -1) == -1);
        yes("UNRECOGNIZED sem traducao (-1)", TripperProtocol.maneuverByte("UNRECOGNIZED", -1) == -1);
        yes("null sem traducao (-1)", TripperProtocol.maneuverByte(null, -1) == -1);

        // todo tipo de manobra que o Waze 5.23.0.2 declara (Instruction$Type) tem traducao,
        // exceto os dois que nao sao manobras
        String[] wazeTypes = {"APPROACHING_DESTINATION", "APPROACHING_STOP_POINT", "CONTINUE_STRAIGHT", "ENTER_HOV_LANE",
                "EXIT_LEFT", "EXIT_RIGHT", "KEEP_LEFT", "KEEP_RIGHT", "LAST_DIRECTION", "NAV_INSTR_NONE",
                "PREPARE_EXIT_LEFT", "PREPARE_EXIT_RIGHT", "PREPARE_TURN_LEFT", "PREPARE_TURN_RIGHT", "ROUNDABOUT_ENTER",
                "ROUNDABOUT_EXIT", "ROUNDABOUT_EXIT_LEFT", "ROUNDABOUT_EXIT_RIGHT", "ROUNDABOUT_EXIT_STRAIGHT",
                "ROUNDABOUT_EXIT_U", "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT", "ROUNDABOUT_STRAIGHT", "ROUNDABOUT_U",
                "SHARP_LEFT", "SHARP_RIGHT", "SLIGHT_LEFT", "SLIGHT_RIGHT", "TURN_LEFT", "TURN_RIGHT", "UNRECOGNIZED",
                "U_TURN", "WAYPOINT_DELAY"};
        int semTraducao = 0;
        StringBuilder faltam = new StringBuilder();
        for (String t : wazeTypes) {
            boolean naoManobra = t.equals("NAV_INSTR_NONE") || t.equals("UNRECOGNIZED");
            if (!naoManobra && TripperProtocol.maneuverByte(t, 1) < 0) {
                semTraducao++;
                faltam.append(' ').append(t);
            }
        }
        yes("cobertura: as " + wazeTypes.length + " manobras do Waze tem traducao (exceto NONE/UNRECOGNIZED)"
                + (faltam.length() > 0 ? " - faltam:" + faltam : ""), semTraducao == 0);

        // seta pequena (proxima manobra)
        yes("proxima: KEEP_LEFT/EXIT_LEFT/PREPARE_EXIT_LEFT -> 0x2B", TripperProtocol.nextManeuverByte("KEEP_LEFT", -1) == 0x2B
                && TripperProtocol.nextManeuverByte("EXIT_LEFT", -1) == 0x2B && TripperProtocol.nextManeuverByte("PREPARE_EXIT_LEFT", -1) == 0x2B);
        yes("proxima: KEEP_RIGHT/EXIT_RIGHT/PREPARE_EXIT_RIGHT -> 0x2C", TripperProtocol.nextManeuverByte("KEEP_RIGHT", -1) == 0x2C
                && TripperProtocol.nextManeuverByte("EXIT_RIGHT", -1) == 0x2C && TripperProtocol.nextManeuverByte("PREPARE_EXIT_RIGHT", -1) == 0x2C);
        yes("proxima: LAST_DIRECTION -> sem dado (FF)", TripperProtocol.nextManeuverByte("LAST_DIRECTION", -1) == 0xFF);
        yes("proxima: null e NAV_INSTR_NONE -> sem dado (FF)", TripperProtocol.nextManeuverByte(null, -1) == 0xFF
                && TripperProtocol.nextManeuverByte("NAV_INSTR_NONE", -1) == 0xFF);
        yes("proxima: TURN_LEFT -> 0x14", TripperProtocol.nextManeuverByte("TURN_LEFT", -1) == 0x14);
        yes("proxima: rotatoria saida 3 -> 0x34", TripperProtocol.nextManeuverByte("ROUNDABOUT_ENTER", 3) == 0x34);
        yes("constantes: radar = 0x3C, sem dado = 0xFF", TripperProtocol.NEXT_RADAR == 0x3C && TripperProtocol.NEXT_NONE == 0xFF);

        System.out.println(failures == 0
                ? "\nALL PACKET CHECKS PASSED (" + checks + ")"
                : "\n" + failures + " OF " + checks + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Hoje as hh:mm no fuso local, em milissegundos. */
    static long at(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.SEPTEMBER, 20, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static boolean crcAgrees() {
        byte[] b = new byte[18];
        for (int seed = 0; seed < 256; seed++) {
            for (int i = 0; i < b.length; i++) b[i] = (byte) (seed * 31 + i * 7);
            if (TripperProtocol.crc16(b) != refCrc(b, 18)) return false;
        }
        return true;
    }
}
