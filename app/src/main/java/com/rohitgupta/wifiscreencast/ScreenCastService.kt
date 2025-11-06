package com.rohitgupta.wifiscreencast

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class ScreenCastService : Service() {

    companion object {
        const val ACTION_START = "com.jenishdesai.screencast.START"
        const val ACTION_STOP = "com.jenishdesai.screencast.STOP"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val TAG = "ScreenCastService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_cast_channel"
        private const val RTSP_PORT = 8554

        var isServiceRunning = false
            private set
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var screenWidth = 1280
    private var screenHeight = 720
    private var screenDpi = 0

    private var isStreaming = false

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCastService = this@ScreenCastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                resultData?.let { startScreenCasting(it) }
            }
            ACTION_STOP -> stopScreenCasting()
        }
        return START_STICKY
    }

    private fun startScreenCasting(resultData: Intent) {
        try {
            startForeground()

            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, resultData)

            setupMediaCodec()
            setupVirtualDisplay()
            startRtspServer()

            isStreaming = true
            isServiceRunning = true

            Log.d(TAG, "Screen casting started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen cast", e)
            stopSelf()
        }
    }

    private fun startForeground() {
        val notification = createNotification("Screen casting active")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = display
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDpi = metrics.densityDpi

        // Scale down if resolution is too high
        if (screenWidth > 1920) {
            val scale = 1920f / screenWidth
            screenWidth = 1920
            screenHeight = (screenHeight * scale).toInt()
        }
    }

    private fun setupMediaCodec() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000) // 4 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
        mediaCodec?.start()

        startEncodingLoop()
    }

    private fun setupVirtualDisplay() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCast",
            screenWidth,
            screenHeight,
            screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )
    }

    private fun startRtspServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(RTSP_PORT)
                Log.d(TAG, "RTSP Server started on port $RTSP_PORT")

                withContext(Dispatchers.Main) {
                    updateNotification("Waiting for client connection...")
                }

                // Accept client connection
                clientSocket = serverSocket?.accept()
                Log.d(TAG, "Client connected: ${clientSocket?.inetAddress}")

                withContext(Dispatchers.Main) {
                    updateNotification("Client connected - Streaming")
                }

                handleRtspClient()
            } catch (e: IOException) {
                Log.e(TAG, "RTSP Server error", e)
            }
        }
    }

    private suspend fun handleRtspClient() {
        // Simplified RTSP handling
        // In production, use a proper RTSP library
        try {
            clientSocket?.getInputStream()?.use { input ->
                clientSocket?.getOutputStream()?.use { output ->
                    val buffer = ByteArray(1024)
                    while (isStreaming) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead > 0) {
                            val request = String(buffer, 0, bytesRead)
                            Log.d(TAG, "RTSP Request: $request")

                            // Send simple RTSP response
                            val response = "RTSP/1.0 200 OK\r\n\r\n"
                            output.write(response.toByteArray())
                            output.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling RTSP client", e)
        }
    }

    private fun startEncodingLoop() {
        serviceScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()

            while (isStreaming) {
                try {
                    val outputBufferId = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: continue

                    if (outputBufferId >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferId)

                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // Send encoded data to client
                            sendEncodedData(outputBuffer, bufferInfo)
                        }

                        mediaCodec?.releaseOutputBuffer(outputBufferId, false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Encoding error", e)
                    break
                }
            }
        }
    }

    private fun sendEncodedData(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            clientSocket?.getOutputStream()?.let { output ->
                val data = ByteArray(info.size)
                buffer.get(data)
                output.write(data)
                output.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error sending encoded data", e)
        }
    }

    private fun stopScreenCasting() {
        isStreaming = false
        isServiceRunning = false

        serviceScope.cancel()

        clientSocket?.close()
        serverSocket?.close()

        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null

        inputSurface?.release()
        inputSurface = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Screen casting stopped")
    }

    fun isStreaming(): Boolean = isStreaming

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Cast Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for screen casting service"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wi-Fi Screen Cast")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenCasting()
    }
}