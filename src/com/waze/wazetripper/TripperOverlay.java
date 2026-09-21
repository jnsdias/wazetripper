package com.waze.wazetripper;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
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
 * TripperOverlay - botao flutuante sobreposto ao mapa do Waze, com o aro do Tripper e um simbolo
 * de conexao que muda de cor com o estado (vermelho desconectado, verde conectado, laranja em
 * andamento); ao toque abre o TripperPanel (pareamento, status e configuracoes).
 * UI 100% programatica - nenhum layout XML, nenhum recurso novo, so android.* framework. O aro e'
 * uma imagem embutida em base64 (TripperIconData) e o simbolo e' desenhado em codigo, nao como
 * asset nem drawable, pra nao precisar estender o pipeline de empacotamento (graft.py).
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
            // Fundo escuro e' so' pra a sombra (elevation) sair redonda; o icone cobre tudo.
            StateIcon icon = new StateIcon(sizePx, stateColor(bridge.getState()));
            button.setBackground(circleBackground(0xFF10131A));
            button.setImageDrawable(icon);
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
            bridge.setStateObserver(state -> icon.setStateColor(stateColor(state)));
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
     * Icone do botao e do cabecalho do painel: o aro do Tripper (imagem embutida em
     * TripperIconData) com o simbolo de conexao desenhado por cima em codigo, na cor do estado
     * (setStateColor). Fica tudo numa caixa de 100x100 unidades, escalada pro tamanho pedido.
     */
    static final class StateIcon extends Drawable {
        private static Bitmap bezel; // decodificado uma vez, compartilhado entre os icones

        private final int sizePx;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private int color;

        StateIcon(int sizePx, int color) {
            this.sizePx = sizePx;
            this.color = color;
            loadBezel();
        }

        void setStateColor(int newColor) {
            if (newColor != color) {
                color = newColor;
                invalidateSelf();
            }
        }

        private static synchronized void loadBezel() {
            if (bezel != null) {
                return;
            }
            try {
                byte[] png = TripperIconData.bezelPng();
                bezel = BitmapFactory.decodeByteArray(png, 0, png.length);
            } catch (Throwable t) {
                Log.e(TAG, "falha ao decodificar o aro do icone; usando circulo escuro", t);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 100f;
            if (bezel != null) {
                canvas.drawBitmap(bezel, null, b, bitmapPaint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF10131A);
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), 50f * s, paint);
            }

            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.scale(s, s);
            paint.setStrokeCap(Paint.Cap.ROUND);

            // Brilho (traco mais largo e translucido) e depois o simbolo, em duas passadas.
            int glow = (color & 0x00FFFFFF) | 0x55000000;
            for (int pass = 0; pass < 2; pass++) {
                float extra = pass == 0 ? 5f : 0f;
                paint.setColor(pass == 0 ? glow : color);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(4.2f + extra);
                for (float r = 9f; r <= 24f; r += 7.5f) {
                    arc.set(50f - r, 60f - r, 50f + r, 60f + r);
                    canvas.drawArc(arc, 225f, 90f, false, paint); // arco de 90 graus pra cima
                }

                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(50f, 60f, 3.6f + extra / 2f, paint);
            }

            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            bitmapPaint.setAlpha(alpha);
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            bitmapPaint.setColorFilter(colorFilter);
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }
    }
}
