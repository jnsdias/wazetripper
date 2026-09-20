#!/usr/bin/env python3
"""Simula o trajeto inteiro de uma rota do Waze via adb (localizacao de teste).

Le os pontos da rota que o NavHooks.onRouteGeometry() registra no logcat (linhas
"GEO rota: N pontos" seguidas de "GEO <bloco> lat,lon;lat,lon;..." em microgrados), interpola
a posicao a cada segundo numa velocidade constante e injeta cada uma como localizacao do
provedor de teste "gps" (cmd location providers set-test-provider-location).

Uso:
  simulate_route.py --log LOGCAT.txt [--speed-kmh 40] [--start-index 0] [--adb PATH]
  simulate_route.py --cleanup            # remove o provedor de teste e restaura o app-op

Requisitos (uma vez por aparelho): o shell precisa poder simular localizacao:
  adb shell appops set --uid 2000 MOCK_LOCATION allow
O provedor some quando o app e' reinstalado; este script o recria a cada execucao.
Nao e' ferramenta de build: roda no host e so' fala com o adb.
"""
import argparse
import math
import os
import re
import subprocess
import sys
import time

DEFAULT_ADB = os.environ.get("ADB", "adb")  # ou --adb PATH (no WSL: o adb.exe do Windows)


def adb(adb_path, *args, check=False):
    r = subprocess.run([adb_path, "shell", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit("adb falhou: " + " ".join(args) + "\n" + r.stdout + r.stderr)
    return r


def load_route(path):
    """Pontos (lat, lon em graus) do ULTIMO bloco 'GEO rota' do log."""
    blocks, cur = [], None
    for line in open(path, errors="replace"):
        if "GEO rota:" in line:
            cur = []
            blocks.append(cur)
            continue
        m = re.search(r"GEO (\d+) ([-\d,;]+)", line)
        if m and cur is not None:
            cur.append(m.group(2))
    if not blocks or not blocks[-1]:
        sys.exit("nenhuma geometria 'GEO' encontrada em " + path)
    pts = []
    for txt in ";".join(blocks[-1]).split(";"):
        if "," in txt:
            la, lo = txt.split(",")
            pts.append((int(la) / 1e6, int(lo) / 1e6))
    return pts


def dist_m(a, b):
    """Distancia aproximada (equirretangular) em metros; suficiente pra trechos curtos."""
    lat = math.radians((a[0] + b[0]) / 2)
    dx = math.radians(b[1] - a[1]) * math.cos(lat) * 6371000
    dy = math.radians(b[0] - a[0]) * 6371000
    return math.hypot(dx, dy)


def resample(pts, step_m):
    """Pontos igualmente espacados (step_m) ao longo da polilinha."""
    out = [pts[0]]
    carry = 0.0
    for a, b in zip(pts, pts[1:]):
        seg = dist_m(a, b)
        if seg == 0:
            continue
        pos = step_m - carry
        while pos <= seg:
            f = pos / seg
            out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
            pos += step_m
        carry = seg - (pos - step_m)
    if out[-1] != pts[-1]:
        out.append(pts[-1])
    return out


def cleanup(adb_path):
    print(adb(adb_path, "cmd location providers remove-test-provider gps").stdout.strip() or "provedor de teste removido")
    print(adb(adb_path, "appops reset --uid 2000").stdout.strip() or "app-ops do shell restaurados")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log")
    ap.add_argument("--speed-kmh", type=float, default=40.0)
    ap.add_argument("--start-index", type=int, default=0, help="comeca a partir deste ponto da rota")
    ap.add_argument("--adb", default=DEFAULT_ADB)
    ap.add_argument("--cleanup", action="store_true")
    a = ap.parse_args()

    if a.cleanup:
        cleanup(a.adb)
        return
    if not a.log:
        sys.exit("--log e' obrigatorio (ou use --cleanup)")

    pts = load_route(a.log)[a.start_index:]
    step = a.speed_kmh / 3.6  # metros por segundo = metros por passo (1 Hz)
    path = resample(pts, step)
    total = sum(dist_m(x, y) for x, y in zip(pts, pts[1:]))
    print(f"rota: {len(pts)} pontos, {total:.0f} m -> {len(path)} passos a {a.speed_kmh:.0f} km/h "
          f"(~{len(path)} s)")

    def ensure_provider():
        adb(a.adb, "cmd location providers add-test-provider gps --supportsAltitude --supportsSpeed "
                   "--supportsBearing --accuracy 1")
        adb(a.adb, "cmd location providers set-test-provider-enabled gps true", check=True)

    ensure_provider()
    recreated = 0
    for i, (lat, lon) in enumerate(path):
        cmd = (f"cmd location providers set-test-provider-location gps "
               f"--location {lat:.6f},{lon:.6f} --accuracy 5")
        r = adb(a.adb, cmd)
        if "Exception" in r.stdout + r.stderr:
            # O provedor de teste as vezes some no meio; recria e tenta de novo (1 vez por passo).
            recreated += 1
            ensure_provider()
            r = adb(a.adb, cmd)
            if "Exception" in r.stdout + r.stderr:
                sys.exit(f"passo {i}: falha ao injetar posicao mesmo apos recriar o provedor:\n{r.stdout}{r.stderr}")
        if i % 10 == 0:
            print(f"  passo {i}/{len(path)}  {lat:.6f},{lon:.6f}")
        time.sleep(1.0)
    print(f"fim da rota simulada ({recreated} recriacoes do provedor). A posicao fica no destino; use --cleanup pra restaurar.")


if __name__ == "__main__":
    main()
