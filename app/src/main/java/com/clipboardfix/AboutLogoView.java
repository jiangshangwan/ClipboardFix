package com.clipboardfix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * 「关于」页的 LOGO 图标 —— 底板随背景流光变色、图形恒定纯白的观感。
 *
 * 要做到「底板取其后背景色」，目标是让「圆角方形底板 + 高饱和紫渐变 + 纯白图形」的底板吃进背后颜色。
 * 常规原生做法是调用小米私有的 MiuiBlurUtils.setViewBlurMode + addBackgroundBlenderColor，
 * 它依赖 MIUI 私有接口，且需要读取「背后像素」（归 SurfaceFlinger 所有，第三方 App 拿不到），无法照搬。
 *
 * 本实现的等价做法（全部为标准 API）：
 *   ① 底板＝圆角方形（squircle），颜色不是写死的，而是把与页面同源的极光 AGSL 铺上去
 *      —— 由 AboutBgView 经 setGlowShader 注入，并用 uTranslate 把图标局部坐标平移回
 *      背景坐标，于是底板每个像素取到它正下方背景极光的色相；同时把饱和度/明度拉向
 *      uLogoSat / uLogoLum，保证底板是「高饱和紫」而不是淡淡的背景色。
 *   ② 图形＝纯白，来自 ic_logo_glyph.png（透明 PNG，几何重建，边缘无噪点）。
 * 于是「底板随流光呼吸变色 + 图形恒定纯白」，与目标结构完全一致。
 *
 * API < 33 无 RuntimeShader 时：底板退化为固定紫色实心，图形仍为纯白，形态不变。
 */
public class AboutLogoView extends ImageView {

    /** 铺底板用（着色器或固定色）。 */
    private final Paint mPlatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 画白色图形用。 */
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF mPlate = new RectF();
    private final Rect mDst = new Rect();

    @Nullable
    private Bitmap mGlyph;
    /** 底板圆角占短边的比例（设计稿实测约 0.27）。 */
    private float mRadiusRatio = 0.27f;
    /** 图形相对底板的占比（设计稿实测约 0.5~0.55，我们是宽卡片所以略大）。 */
    private float mGlyphScale = 0.62f;

    /** 无极光着色器时的兜底紫色（对齐设计稿底板实测 RGB(95,32,124)）。 */
    private static final int FALLBACK_PLATE = Color.rgb(95, 32, 124);

    public AboutLogoView(Context context) {
        super(context);
        init();
    }

    public AboutLogoView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AboutLogoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPlatePaint.setColor(FALLBACK_PLATE);
        mPlatePaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        mGlyph = (drawable instanceof BitmapDrawable) ? ((BitmapDrawable) drawable).getBitmap() : null;
        invalidate();
    }

    /**
     * 注入极光着色器（由 AboutBgView 调用）填充底板；传 null 则退化为固定紫色。
     * 底板颜色 = 该像素正下方背景极光的色相 + 拉向 uLogoSat/uLogoLum 的饱和度/明度。
     */
    public void setGlowShader(@Nullable Shader shader) {
        mPlatePaint.setShader(shader);
        invalidate();
    }

    /**
     * 图形（卡片）着色。浅色模式传纯白（= 原图色，不改变），深色模式传深紫
     * （与浅紫底板形成强反差，对齐设计稿深色模式「浅底 + 深图形」观感）。
     * 传 0 表示清除着色（保持原图）。
     */
    public void setGlyphTint(int color) {
        if (color == 0) {
            mGlyphPaint.setColorFilter(null);
        } else {
            mGlyphPaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = Math.min(w, h) * mRadiusRatio;

        // ① 底板：圆角方形（不用 clipToOutline，避免依赖背景 outline / 硬件裁切差异）
        mPlate.set(0f, 0f, (float) w, (float) h);
        canvas.drawRoundRect(mPlate, r, r, mPlatePaint);

        // ② 图形：纯白，居中
        Bitmap g = mGlyph;
        if (g != null) {
            int side = (int) (Math.min(w, h) * mGlyphScale);
            int left = (w - side) / 2;
            int top = (h - side) / 2;
            mDst.set(left, top, left + side, top + side);
            canvas.drawBitmap(g, null, mDst, mGlyphPaint);
        }
    }
}
