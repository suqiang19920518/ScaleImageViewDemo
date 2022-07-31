package com.example.scaleImageview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.viewpager.widget.ViewPager;

import java.util.function.IntConsumer;

/**
 * a custom view that supports zoom and move
 */
public class ScaleImageView extends AppCompatImageView implements OnGlobalLayoutListener, OnScaleGestureListener, OnTouchListener {
    private static final String TAG = "ScaleImageView";
    public static final int PREVIEW_CLICK_TYPE = 0;
    public static final int PREVIEW_SWITCH_LAST_TYPE = 1;
    public static final int PREVIEW_SWITCH_NEXT_TYPE = 2;

    //是否支持缩放
    private boolean isScaleEnable;
    private boolean mInit = false;

    //屏幕尺寸
    Point mScreenSize = new Point();
    private Matrix mMatrix;
    //捕获用户多指触控时缩放的比例
    private ScaleGestureDetector mScaleGestureDetector;
    private IntConsumer mEventConsumer;

    //---------------------自由移动的变量------------------------
    //记录上次多点触控的数量
    private int mLastPointerCount;
    //记录上次中心点的坐标
    private float mLastPointerX;
    private float mLastPointerY;

    //系统触发的最小滑动距离
    private float mTouchSlop;
    //是否能够移动
    private boolean isCanDrag;
    //是否超出控件宽度，即是否处于放大状态
    private boolean isBeyondLeftAndRight;
    //是否超出控件高度，即是否处于放大状态
    private boolean isBeyondTopAndBottom;

    private float downX;
    private float downY;
    private float deltaX = 0;
    private float deltaY = 0;
    //系统触发的滚动整个页面的最小距离
    private int mScrollPageSlop;
    //手指上下滑动，触发壁纸切换的有效距离
    private int validScrollYThreshold;
    private float significantMoveThreshold;


    //---------------------双击放大与缩小变量-------------------------
    //用户双击手势的对象变量
    private GestureDetector mGestureDetector;
    //是否支持双击缩放或缩小
    private boolean isDoubleTapEnable = false;
    //是否正在放大或缩小---防止用户在正在放大或缩小时疯狂点击
    private boolean isScaling;
    //初始化缩放的比例
    private float mInitScale;
    //双击放大的值
    private float mMidScale;
    //放大的最大值
    private float mMaxScale;

    public ScaleImageView(Context context) {
        this(context, null);
    }

    public ScaleImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScaleImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        // 初始化
        mMatrix = new Matrix();
        setScaleType(ScaleType.MATRIX);
        //获取屏幕尺寸
        getFullScreenSize(context, mScreenSize);

        setOnTouchListener(this);
        mScaleGestureDetector = new ScaleGestureDetector(context, this);

        //系统触发的最小滑动距离
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        //系统触发的滚动整个页面的最小距离
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mScrollPageSlop = configuration.getScaledPagingTouchSlop();

        int validScrollFactor = 40;
        significantMoveThreshold = validScrollFactor / 100f;

        //双击放大与缩小
        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isScaling) {
                    return true;
                }
                //以此点为缩放中心
                float x = e.getX();
                float y = e.getY();

                if (getCurrentScale() < mMidScale) {
                    postDelayed(new SlowlyScaleRunnable(mMidScale, x, y), 16);
//					mMatrix.postScale(mMidScale/getScale(), mMidScale/getScale(), x, y);
//					checkBorderAndCenterWhenScale();
//					setImageMatrix(mMatrix);
                } else {
                    postDelayed(new SlowlyScaleRunnable(mInitScale, x, y), 16);
//					mMatrix.postScale(mInitScale/getScale(), mInitScale/getScale(), x, y);
//					checkBorderAndCenterWhenScale();
//					setImageMatrix(mMatrix);
                }
                isScaling = true;
                return true;
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        //注册onGlobalLayoutListener
        getViewTreeObserver().addOnGlobalLayoutListener(this);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        validScrollYThreshold = (int) (getMeasuredHeight() * significantMoveThreshold);
        //LogUtils.d("onSizeChanged=" + getMeasuredHeight() + ", validScrollYThreshold=" + validScrollYThreshold);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        //移除onGlobalLayoutListener
        getViewTreeObserver().removeGlobalOnLayoutListener(this);
        mEventConsumer = null;
    }

    /**
     * 捕获图片加载完成事件
     * onMeasure和onDraw都不适合
     */
    @Override
    public void onGlobalLayout() {
        //初始化操作（一次就好，为了保证对缩放只进行一次）
        if (!mInit) {
            //得到控件的宽和高--不一定是屏幕的宽和高 可能会有actionBar等等
            int width = getWidth();
            int height = getHeight();

            //得到我们的图片以及宽和高
            Drawable drawable = getDrawable();
            if (drawable == null || drawable.getIntrinsicWidth() < 0) {
                return;
            }

            /**
             * 这里说下Drawable这个抽象类，具体实现类为BitmapDrawable
             * BitmapDrawable这个类重写了getIntrinsicWidth()和getIntrinsicHeight()方法
             * 这个两个方法看字面意思就知道是什么了，就是得到图片固有的宽和高
             */
            int intrinsicWidth = drawable.getIntrinsicWidth();
            int intrinsicHeight = drawable.getIntrinsicHeight();
            Log.e("SCALE_IMAGEVIEW", intrinsicWidth + ":intrinsicWidth");
            Log.e("SCALE_IMAGEVIEW", intrinsicHeight + ":intrinsicHeight");
            // 如果图片宽度比控件宽度小，高度比控件高度大，则需要缩小
            float scale = 1.0f;
            if (width > intrinsicWidth && height < intrinsicHeight) {
                scale = height * 1.0f / intrinsicHeight;
            }
            // 如果图片宽度比控件宽度大，高度比控件高度小，则需要缩小
            if (width < intrinsicWidth && height > intrinsicHeight) {
                scale = width * 1.0f / intrinsicWidth;
            }
            // 如果图片宽高都比控件宽高大，则需要缩小。如果图片宽高都比控件宽高小，则需要放大。
            if ((width < intrinsicWidth && height < intrinsicHeight) || (width > intrinsicWidth && height > intrinsicHeight)) {
                scale = Math.min(width * 1.0f / intrinsicWidth, height * 1.0f / intrinsicHeight);
            }

            //得到初始化缩放的比例
            mInitScale = scale;
            mMidScale = 2 * mInitScale;//双击放大的值
            mMaxScale = 4 * mInitScale;//放大的最大值

            //将图片移动到控件的中心
            int dx = width / 2 - intrinsicWidth / 2;
            int dy = height / 2 - intrinsicHeight / 2;
            //将一些参数设置到图片或控件上 设置平移缩放 旋转
            mMatrix.postTranslate(dx, dy);
            mMatrix.postScale(mInitScale, mInitScale, width / 2, height / 2);//以控件的中心进行缩放
            setImageMatrix(mMatrix);

            mInit = true;
        }
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        Log.e("ScaleGestureDetector", "onScaleBegin");
        //修改为true才会进入onScale()这个函数，否则多指触控一直走onScaleBegin方法，不走onScale和onScaleEnd方法
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        Log.e("ScaleGestureDetector", "onScaleEnd");
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        float scale = getCurrentScale();
        //捕获用户多指触控时缩放的比例
        float scaleFactor = detector.getScaleFactor();
        Log.e("ScaleGestureDetector", "scaleFactor:" + scaleFactor);
        if (getDrawable() == null) {
            return true;
        }
        //最大最小控制(缩放区间 initScale --- maxScale)
        if ((scale < mMaxScale && scaleFactor > 1.0f) || (scale > mInitScale && scaleFactor < 1.0f)) {
            if (scale * scaleFactor > mMaxScale) {
                scaleFactor = mMaxScale / scale;
            }
            if (scale * scaleFactor < mInitScale) {
                scaleFactor = mInitScale / scale;
            }

            mMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            //不断检测 控制边界和中心位置
            checkBorderAndCenterWhenScale();
            setImageMatrix(mMatrix);
        }

        return true;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (isDoubleTapEnable) {
            //双击放大与缩小事件传递给GestureDetector 放在最前面 防止双击时还能产生移动的事件响应
            if (mGestureDetector.onTouchEvent(event)) {
                return true;
            }
        }

        //将手势传递给ScaleGestureDetector
        if (isScaleEnable) {
            mScaleGestureDetector.onTouchEvent(event);
        }

        //-------------------------将放大的图片自由移动逻辑处理-----------------start------------
        //得到触控中心点的坐标
        float pointerX = 0;
        float pointerY = 0;
        //拿到多点触控的数量
        int pointerCount = event.getPointerCount();
        Log.i("pointerCount", "pointerCount:" + pointerCount);
        for (int i = 0; i < pointerCount; i++) {
            pointerX += event.getX(i);
            pointerY += event.getY(i);
        }
        pointerX /= pointerCount;
        pointerY /= pointerCount;
        if (mLastPointerCount != pointerCount) {
            //手指发生改变时 需要重新判断 是否能够移动
            isCanDrag = false;
            mLastPointerX = pointerX;
            mLastPointerY = pointerY;
        }
        mLastPointerCount = pointerCount;
        RectF rectF = getMatrixRectF();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                if (getParent() instanceof ViewPager) {
                    //如果图片放大时 处理图片平移与ViewPager的滑动冲突
                    if (rectF.width() - getWidth() > 0.01 || rectF.height() - getHeight() > 0.01) {
                        //阻止ViewPager拦截触摸事件
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                deltaX = event.getX() - downX;
                deltaY = event.getY() - downY;
                //如果图片放大时 处理图片平移与ViewPager的滑动冲突
                if (getParent() instanceof ViewPager) {
                    if (rectF.width() - getWidth() > 0.01 || rectF.height() - getHeight() > 0.01) {
                        //阻止ViewPager拦截触摸事件
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }

                float dx = pointerX - mLastPointerX;
                float dy = pointerY - mLastPointerY;
                if (!isCanDrag) {
                    isCanDrag = isMoveAction(dx, dy);
                }
                if (isCanDrag) {
                    if (getDrawable() != null) {
                        isBeyondLeftAndRight = isBeyondTopAndBottom = true;
                        //如果图片宽度小于控件宽度 不允许横向移动
                        if (rectF.width() < getWidth()) {
                            isBeyondLeftAndRight = false;
                            dx = 0;
                        }
                        //如果图片的高度小于控件的高度 不允许纵向移动
                        if (rectF.height() < getHeight()) {
                            isBeyondTopAndBottom = false;
                            dy = 0;
                        }

                        mMatrix.postTranslate(dx, dy);
                        checkBorderWhenTranslate();
                        setImageMatrix(mMatrix);
                    }
                }
                mLastPointerX = pointerX;
                mLastPointerY = pointerY;
                break;
            case MotionEvent.ACTION_UP:
                if (Math.abs(deltaY) < Math.abs(mScrollPageSlop) && Math.abs(deltaX) < Math.abs(mScrollPageSlop)) {
                    updateEventConsumer(PREVIEW_CLICK_TYPE);
                } else {
                    //final int pageHeight = getMeasuredHeight();
                    //boolean isSignificantMove = Math.abs(deltaY) > pageHeight * significantMoveThreshold;
                    //LogUtils.d("Math.abs(deltaY)=" + Math.abs(deltaY));
                    if (Math.abs(deltaY) > validScrollYThreshold) {
                        if (deltaY > 0) {
                            updateEventConsumer(PREVIEW_SWITCH_LAST_TYPE);
                        } else {
                            updateEventConsumer(PREVIEW_SWITCH_NEXT_TYPE);
                        }
                    }
                }
                deltaX = 0;
                deltaY = 0;
            case MotionEvent.ACTION_CANCEL:
                mLastPointerCount = 0;
                break;
        }
        //-------------------------将放大的图片自由移动逻辑处理-------------------end----------

        return true;
    }

    /**
     * 获取图片当前的缩放值
     *
     * @return
     */
    public float getCurrentScale() {
        float[] values = new float[9];
        mMatrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    /**
     * 获得图片放大或缩小之后的宽和高 以及 left top right bottom的坐标点
     *
     * @return
     */
    private RectF getMatrixRectF() {
        Matrix matrix = mMatrix;
        RectF rect = new RectF();
        Drawable drawable = getDrawable();
        if (null != drawable) {
            rect.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            matrix.mapRect(rect);
        }
        return rect;
    }

    /**
     * 在缩放的时候进行边界控制以及我们的中心位置控制
     */
    private void checkBorderAndCenterWhenScale() {
        RectF rect = getMatrixRectF();
        float deltaX = 0;
        float deltaY = 0;
        //控件的宽和高
        int width = getWidth();
        int height = getHeight();
        Log.i("top", "top:" + rect.top);
        Log.i("left", "left:" + rect.left);
        Log.i("right", "right:" + rect.right);
        Log.i("bottom", "bottom:" + rect.bottom);

        //如果图片的宽和高大于控件的宽和高 在缩放过程中会产生border 进行偏移补偿
        if (rect.width() >= width) {
            if (rect.left > 0) {
                deltaX = -rect.left;
            }
            if (rect.right < width) {
                deltaX = width - rect.right;
            }
        }

        if (rect.height() >= height) {
            if (rect.top > 0) {
                deltaY = -rect.top;
            }
            if (rect.bottom < height) {
                deltaY = height - rect.bottom;
            }
        }

        //如果图片的宽和高小于控件的宽和高，让其居中
        if (rect.width() < width) {
            deltaX = width / 2f - rect.right + rect.width() / 2f;
        }
        if (rect.height() < height) {
            deltaY = height / 2f - rect.bottom + rect.height() / 2f;
        }
        mMatrix.postTranslate(deltaX, deltaY);
    }

    /**
     * 当移动时 进行边界检查
     */
    private void checkBorderWhenTranslate() {

        RectF rect = getMatrixRectF();
        float deltaX = 0;
        float deltaY = 0;

        int width = getWidth();
        int height = getHeight();

        if (rect.top > 0 && isBeyondTopAndBottom) {
            deltaY = -rect.top;
        }
        if (rect.bottom < height && isBeyondTopAndBottom) {
            deltaY = height - rect.bottom;
        }

        if (rect.left > 0 && isBeyondLeftAndRight) {
            deltaX = -rect.left;
        }
        if (rect.right < width && isBeyondLeftAndRight) {
            deltaX = width - rect.right;
        }

        mMatrix.postTranslate(deltaX, deltaY);
    }

    /**
     * 判断滑动的距离是否触发滑动的临界条件
     *
     * @param dx
     * @param dy
     * @return
     */
    private boolean isMoveAction(float dx, float dy) {
        return Math.sqrt(dx * dx + dy * dy) > mTouchSlop;
    }

    /**
     * 获取屏幕尺寸
     *
     * @param context
     * @param point
     */
    public static void getFullScreenSize(Context context, Point point) {
        WindowManager windowManager = (WindowManager) context.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        Display defaultDisplay = windowManager.getDefaultDisplay();
        defaultDisplay.getRealSize(point);
    }

    /**
     * 获取bitmap
     *
     * @return
     */
    public Bitmap convertViewToBitmap() {
        Bitmap bitmap;
        int targetWidth = mScreenSize.x;
        int targetHeight = mScreenSize.y;
        try {
            bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_4444);
        }
        Canvas canvas = new Canvas(bitmap);
        draw(canvas);
        Log.d(TAG, "zoomedCropBitmap: " + bitmap.getWidth() + "-" + bitmap.getHeight());
        return bitmap;
    }

    /**
     * 是否已缩放
     *
     * @return
     */
    public boolean isScaled() {
        RectF rectF = getMatrixRectF();
        //如果图片的宽度大于控件的宽度，或者图片的高度大于控件的高度，则发生了缩放
        return rectF.width() > getWidth() || rectF.height() > getHeight();
    }

    /**
     * 重置状态
     */
    public void reset() {
        mMatrix = new Matrix();
        mInit = false;
        setImageDrawable(null);
    }

    public void setUpConsumer(IntConsumer eventConsumer) {
        this.mEventConsumer = eventConsumer;
    }

    private void updateEventConsumer(int eventType) {
        if (mEventConsumer != null) {
            mEventConsumer.accept(eventType);
        }
    }

    public boolean isScaleEnable() {
        return isScaleEnable;
    }

    public void setScaleEnable(boolean scaleEnable) {
        isScaleEnable = scaleEnable;
    }

    public boolean isDoubleTapEnable() {
        return isDoubleTapEnable;
    }

    public void setDoubleTapEnable(boolean doubleTapEnable) {
        isDoubleTapEnable = doubleTapEnable;
    }

    /**
     * 自动放大与缩小
     */
    private class SlowlyScaleRunnable implements Runnable {
        //缩放的中心点
        private float x;
        private float y;
        //缩放的目标值
        private float mTargetScale;

        //放大与缩小的梯度
        private final float BIGGER = 1.07F;
        private final float SMALL = 0.97F;

        private float tmpScale;

        public SlowlyScaleRunnable(float mTargetScale, float x, float y) {
            this.mTargetScale = mTargetScale;
            this.x = x;
            this.y = y;
            if (getCurrentScale() < mTargetScale) {
                tmpScale = BIGGER;
            }
            if (getCurrentScale() > mTargetScale) {
                tmpScale = SMALL;
            }
        }

        @Override
        public void run() {
            //进行缩放
            mMatrix.postScale(tmpScale, tmpScale, x, y);
            checkBorderAndCenterWhenScale();
            setImageMatrix(mMatrix);
            float currentScale = getCurrentScale();
            if ((tmpScale > 1.0f && currentScale < mTargetScale) || (tmpScale < 1.0f && currentScale > mTargetScale)) {
                postDelayed(this, 16);
            } else {
                isScaling = false;
                //到达了目标值
                float scale = mTargetScale / currentScale;
                mMatrix.postScale(scale, scale, x, y);
                checkBorderAndCenterWhenScale();
                setImageMatrix(mMatrix);
            }
        }
    }

}
