package com.eece451.networkcellanalyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight custom line chart for visualizing recent signal power values.
 * Draws the last N data points as a smooth line. Entirely self-contained —
 * no external charting library needed, so the Gradle build stays simple.
 *
 * Y-axis: signal power in dBm, fixed range [-120, -50]
 * X-axis: time (most recent on the right)
 */
class SignalChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxPoints = 20
    private val minDbm = -120f
    private val maxDbm = -50f

    private val values = ArrayDeque<Int>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#336C63FF") // semi-transparent purple
        style = Paint.Style.FILL
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 28f
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBBBBB")
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }

    fun addValue(dbm: Int) {
        values.addLast(dbm)
        while (values.size > maxPoints) values.removeFirst()
        invalidate()
    }

    fun clear() {
        values.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val paddingLeft = 80f
        val paddingRight = 20f
        val paddingTop = 20f
        val paddingBottom = 40f

        val chartLeft = paddingLeft
        val chartRight = width - paddingRight
        val chartTop = paddingTop
        val chartBottom = height - paddingBottom
        val chartHeight = chartBottom - chartTop

        // Horizontal grid + y-axis labels
        for (i in 0..3) {
            val y = chartTop + chartHeight * i / 3f
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
            val label = (maxDbm - (maxDbm - minDbm) * i / 3f).toInt().toString()
            canvas.drawText(label, 8f, y + 10f, textPaint)
        }

        if (values.isEmpty()) {
            canvas.drawText(
                "Waiting for signal data...",
                (chartLeft + chartRight) / 2f,
                (chartTop + chartBottom) / 2f,
                emptyPaint
            )
            return
        }

        // Build the line path
        val chartWidth = chartRight - chartLeft
        val step = if (values.size > 1) chartWidth / (maxPoints - 1) else 0f
        val startX = chartRight - step * (values.size - 1)

        val linePath = Path()
        val fillPath = Path()
        val points = mutableListOf<Pair<Float, Float>>()

        values.forEachIndexed { i, dbm ->
            val x = startX + i * step
            val clamped = dbm.toFloat().coerceIn(minDbm, maxDbm)
            val y = chartTop + chartHeight * (1f - (clamped - minDbm) / (maxDbm - minDbm))
            points.add(x to y)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, chartBottom)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Close fill area
        val lastX = points.last().first
        fillPath.lineTo(lastX, chartBottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Draw point dots
        for ((x, y) in points) {
            canvas.drawCircle(x, y, 7f, pointPaint)
        }
    }
}
