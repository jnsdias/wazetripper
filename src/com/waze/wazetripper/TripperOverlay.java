package com.waze.wazetripper;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

/**
 * TripperOverlay - botao flutuante sobreposto ao mapa do Waze, com o icone "celular <-> Pod"
 * num circulo que muda de cor com o estado da conexao (vermelho desconectado, verde conectado,
 * laranja em andamento); ao toque abre o TripperPanel (pareamento, status e configuracoes).
 * UI 100% programatica - nenhum layout XML, nenhum recurso novo, so android.* framework. O icone
 * tambem e' desenhado em codigo (Canvas/Path), nao como asset nem drawable, pra nao precisar
 * estender o pipeline de empacotamento (graft.py).
 *
 * Chamado pelo hook em MainActivity.onCreate: TripperOverlay.attach(this). Precisa ser public
 * (classe e metodo) porque o hook injetado via smali fica em com.waze.MainActivity - pacote
 * diferente de com.waze.wazetripper - e invoke-static entre pacotes falha em tempo de execucao
 * (IllegalAccessError) se o alvo nao for public, mesmo funcionando na compilacao.
 */
public final class TripperOverlay {

    private TripperOverlay() {
    }

    private static final String TAG = "WazeTripper";

    private static final int BUTTON_SIZE_DP = 56;
    private static final int MARGIN_DP = 24;
    // 220dp: acima do botao nativo do Waze no canto inferior direito durante a navegacao (o topo dele
    // fica a ~200dp da base em telas de ~890dp de altura; com 150dp o nosso botao ficava em cima dele).
    private static final int BOTTOM_MARGIN_DP = 220;

    public static void attach(Activity activity) {
        // Ver docs/DEVELOPMENT.md (posicao do hook): o hook e' injetado logo apos o
        // super.onCreate() (ponto seguro pro registrador do p0/this - ver nota no
        // apply_patches_wazetripper.py), mas o proprio onCreate() do Waze ainda vai chamar
        // setContentView() DEPOIS disso - e setContentView() remove todos os filhos do
        // container de conteudo antes de inflar o layout novo, apagando qualquer view que a
        // gente tenha adicionado ali antes dele rodar. Por isso a parte que mexe na tela e'
        // adiada com Handler.post(): so' roda depois que a mensagem atual (o onCreate() inteiro,
        // incluindo o setContentView do Waze) terminar de processar.
        Log.e(TAG, "attach() chamado, activity=" + activity.getClass().getName());
        try {
            new Handler(Looper.getMainLooper()).post(() -> attachNow(activity));
        } catch (Throwable t) {
            Log.e(TAG, "attach() falhou ao agendar post()", t);
        }
    }

    private static void attachNow(Activity activity) {
        Log.e(TAG, "attachNow() rodando (pos setContentView), activity=" + activity.getClass().getName());
        try {
            ViewGroup root = activity.findViewById(android.R.id.content);
            Log.e(TAG, "root (android.R.id.content) = " + root);
            if (root == null) {
                Log.e(TAG, "root nulo, abortando");
                return;
            }

            float density = activity.getResources().getDisplayMetrics().density;
            int sizePx = (int) (BUTTON_SIZE_DP * density);
            int marginPx = (int) (MARGIN_DP * density);
            Log.e(TAG, "density=" + density + " sizePx=" + sizePx + " marginPx=" + marginPx);

            TripperBridge bridge = TripperBridge.get(activity.getApplicationContext());
            Log.e(TAG, "TripperBridge obtido: " + bridge);

            ImageButton button = new ImageButton(activity);
            button.setBackground(circleBackground(stateColor(bridge.getState())));
            button.setImageDrawable(linkIcon((int) (sizePx * 0.74f)));
            button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            button.setContentDescription("WazeTripper");
            button.setElevation(8f * density);
            Log.e(TAG, "button criado");

            int bottomMarginPx = (int) (BOTTOM_MARGIN_DP * density);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.rightMargin = marginPx;
            lp.bottomMargin = bottomMarginPx; // acima do icone que o Waze ja tem nesse canto
            root.addView(button, lp);
            Log.e(TAG, "button adicionado ao root; root.getChildCount() agora = " + root.getChildCount());

            button.setOnClickListener(v -> TripperPanel.show(activity, bridge));
            // Observador permanente (o listener do painel some quando ele fecha): o botao muda de
            // cor com o estado mesmo sem o painel aberto.
            bridge.setStateObserver(state -> button.setBackground(circleBackground(stateColor(state))));
            bridge.startAutoOnce(); // procura o Pod conhecido ao abrir o Waze (no-op sem pareamento / desligado)
            Log.e(TAG, "attachNow() concluido com sucesso");
        } catch (Throwable t) {
            Log.e(TAG, "attachNow() falhou", t);
        }
    }

    // ---------- icone e cores (usados tambem pelo cabecalho do TripperPanel) -----------------

    private static final int COLOR_DISCONNECTED = 0xFFE53935; // vermelho
    private static final int COLOR_CONNECTED = 0xFF43A047;    // verde
    private static final int COLOR_PROGRESS = 0xFFFFA000;     // laranja: procurando/conectando/PIN

    static int stateColor(TripperBridge.State state) {
        switch (state) {
            case CONNECTED:
                return COLOR_CONNECTED;
            case DISCONNECTED:
                return COLOR_DISCONNECTED;
            default:
                return COLOR_PROGRESS;
        }
    }

    static Drawable circleBackground(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /**
     * Icone "celular <-> Pod": celular a esquerda, tres pontos de ligacao e o mostrador redondo do
     * Pod (com seta de navegacao) a direita. Desenhado em codigo numa caixa de 100x100 unidades,
     * escalada pro tamanho pedido; sempre branco (a cor de estado vem do circulo de fundo).
     */
    static Drawable linkIcon(int sizePx) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path arrow = new Path();

            {
                // seta de navegacao centrada em (77, 50), raio 11
                arrow.moveTo(77f, 39f);
                arrow.lineTo(85.25f, 59.9f);
                arrow.lineTo(77f, 54.95f);
                arrow.lineTo(68.75f, 59.9f);
                arrow.close();
            }

            @Override
            public void draw(Canvas canvas) {
                android.graphics.Rect b = getBounds();
                float s = Math.min(b.width(), b.height()) / 100f;
                canvas.save();
                canvas.translate(b.left, b.top);
                canvas.scale(s, s);

                paint.setColor(Color.WHITE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(6.5f);

                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(7f, 22f, 31f, 78f, 8f, 8f, paint); // celular
                canvas.drawCircle(77f, 50f, 19f, paint);                // mostrador do Pod

                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(19f, 68f, 3f, paint);                 // botao do celular
                for (int x = 38; x <= 50; x += 6) {
                    canvas.drawCircle(x, 50f, 3f, paint);               // pontos de ligacao
                }
                canvas.drawPath(arrow, paint);

                canvas.restore();
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }

            @Override
            public int getIntrinsicWidth() {
                return sizePx;
            }

            @Override
            public int getIntrinsicHeight() {
                return sizePx;
            }
        };
    }
}
