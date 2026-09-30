package com.otaclient

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.otaclient.utils.AppStorage
import com.otaclient.utils.OTAClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class SplashActivity : AppCompatActivity() {

  private enum class TargetDestination {
    MAIN, CONFIGURE
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContentView(R.layout.activity_splash)

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
      val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
      insets
    }

    lifecycleScope.launch {
      AppStorage.loadManifest(context = this@SplashActivity)

      AppStorage.getDeviceInfo(contentResolver)

      val isHealthy = withContext(Dispatchers.IO) {
        OTAClient.instance.healthCheck()
      }

      if (isHealthy) {
        println("API is healthy. Proceeding to MainActivity.")
        checkUpdate()
      } else {
        println("API Health Check failed. Redirecting to settings setup.")
        // navigateTo(TargetDestination.CONFIGURE) // TODO: Verify this
        navigateTo(TargetDestination.MAIN)
      }
    }
  }

  private fun navigateTo(destination: TargetDestination) {
    val destinationClass = when (destination) {
      TargetDestination.MAIN -> MainActivity::class.java
      TargetDestination.CONFIGURE -> ConfigureActivity::class.java
    }

    val intent = Intent(this, destinationClass)
    startActivity(intent)
    finish()
  }

  private suspend fun checkUpdate() {
    val check = OTAClient.instance.checkUpdate()

    if (!check.haveUpdate) {
      navigateTo(TargetDestination.MAIN)
      return
    }

    if (check.haveBundleUpdate) {
      val accept = confirmUpdate(
        title = "Update available",
        message = "A new bundle update is available. Install it now?"
      )

      if (!accept) {
        navigateTo(TargetDestination.MAIN)
        return
      }

      val progressBar = findViewById<ProgressBar>(R.id.progressBar)
      val progressText = findViewById<TextView>(R.id.progressText)

      progressBar.isIndeterminate = true
      progressBar.visibility = View.VISIBLE
      progressText.visibility = View.VISIBLE
      progressText.text = "Downloading new update..."

      val response = OTAClient.instance.downloadBundle(
        context = this@SplashActivity,
        check = check,
        onProgress = { downloaded, total ->
          if (total != null) {
            println("Progress: $downloaded/$total")
            progressBar.isIndeterminate = false
            progressBar.max = total.toInt()
            progressBar.progress = downloaded.toInt()
            val percent = (downloaded * 100 / total).toInt()
            progressText.text = "Downloading new update... $percent%"
          }
        }
      )

      AppStorage.RUNNING_BUNDLE_DIR = response.downloadDir
      AppStorage.loadManifest(this@SplashActivity)

      println("Bundle: ${response.bundleFile.absolutePath}")
      println("Manifest: ${AppStorage.MANIFEST}")

      restartApp()
      return
    }

    if (check.haveVersionUpdate) {
      val accept = confirmUpdate(
        title = "Update available",
        message = "A new app version is available. Install it now?"
      )

      if (!accept) {
        navigateTo(TargetDestination.MAIN)
        return
      }

      // // Android 8+ unknown-app installation permission
      // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
      //   startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
      //   return
      // }

      // val response = OTAClient.instance.downloadApp(this@SplashActivity, check = check)

      // println("APK: ${response.appFile.absolutePath}")

      // val intent = Intent(Intent.ACTION_VIEW).apply {
      //   setDataAndType(response.appFileUri, "application/vnd.android.package-archive")
      //   addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      //   addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      // }

      // startActivity(intent)

      val url = OTAClient.instance.downloadApp(this@SplashActivity, check = check)
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

      try {
        startActivity(intent)
      } catch (e: Exception) {
        e.printStackTrace()
      }

      return
    }

    navigateTo(TargetDestination.MAIN)
  }

  private suspend fun confirmUpdate(title: String, message: String): Boolean =
    suspendCancellableCoroutine { continuation ->
      AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("Install") { _, _ -> continuation.resume(true) }
        .setNegativeButton("Not now") { _, _ -> continuation.resume(false) }
        .setCancelable(false)
        .show()
    }

  fun restartApp() {
    val packageManager = packageManager
    val intent = packageManager.getLaunchIntentForPackage(packageName)

    if (intent != null) {
        // Clear all previous activities on the backstack
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)

        // Kill the current process completely
        kotlin.system.exitProcess(0)
    }
  }

}
