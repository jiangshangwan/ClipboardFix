package com.clipboardfix

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 临界阻尼弹簧，等价于 Compose 的 spring(dampingRatio, stiffness, visibilityThreshold)。
 *
 * <p>参考项目（WeChat-LiquidGlass / HyperIsland / KernelSU）的液态玻璃动画全部由弹簧驱动
 * ——五条独立弹簧（位置、速度、按压、横向缩放、纵向缩放），而不是时长插值器。
 * 手感来自物理参数本身，所以这里照搬参数而不做近似。
 *
 * <p>半隐式欧拉积分；步长细分为 1/240s，保证刚度 1000 这一档也稳定。
 */
class Spring(
    private val mDampingRatio: Float,
    private val mStiffness: Float,
    private val mThreshold: Float,
    initial: Float
) {
    private var mValue: Float = initial
    private var mVelocity: Float = 0f
    private var mTarget: Float = initial
    private var mRunning: Boolean = false

    constructor(dampingRatio: Float, stiffness: Float) : this(dampingRatio, stiffness, 0.001f, 0f)

    fun value(): Float = mValue
    fun target(): Float = mTarget
    fun velocity(): Float = mVelocity
    fun isRunning(): Boolean = mRunning

    fun animateTo(target: Float) {
        if (mTarget != target) {
            mTarget = target
            mRunning = true
        }
    }

    fun snapTo(value: Float) {
        mValue = value
        mTarget = value
        mVelocity = 0f
        mRunning = false
    }

    /** 推进 dt 秒；仍在运动返回 true。 */
    fun update(dt: Float): Boolean {
        if (!mRunning) return false
        var remaining = Math.min(dt, 0.064f)
        val damping = 2f * mDampingRatio * sqrt(mStiffness.toDouble()).toFloat()
        while (remaining > 0f) {
            val step = Math.min(remaining, 1f / 240f)
            remaining -= step
            val accel = -mStiffness * (mValue - mTarget) - damping * mVelocity
            mVelocity += accel * step
            mValue += mVelocity * step
        }
        if (abs(mValue - mTarget) < mThreshold && abs(mVelocity) < mThreshold * 10f) {
            mValue = mTarget
            mVelocity = 0f
            mRunning = false
        }
        return mRunning
    }
}
