package com.example.pushupcounter

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.*
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var previewView: PreviewView
    private lateinit var overlay: PoseOverlay
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var detector: PoseDetector
    private val pushUpDetector = PushUpDetector()
    private var running = true
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(16,20,24)) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = PoseOverlay(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        countText = TextView(this).apply { text = "0"; textSize = 64f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) }
        val countLabel = TextView(this).apply { text = "PUSH-UPS"; textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
        val counter = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24,18,24,18); setBackgroundColor(0xB0101418.toInt()) }
        counter.addView(countText, LinearLayout.LayoutParams(-1, -2)); counter.addView(countLabel, LinearLayout.LayoutParams(-1, -2))
        val top = FrameLayout.LayoutParams(190, 140, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 32 }
        root.addView(counter, top)

        statusText = TextView(this).apply { text = "Position your full body in frame"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(18,12,18,12); setBackgroundColor(0xB0101418.toInt()) }
        root.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 105 })
        val reset = Button(this).apply { text = "RESET"; setOnClickListener { pushUpDetector.reset(); countText.text = "0"; statusText.text = "Ready" } }
        root.addView(reset, FrameLayout.LayoutParams(130, 58, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 28 })
        setContentView(root)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else statusText.text = "Camera permission is required"
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setTargetResolution(Size(480, 360)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                if (!running) { proxy.close(); return@setAnalyzer }
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                detector.process(image).addOnSuccessListener { pose ->
                    val points = pose.allPoseLandmarks.map { PointF(it.position.x, it.position.y) }
                    val result = pushUpDetector.update(pose)
                    runOnUiThread { overlay.setPose(points, image.width, image.height, lensFacing == CameraSelector.LENS_FACING_FRONT); countText.text = result.count.toString(); statusText.text = result.message }
                }.addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lensFacing).build(), preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() { super.onDestroy(); executor.shutdown(); detector.close() }
}

data class CounterResult(val count: Int, val message: String)

class PushUpDetector {
    private enum class State { READY, DOWN }
    private var state = State.READY
    private var count = 0
    private var lastCountAt = 0L
    fun reset() { count = 0; state = State.READY; lastCountAt = 0L }
    fun update(pose: Pose): CounterResult {
        val l = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW); val s = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER); val w = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val r = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW); val rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER); val rw = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        if (l == null || s == null || w == null || r == null || rs == null || rw == null) return CounterResult(count, "Show both arms to the camera")
        val angle = (angle(s.position, l.position, w.position) + angle(rs.position, r.position, rw.position)) / 2f
        val now = System.currentTimeMillis()
        if (angle < 105f) { state = State.DOWN; return CounterResult(count, "DOWN — push up") }
        if (angle > 155f && state == State.DOWN && now - lastCountAt > 450) { count++; lastCountAt = now; state = State.READY; return CounterResult(count, "GOOD — ${count} complete") }
        return CounterResult(count, if (state == State.DOWN) "Push up" else "Go down to start")
    }
    private fun angle(a: PointF, b: PointF, c: PointF): Float {
        val ab = atan2(a.y-b.y, a.x-b.x); val cb = atan2(c.y-b.y, c.x-b.x)
        var d = abs((ab-cb) * 180f / Math.PI.toFloat()); if (d > 180f) d = 360f-d; return d
    }
}

class PoseOverlay(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private var points = emptyList<PointF>(); private var iw = 1; private var ih = 1; private var mirror = false
    private val connections = arrayOf(intArrayOf(11,13,15), intArrayOf(12,14,16), intArrayOf(11,12), intArrayOf(11,23), intArrayOf(12,24), intArrayOf(23,24), intArrayOf(23,25,27), intArrayOf(24,26,28))
    fun setPose(p: List<PointF>, width: Int, height: Int, front: Boolean) { points=p; iw=width; ih=height; mirror=front; invalidate() }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); if (points.isEmpty()) return
        val scale = min(width.toFloat()/iw, height.toFloat()/ih); val ox=(width-iw*scale)/2f; val oy=(height-ih*scale)/2f
        fun x(v: Float) = if (mirror) width-(ox+v*scale) else ox+v*scale; fun y(v: Float)=oy+v*scale
        paint.color=Color.rgb(0,208,132); paint.strokeWidth=8f; paint.style=Paint.Style.STROKE
        connections.forEach { chain -> for (i in 0 until chain.size-1) { val a=points.getOrNull(chain[i]); val b=points.getOrNull(chain[i+1]); if(a!=null&&b!=null) canvas.drawLine(x(a.x),y(a.y),x(b.x),y(b.y),paint) } }
        paint.style=Paint.Style.FILL; points.forEach { canvas.drawCircle(x(it.x),y(it.y),7f,paint) }
    }
}
