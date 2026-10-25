package dev.moksh.minilauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.service.dreams.DreamService;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Screensaver: the same painting as the home screen, dimmed, with a small clock that moves
 * between corners so nothing burns in. Reads the cached art.jpg; no network, no extra download.
 */
public class ArtDream extends DreamService {

    private DreamView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        view = new DreamView(this);
        setContentView(view);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (view != null) view.release();
    }

    static final class DreamView extends View {
        private final Context ctx;
        private Bitmap bmp;
        private String title, sub;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final Date now = new Date();
        private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
        private final SimpleDateFormat timeFmt;

        DreamView(Context c) {
            super(c);
            ctx = c;
            SharedPreferences cfg = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
            timeFmt = new SimpleDateFormat(cfg.getBoolean("h24", true) ? "HH:mm" : "h:mm", Locale.getDefault());
            load();
        }

        private void load() {
            File f = new File(ctx.getCacheDir(), "art.jpg");
            if (!f.exists()) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / s > 2200) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bmp = BitmapFactory.decodeFile(f.getPath(), o);
            try {
                BufferedReader br = new BufferedReader(new FileReader(new File(ctx.getCacheDir(), "art.txt")));
                title = br.readLine();
                sub = br.readLine();
                br.close();
            } catch (Exception ignored) {
            }
        }

        void release() {
            if (bmp != null) {
                bmp.recycle();
                bmp = null;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            if (bmp != null) {
                float sc = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
                int sw = (int) (w / sc), sh = (int) (h / sc);
                int l = (bmp.getWidth() - sw) / 2, t = (bmp.getHeight() - sh) / 2;
                src.set(l, t, l + sw, t + sh);
                dst.set(0, 0, w, h);
                c.drawBitmap(bmp, src, dst, paint);
            } else {
                paint.setShader(new LinearGradient(0, 0, w, h, 0xFF2B2F3A, 0xFF0B0C10, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, paint);
                paint.setShader(null);
            }
            // soft shade along the bottom so the text stays readable; the painting itself is not dimmed
            paint.setShader(new LinearGradient(0, h * 0.62f, 0, h, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.62f, w, h, paint);
            paint.setShader(null);

            now.setTime(System.currentTimeMillis());
            float left = w * 0.063f, right = w * 0.927f;
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(true);
            paint.setTextSize(h * 0.075f);
            c.drawText(timeFmt.format(now), left, h * 0.857f, paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(h * 0.022f);
            c.drawText(dateFmt.format(now), left, h * 0.905f, paint);

            if (title != null) {
                paint.setTextAlign(Paint.Align.RIGHT);
                if (sub != null) {
                    paint.setTextSize(h * 0.0165f);
                    paint.setAlpha(190);
                    c.drawText(sub, right, h * 0.903f, paint);
                }
                paint.setAlpha(255);
                paint.setTextSize(h * 0.021f);
                String t = title;
                int n = paint.breakText(t, true, w * 0.45f, null);
                if (n < t.length()) t = t.substring(0, Math.max(1, n - 1)) + "\u2026";
                c.drawText(t, right, h * 0.927f, paint);
            }

            // small chevron at bottom centre, as in the reference
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(h * 0.003f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setAlpha(200);
            float cx = w / 2f, cy = h * 0.953f, cw = h * 0.022f;
            c.drawLine(cx - cw, cy + cw * 0.3f, cx, cy - cw * 0.3f, paint);
            c.drawLine(cx, cy - cw * 0.3f, cx + cw, cy + cw * 0.3f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setTextAlign(Paint.Align.LEFT);            postInvalidateDelayed(20000);
        }
    }
}
