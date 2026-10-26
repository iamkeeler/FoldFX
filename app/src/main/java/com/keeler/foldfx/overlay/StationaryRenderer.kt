package com.keeler.foldfx.overlay

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import com.keeler.foldfx.capture.SnapshotFrames
import com.keeler.foldfx.optics.FoldOptics
import com.keeler.foldfx.optics.FoldSurface

/** Keeps the captured page registered to screen pixels; only optical spread changes. */
@RequiresApi(33)
internal class StationaryRenderer {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@StationaryRenderer.shader }
    private var frame: SnapshotFrames.Frame? = null

    fun bind(next: SnapshotFrames.Frame) {
        if (next === frame) return
        frame = next
        val image = BitmapShader(next.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        image.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        shader.setInputShader("image", image)
        shader.setFloatUniform("imageSize", next.bitmap.width.toFloat(), next.bitmap.height.toFloat())
    }

    fun draw(canvas: Canvas, width: Int, height: Int, angle: Float, progress: Float,
             intensity: Float, surface: FoldSurface, movingLeft: Boolean, rotation: Int) {
        if (frame == null || width == 0 || height == 0) return
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("rotation", rotation.toFloat())
        shader.setFloatUniform("side", if (movingLeft) -1f else 1f)
        shader.setFloatUniform("inner", if (surface == FoldSurface.INNER) 1f else 0f)
        shader.setFloatUniform("strength", if (surface == FoldSurface.INNER) FoldOptics.diffusionStrength(angle) else progress)
        shader.setFloatUniform("intensity", intensity)
        // Hold the page solid until the final focus landing, avoiding a long double image.
        paint.alpha = (progress / 0.015f * 255f).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        // Original AGSL implementation. Fixed bounds keep sampling cost predictable.
        private const val SOURCE = """
            uniform shader image;
            uniform float2 size;
            uniform float2 imageSize;
            uniform float side;
            uniform float inner;
            uniform float strength;
            uniform float intensity;
            uniform float rotation;

            half4 main(float2 p) {
                // Work in the panel's natural orientation, even when apps rotate.
                float2 panel = p;
                float2 panelSize = size;
                if (rotation > 0.5 && rotation < 1.5) {
                    panel = float2(p.y, size.x - p.x);
                    panelSize = size.yx;
                } else if (rotation > 1.5 && rotation < 2.5) {
                    panel = size - p;
                } else if (rotation > 2.5) {
                    panel = float2(size.y - p.y, p.x);
                    panelSize = size.yx;
                }
                float hinge = inner > 0.5 ? panelSize.x * 0.5 : (side < 0.0 ? panelSize.x : 0.0);
                float distance = (panel.x - hinge) * side;
                float span = inner > 0.5 ? panelSize.x * 0.5 : panelSize.x;
                float edge = clamp(distance / span, 0.0, 1.0);
                // Identity sampling on BOTH halves. Rotation only locates the crease;
                // no perspective, translation, scale, or image-edge reveal is applied.
                float2 uv = p * imageSize / size;
                float edge3 = edge * edge * edge;
                float radius = min(96.0, min(imageSize.x, imageSize.y) * 0.055) * edge3 * strength * intensity;
                half4 color;
                if (radius < 0.5) {
                    color = image.eval(uv);
                } else {
                    color = half4(0.0);
                    for (int y = -2; y <= 2; y++) {
                        for (int x = -2; x <= 2; x++) {
                            float wx = x == 0 ? 6.0 : (abs(float(x)) == 1.0 ? 4.0 : 1.0);
                            float wy = y == 0 ? 6.0 : (abs(float(y)) == 1.0 ? 4.0 : 1.0);
                            // Symmetric optical spread can soften/stretch detail without
                            // moving the centre of an icon or changing the page geometry.
                            float2 spread = (rotation > 0.5 && rotation < 1.5) || rotation > 2.5
                                ? float2(0.5, 0.6) : float2(0.6, 0.5);
                            color += image.eval(uv + float2(float(x), float(y)) * radius * spread) * half(wx * wy / 256.0);
                        }
                    }
                }
                float shade = min(0.6, edge3 * strength * intensity * 0.48);
                color.rgb *= half(1.0 - shade);
                return color;
            }
        """
    }
}
