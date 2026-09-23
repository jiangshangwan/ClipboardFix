package com.clipboardfix;

/**
 * 临界阻尼弹簧，等价于 Compose 的 {@code spring(dampingRatio, stiffness, visibilityThreshold)}。
 *
 * <p>参考项目（WeChat-LiquidGlass / HyperIsland / KernelSU）的液态玻璃动画全部由弹簧驱动
 * ——五条独立弹簧（位置、速度、按压、横向缩放、纵向缩放），而不是时长插值器。
 * 手感来自物理参数本身，所以这里照搬参数而不做近似。
 *
 * <p>半隐式欧拉积分；步长细分为 1/240s，保证刚度 1000 这一档也稳定。
 */
final class Spring {

    private final float mStiffness;
    private final float mDampingRatio;
    private final float mThreshold;

    private float mValue;
    private float mVelocity;
    private float mTarget;
    private boolean mRunning;

    Spring(float dampingRatio, float stiffness) {
        this(dampingRatio, stiffness, 0.001f, 0f);
    }

    Spring(float dampingRatio, float stiffness, float threshold, float initial) {
        mDampingRatio = dampingRatio;
        mStiffness = stiffness;
        mThreshold = threshold;
        mValue = initial;
        mTarget = initial;
    }

    float value() {
        return mValue;
    }

    float target() {
        return mTarget;
    }

    float velocity() {
        return mVelocity;
    }

    boolean isRunning() {
        return mRunning;
    }

    void animateTo(float target) {
        if (mTarget != target) {
            mTarget = target;
            mRunning = true;
        }
    }

    void snapTo(float value) {
        mValue = value;
        mTarget = value;
        mVelocity = 0f;
        mRunning = false;
    }

    /** 推进 {@code dt} 秒；仍在运动返回 true。 */
    boolean update(float dt) {
        if (!mRunning) {
            return false;
        }
        float remaining = Math.min(dt, 0.064f);
        float damping = 2f * mDampingRatio * (float) Math.sqrt(mStiffness);
        while (remaining > 0f) {
            float step = Math.min(remaining, 1f / 240f);
            remaining -= step;
            float accel = -mStiffness * (mValue - mTarget) - damping * mVelocity;
            mVelocity += accel * step;
            mValue += mVelocity * step;
        }
        if (Math.abs(mValue - mTarget) < mThreshold
                && Math.abs(mVelocity) < mThreshold * 10f) {
            mValue = mTarget;
            mVelocity = 0f;
            mRunning = false;
        }
        return mRunning;
    }
}
