package com.handdraw.studio

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object GeometryUtils {

    fun distance(a: PointF, b: PointF): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /** Shortest distance from point p to the segment a-b. Direct port of the Python helper. */
    fun pointSegmentDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y

        if (dx == 0f && dy == 0f) return distance(p, a)

        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
        t = max(0f, min(1f, t))

        val closest = PointF(a.x + dx * t, a.y + dy * t)
        return distance(p, closest)
    }
}
