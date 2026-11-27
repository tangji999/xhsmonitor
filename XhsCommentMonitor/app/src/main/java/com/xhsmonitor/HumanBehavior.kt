package com.xhsmonitor

import android.graphics.Path
import android.graphics.Rect
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 类人化行为节奏工具。
 *
 * 只做三件事：让操作的时间间隔、触点位置、滑动轨迹更接近真实用户的自然波动。
 * 不伪造设备指纹、不绕过验证码、不轮换 IP、不模拟传感器数据。
 *
 * 注意：任何类人化都无法保证不被平台识别，平台仍可能通过账号历史、网络环境、
 * 设备指纹和行为统计发现异常。请控制使用强度，优先使用备用账号。
 */
object HumanBehavior {
    private val rng = Random.Default

    /** 标准正态分布采样（Box-Muller） */
    private fun gaussian(): Double {
        var u = 0.0
        var v = 0.0
        while (u <= 0.0) u = rng.nextDouble()
        while (v <= 0.0) v = rng.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)
    }

    /**
     * 类人动作间隔：围绕基准值的正态波动，并带小概率的「走神/放下手机」长停顿。
     * 相比固定 ±15% 抖动，这个分布更接近真人的操作节奏。
     */
    fun actionDelay(baseMs: Long): Long {
        val b = baseMs.coerceAtLeast(200L).toDouble()
        var value = b + gaussian() * b * 0.32
        if (rng.nextDouble() < 0.07) {
            value *= 1.7 + rng.nextDouble() * 2.0
        }
        val min = (b * 0.45).toLong().coerceAtLeast(180L)
        val max = (b * 5).toLong().coerceAtMost(30_000L).coerceAtLeast(min)
        return value.toLong().coerceIn(min, max)
    }

    /** 点进笔记后「先看正文」的停留时间 */
    fun readingPauseMs(): Long = actionDelay(3_200L)

    /** 连续运行多久后安排一次休息：8~15 分钟 */
    fun breakAfterMs(): Long = 480_000L + rng.nextLong(0L, 420_000L)

    /** 休息时长：1~3 分钟 */
    fun sessionBreakMs(): Long = 60_000L + rng.nextLong(0L, 120_000L)

    data class TypingStep(val text: String, val waitBeforeMs: Long)

    /**
     * 中文输入法通常按词组整体选字上屏，而不是逐字母。
     * 按 2~3 字切段，段间带选词停顿，偶尔有较长的思考停顿。
     */
    fun typingSteps(target: String): List<TypingStep> {
        val steps = mutableListOf<TypingStep>()
        var index = 0
        var accumulated = ""
        while (index < target.length) {
            val remaining = target.length - index
            val length = if (remaining <= 2) remaining else (2 + rng.nextInt(2)).coerceAtMost(remaining)
            accumulated += target.substring(index, index + length)
            index += length
            val wait = if (rng.nextDouble() < 0.12) {
                rng.nextLong(700L, 1_500L)
            } else {
                rng.nextLong(160L, 480L)
            }
            steps.add(TypingStep(accumulated, wait))
        }
        return steps
    }

    data class Tap(val x: Float, val y: Float, val durationMs: Long)

    /** 触点在目标范围内呈高斯分布，避免每次都点在正中心 */
    fun tap(rect: Rect): Tap {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val dx = (gaussian() * rect.width() * 0.13f).toFloat()
            .coerceIn(-rect.width() * 0.28f, rect.width() * 0.28f)
        val dy = (gaussian() * rect.height() * 0.11f).toFloat()
            .coerceIn(-rect.height() * 0.24f, rect.height() * 0.24f)
        return Tap(cx + dx, cy + dy, rng.nextLong(70L, 160L))
    }

    data class Swipe(val path: Path, val durationMs: Long)

    /** 带轻微横向弧线和速度变化的上滑 */
    fun swipeUp(width: Int, height: Int): Swipe {
        val startX = (width * 0.5f + gaussian().toFloat() * width * 0.07f)
            .coerceIn(width * 0.25f, width * 0.75f)
        val startY = height * (0.70f + rng.nextDouble() * 0.14).toFloat()
        val distance = height * (0.30f + rng.nextDouble() * 0.20).toFloat()
        val endX = (startX + gaussian().toFloat() * width * 0.04f)
            .coerceIn(width * 0.20f, width * 0.80f)
        return buildSwipe(startX, startY, endX, startY - distance, width)
    }

    /** 小幅下滑，模拟回看刚才划过的内容 */
    fun swipeDownSmall(width: Int, height: Int): Swipe {
        val startX = (width * 0.5f + gaussian().toFloat() * width * 0.06f)
            .coerceIn(width * 0.25f, width * 0.75f)
        val startY = height * (0.35f + rng.nextDouble() * 0.12).toFloat()
        val distance = height * (0.10f + rng.nextDouble() * 0.10).toFloat()
        val endX = (startX + gaussian().toFloat() * width * 0.03f)
            .coerceIn(width * 0.20f, width * 0.80f)
        return buildSwipe(startX, startY, endX, startY + distance, width)
    }

    /** 用多段直线逼近自然弧线，兼容性优于在手势里使用曲线指令 */
    private fun buildSwipe(sx: Float, sy: Float, ex: Float, ey: Float, width: Int): Swipe {
        val path = Path()
        path.moveTo(sx, sy)
        val bend = gaussian().toFloat() * width * 0.02f
        for (i in 1..6) {
            val t = i / 6f
            val x = sx + (ex - sx) * t + bend * (4f * t * (1f - t))
            val y = sy + (ey - sy) * t
            path.lineTo(x, y)
        }
        val duration = when {
            rng.nextDouble() < 0.15 -> rng.nextLong(520L, 780L)
            rng.nextDouble() < 0.20 -> rng.nextLong(160L, 260L)
            else -> rng.nextLong(300L, 520L)
        }
        return Swipe(path, duration)
    }

    /** 真人不会把每条结果都点开 */
    fun shouldSkipNote(): Boolean = rng.nextDouble() < 0.20

    /** 看评论时偶尔往上回翻一下 */
    fun shouldBackScroll(): Boolean = rng.nextDouble() < 0.16

    /** 每篇笔记看几屏评论，1~2 屏随机 */
    fun commentScrollRounds(): Int = 1 + rng.nextInt(2)
}
