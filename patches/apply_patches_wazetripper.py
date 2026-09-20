#!/usr/bin/env python3
"""Patcher do WazeTripper - injeta os hooks (startup + navegacao) num decompile apktool limpo do
Waze 5.23.0.2 (build/base_apktool). Mecanismo no estilo de apply_patches.py do wazeology
(ver NOTICE.md).

Hooks: o de startup (botao flutuante + ponte BLE) e os de navegacao do Waze (manobra atual/proxima,
distancias, ETA, estado da navegacao, radares e alertas). Ver docs/DEVELOPMENT.md.

Nenhuma mudanca de manifest e' necessaria: o Waze ja declara BLUETOOTH_CONNECT/BLUETOOTH_SCAN,
que e' tudo que o link duplo (GATT client + GATT server local, sem advertising) precisa.

Usage: apply_patches_wazetripper.py <decompiled_tree>   (default: build/base_apktool)
"""
import os
import sys

APP_SMALI = "smali_classes5/com/waze/MainActivity.smali"

# NOTA (ver docs/DEVELOPMENT.md): o alvo e a posicao do hook foram descobertos em 3 passos.
#
# 1. Activity: FreeMapAppActivity e' uma tela de bootstrap transitoria, substituida quase de
#    imediato pela MainActivity - views penduradas nela morrem junto. Alvo certo: MainActivity.
# 2. Posicao "after_locals" (inicio do metodo) quebra: TripperOverlay.attach() chama
#    findViewById(android.R.id.content), forcando a instalacao da DecorView antes do
#    requestWindowFeature() do super.onCreate() -> AndroidRuntimeException ("requestFeature() must
#    be called before adding content"). Por isso o hook entra logo APOS o invoke-super onCreate.
# 3. Posicao "before_last_return" tambem NAO serve: o registrador de p0 e' reaproveitado mais
#    adiante no metodo, e o verificador ART rejeita a classe (VerifyError: "register vN has type
#    Reference java.lang.Object but expected Reference: android.app.Activity").
#
# Posicao final: "after_super_onCreate" (registrador de p0 ainda intacto). O onCreate() do proprio
# Waze ainda chama setContentView() depois do hook, o que apagaria qualquer view adicionada antes;
# por isso o trabalho de UI e' adiado no lado Java, via Handler.post() dentro de
# TripperOverlay.attach().
#
# Os hooks de navegacao entram no INICIO do metodo ("after_locals"), sem depender do registrador
# de p0 nem de before_last_return: quando precisam de dados do DistanceUpdate, leem direto do
# parametro (p1). Todos os metodos alvo tem .locals suficiente pros registradores usados aqui.

NAV_SMALI = "smali_classes6/com/waze/navigate/NavigationInfoNativeManager.smali"
ALERT_SMALI = "smali_classes5/com/waze/alerters/f.smali"
ALERT_MGR_SMALI = "smali_classes5/com/waze/alerters/AlerterNativeManager.smali"

# Trecho comum de onCurrentInstructionChanged/onNextInstructionChanged: resolve o nome do
# Instruction$Type a partir do codigo cru (p1), igual ao wazeology; "UNKNOWN" se nao mapear.
_INSTRUCTION_NAME = """    invoke-static {p1}, Lcom/waze/jni/protos/navigate/Instruction$Type;->forNumber(I)Lcom/waze/jni/protos/navigate/Instruction$Type;
    move-result-object v0
    if-eqz v0, :wt_unk
    invoke-virtual {v0}, Ljava/lang/Enum;->name()Ljava/lang/String;
    move-result-object v0
    goto :wt_named
    :wt_unk
    const-string v0, "UNKNOWN"
    :wt_named
"""

HOOKS = [
    {
        "file": APP_SMALI,
        "method": "onCreate(Landroid/os/Bundle;)V",
        "position": "after_super_onCreate",
        "marker": "Lcom/waze/wazetripper/TripperOverlay;->attach",
        "code": """    # --- wazetripper hook: botao flutuante + ponte BLE ---
    invoke-static {p0}, Lcom/waze/wazetripper/TripperOverlay;->attach(Landroid/app/Activity;)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onCurrentInstructionChanged(I)V",
        "position": "after_locals",
        "marker": "NavHooks;->onCurrent(",
        "code": "    # --- wazetripper hook: manobra atual ---\n" + _INSTRUCTION_NAME +
                "    invoke-static {p1, v0}, Lcom/waze/wazetripper/NavHooks;->onCurrent(ILjava/lang/String;)V\n"
                "    # --- end hook ---\n",
    },
    {
        "file": NAV_SMALI,
        "method": "onNextInstructionChanged(I)V",
        "position": "after_locals",
        "marker": "NavHooks;->onNext(",
        "code": "    # --- wazetripper hook: proxima manobra ---\n" + _INSTRUCTION_NAME +
                "    invoke-static {p1, v0}, Lcom/waze/wazetripper/NavHooks;->onNext(ILjava/lang/String;)V\n"
                "    # --- end hook ---\n",
    },
    {
        "file": NAV_SMALI,
        "method": "onExitNumberChanged(I)V",
        "position": "after_locals",
        "marker": "NavHooks;->onExit(",
        "code": """    # --- wazetripper hook: saida atual ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onExit(I)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onNextExitNumberChanged(I)V",
        "position": "after_locals",
        "marker": "NavHooks;->onNextExit(",
        "code": """    # --- wazetripper hook: proxima saida ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onNextExit(I)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onCurrentInstructionDistanceChanged(Lcom/waze/jni/protos/navigate/DistanceUpdate;)V",
        "position": "after_locals",
        "marker": "NavHooks;->onDistance(",
        "code": """    # --- wazetripper hook: distancia ate a manobra ---
    invoke-virtual {p1}, Lcom/waze/jni/protos/navigate/DistanceUpdate;->getRawMeters()I
    move-result v0
    invoke-virtual {p1}, Lcom/waze/jni/protos/navigate/DistanceUpdate;->getValueString()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {p1}, Lcom/waze/jni/protos/navigate/DistanceUpdate;->getUnitString()Ljava/lang/String;
    move-result-object v2
    invoke-static {v0, v1, v2}, Lcom/waze/wazetripper/NavHooks;->onDistance(ILjava/lang/String;Ljava/lang/String;)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onEtaDistanceChanged(Lcom/waze/jni/protos/navigate/DistanceUpdate;)V",
        "position": "after_locals",
        "marker": "NavHooks;->onEtaDistance(",
        "code": """    # --- wazetripper hook: distancia total ---
    invoke-virtual {p1}, Lcom/waze/jni/protos/navigate/DistanceUpdate;->getRawMeters()I
    move-result v0
    invoke-static {v0}, Lcom/waze/wazetripper/NavHooks;->onEtaDistance(I)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onEtaMinutesChanged(Ljava/lang/String;Ljava/lang/String;I)V",
        "position": "after_locals",
        "marker": "NavHooks;->onEtaMinutes(",
        "code": """    # --- wazetripper hook: tempo restante (minutos) ---
    invoke-static {p1, p2, p3}, Lcom/waze/wazetripper/NavHooks;->onEtaMinutes(Ljava/lang/String;Ljava/lang/String;I)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onCurrentEtaSecondsChanged(I)V",
        "position": "after_locals",
        "marker": "NavHooks;->onEtaSeconds(",
        "code": """    # --- wazetripper hook: tempo restante (segundos) ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onEtaSeconds(I)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onNavigationStateChanged(ZI)V",
        "position": "after_locals",
        "marker": "NavHooks;->onNavState(",
        "code": """    # --- wazetripper hook: estado da navegacao ---
    invoke-static {p1, p2}, Lcom/waze/wazetripper/NavHooks;->onNavState(ZI)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onRouteGeometryUpdated(Lcom/waze/jni/protos/navigate/PolylineGeometry;)V",
        "position": "after_locals",
        "marker": "NavHooks;->onRouteGeometry(",
        "code": """    # --- wazetripper hook: geometria da rota (diagnostico: simular o trajeto) ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onRouteGeometry(Ljava/lang/Object;)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onEnforcementZoneUpdate(I)V",
        "position": "after_locals",
        "marker": "NavHooks;->onEnforcementZone(",
        "code": """    # --- wazetripper hook: radar: zona de fiscalizacao ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onEnforcementZone(I)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onEnforcementZoneClear()V",
        "position": "after_locals",
        "marker": "NavHooks;->onEnforcementZoneClear(",
        "code": """    # --- wazetripper hook: radar: fim da zona de fiscalizacao ---
    invoke-static {}, Lcom/waze/wazetripper/NavHooks;->onEnforcementZoneClear()V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onAverageSpeedCamZoneUpdate(Lcom/waze/jni/protos/navigate/AverageSpeedCameraZoneUpdate;)V",
        "position": "after_locals",
        "marker": "NavHooks;->onAvgSpeedCam(",
        "code": """    # --- wazetripper hook: radar: velocidade media ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onAvgSpeedCam(Ljava/lang/Object;)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onAverageSpeedCamZoneClear()V",
        "position": "after_locals",
        "marker": "NavHooks;->onAvgSpeedCamClear(",
        "code": """    # --- wazetripper hook: radar: fim da velocidade media ---
    invoke-static {}, Lcom/waze/wazetripper/NavHooks;->onAvgSpeedCamClear()V
    # --- end hook ---
""",
    },
    {
        "file": ALERT_SMALI,
        "method": "onAlertStart([B)V",
        "position": "after_locals",
        "marker": "NavHooks;->onAlertStart(",
        "code": """    # --- wazetripper hook: alerta: inicio ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onAlertStart([B)V
    # --- end hook ---
""",
    },
    {
        "file": ALERT_SMALI,
        "method": "onAlertTimeoutStarted([B)V",
        "position": "after_locals",
        "marker": "NavHooks;->onAlertTimeout(",
        "code": """    # --- wazetripper hook: alerta: timeout ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onAlertTimeout([B)V
    # --- end hook ---
""",
    },
    {
        "file": ALERT_SMALI,
        "method": "onAlertEnd([B)V",
        "position": "after_locals",
        "marker": "NavHooks;->onAlertEnd(",
        "code": """    # --- wazetripper hook: alerta: fim ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onAlertEnd([B)V
    # --- end hook ---
""",
    },
    {
        "file": ALERT_MGR_SMALI,
        "method": "updateAlertersRepository(Lcom/waze/jni/protos/alerters/NativeAlertRepositoryUpdate;)V",
        "position": "after_locals",
        "marker": "NavHooks;->onAlertsUpdate(",
        "code": """    # --- wazetripper hook: alertas completos (tipo, subtipo, distancia, titulo) ---
    invoke-static {p1}, Lcom/waze/wazetripper/NavHooks;->onAlertsUpdate(Ljava/lang/Object;)V
    # --- end hook ---
""",
    },
]


def find_method_range(lines, method_sig):
    start = None
    for i, ln in enumerate(lines):
        s = ln.strip()
        if s.startswith(".method") and s.split()[-1] == method_sig:
            start = i
            break
    if start is None:
        raise SystemExit("method not found: " + method_sig)
    for j in range(start + 1, len(lines)):
        if lines[j].strip() == ".end method":
            return start, j
    raise SystemExit(".end method not found for " + method_sig)


def apply_hook(lines, hook):
    start, end = find_method_range(lines, hook["method"])
    body = "".join(lines[start:end])
    if hook["marker"] in body:
        return lines, False  # already applied
    block = [l + "\n" for l in hook["code"].rstrip("\n").split("\n")]
    if hook["position"] == "after_locals":
        for i in range(start, end):
            s = lines[i].strip()
            if s.startswith(".locals ") or s.startswith(".registers "):
                lines[i + 1:i + 1] = block
                return lines, True
        raise SystemExit("no .locals in " + hook["method"])
    if hook["position"] == "after_super_onCreate":
        for i in range(start, end):
            s = lines[i].strip()
            if s.startswith("invoke-super") and "onCreate(Landroid/os/Bundle;)V" in s:
                lines[i + 1:i + 1] = block
                return lines, True
        raise SystemExit("no invoke-super onCreate(...) in " + hook["method"])
    if hook["position"] == "before_last_return":
        last_return = None
        for i in range(start, end):
            if lines[i].strip() == "return-void":
                last_return = i
        if last_return is None:
            raise SystemExit("no return-void in " + hook["method"])
        lines[last_return:last_return] = block
        return lines, True
    raise SystemExit("bad position " + hook["position"])


def main():
    tree = sys.argv[1] if len(sys.argv) > 1 else "build/base_apktool"
    if not os.path.isdir(tree):
        raise SystemExit("decompiled tree not found: " + tree)
    files = []
    for hook in HOOKS:
        if hook["file"] not in files:
            files.append(hook["file"])
    for rel in files:
        path = os.path.join(tree, rel)
        with open(path) as f:
            lines = f.readlines()
        touched = 0
        for hook in HOOKS:
            if hook["file"] != rel:
                continue
            lines, applied = apply_hook(lines, hook)
            print("  hook {:<38} {}".format(hook["method"].split("(")[0], "applied" if applied else "already present"))
            if applied:
                touched += 1
        if touched:
            with open(path, "w") as f:
                f.writelines(lines)
    print("done. Nenhuma mudanca de manifest necessaria (ver docs/DEVELOPMENT.md).")


if __name__ == "__main__":
    main()
