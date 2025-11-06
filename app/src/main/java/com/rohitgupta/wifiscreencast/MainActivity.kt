package com.rohitgupta.wifiscreencast

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jenishdesai.screencast.databinding.ActivityMainBinding
import com.rohitgupta.wifiscreencast.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var screenCastService: ScreenCastService? = null
    private var isBound = false

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // Screen capture permission launcher
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                startScreenCasting(intent)
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Notification permission launcher (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, "Notification permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenCastService.LocalBinder
            screenCastService = binder.getService()
            isBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            screenCastService = null
            isBound = false
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        displayLocalIpAddress()
    }

    private fun setupUI() {
        binding.btnStartCast.setOnClickListener {
            checkPermissionsAndStartCast()
        }

        binding.btnStopCast.setOnClickListener {
            stopScreenCasting()
        }

        updateUI()
    }

    private fun checkPermissionsAndStartCast() {
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    requestScreenCapture()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startScreenCasting(data: Intent) {
        val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_START
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Bind to service
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        Toast.makeText(this, "Screen casting started", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopScreenCasting() {
        val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_STOP
        }
        startService(serviceIntent)

        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }

        Toast.makeText(this, "Screen casting stopped", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun displayLocalIpAddress() {
        val ipAddress = getLocalIpAddress()
        if (ipAddress != null) {
            binding.tvIpAddress.text = "Device IP: $ipAddress"
            binding.tvStreamUrl.text = "Stream URL: rtsp://$ipAddress:8554/screen"
        } else {
            binding.tvIpAddress.text = "Device IP: Not connected to Wi-Fi"
            binding.tvStreamUrl.text = "Stream URL: N/A"
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun updateUI() {
        val isStreaming = screenCastService?.isStreaming() == true

        binding.btnStartCast.isEnabled = !isStreaming
        binding.btnStopCast.isEnabled = isStreaming

        if (isStreaming) {
            binding.tvStatus.text = "Status: Streaming Active"
            binding.tvStatus.setTextColor(getColor(R.color.success))
            binding.viewStatusIndicator.setBackgroundColor(getColor(R.color.success))
        } else {
            binding.tvStatus.text = "Status: Not Streaming"
            binding.tvStatus.setTextColor(getColor(R.color.textSecondary))
            binding.viewStatusIndicator.setBackgroundColor(getColor(R.color.gray))
        }
    }

    override fun onResume() {
        super.onResume()
        displayLocalIpAddress()

        // Try to rebind if service is running
        if (!isBound && ScreenCastService.isServiceRunning) {
            val serviceIntent = Intent(this, ScreenCastService::class.java)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}