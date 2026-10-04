package com.example.videoframes

import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnPick: Button
    private lateinit var tvVideoInfo: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var etInterval: EditText
    private lateinit var etCount: EditText
    private lateinit var btnStart: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private var videoUri: Uri? = null
    private var durationMs: Long = 0L
    private var videoName: String = "video"

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onVideoSelected(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPick = findViewById(R.id.btnPick)
        tvVideoInfo = findViewById(R.id.tvVideoInfo)
        rgMode = findViewById(R.id.rgMode)
        etInterval = findViewById(R.id.etInterval)
        etCount = findViewById(R.id.etCount)
        btnStart = findViewById(R.id.btnStart)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        btnPick.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            etInterval.isEnabled = checkedId == R.id.rbInterval
            etCount.isEnabled = checkedId == R.id.rbCount
        }
        rgMode.check(R.id.rbInterval)

        btnStart.setOnClickListener { startExtract() }
    }

    private fun onVideoSelected(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            toast("无法读取视频: ${e.message}")
            return
        } finally {
            retriever.release()
        }

        if (durationMs <= 0L) {
            toast("无法获取视频时长")
            return
        }

        videoUri = uri
        videoName = queryDisplayName(uri) ?: "video"
        tvVideoInfo.text = getString(R.string.video_info, videoName, formatDuration(durationMs))
        btnStart.isEnabled = true
        tvStatus.text = ""
    }

    private fun startExtract() {
        val uri = videoUri ?: return
        val durationSec = durationMs / 1000.0

        // 计算每张截图对应的时间点(秒)
        val times: List<Double> = when (rgMode.checkedRadioButtonId) {
            R.id.rbInterval -> {
                val sec = etInterval.text.toString().trim().toDoubleOrNull()
                if (sec == null || sec <= 0.0) {
                    toast("请输入正确的间隔秒数")
                    return
                }
                val list = mutableListOf<Double>()
                var t = 0.0
                while (t < durationSec) {
                    list.add(t)
                    t += sec
                }
                if (list.isEmpty()) list.add(0.0)
                list
            }

            else -> {
                val n = etCount.text.toString().trim().toIntOrNull()
                if (n == null || n <= 0) {
                    toast("请输入正确的截图数量")
                    return
                }
                (0 until n).map { durationSec * it / n }
            }
        }

        val capped = if (times.size > MAX_FRAMES) {
            toast("截图数量过多,最多 $MAX_FRAMES 张")
            times.take(MAX_FRAMES)
        } else {
            times
        }

        setRunning(true)
        progressBar.max = capped.size
        progressBar.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
            var saved = 0
            var error: String? = null
            val folder = sanitizeFolderName(videoName)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MainActivity, uri)
                for ((i, t) in capped.withIndex()) {
                    val bitmap = retriever.getFrameAtTime(
                        (t * 1_000_000).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    if (bitmap != null) {
                        if (saveFrame(bitmap, folder, i)) saved++
                        bitmap.recycle()
                    }
                    withContext(Dispatchers.Main) {
                        progressBar.progress = i + 1
                        tvStatus.text = getString(R.string.status_progress, i + 1, capped.size)
                    }
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                retriever.release()
            }

            withContext(Dispatchers.Main) {
                setRunning(false)
                tvStatus.text = if (error != null) {
                    getString(R.string.status_failed, error)
                } else {
                    getString(R.string.status_done, saved, folder)
                }
            }
        }
    }

    /** 通过 MediaStore 保存到 相册/Pictures/VideoFrames/<视频名>/,无需任何权限 */
    private fun saveFrame(bitmap: Bitmap, folder: String, index: Int): Boolean {
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                String.format(Locale.US, "frame_%04d.jpg", index + 1)
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VideoFrames/$folder")
        }
        val uri = contentResolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: return false

        return try {
            val ok = contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            } == true
            if (!ok) contentResolver.delete(uri, null, null)
            ok
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun setRunning(running: Boolean) {
        btnStart.isEnabled = !running
        btnPick.isEnabled = !running
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    private fun sanitizeFolderName(name: String): String =
        name.substringBeforeLast('.')
            .replace(Regex("[^\\w一-鿿-]"), "_")
            .ifBlank { "video" }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val MAX_FRAMES = 500
    }
}
