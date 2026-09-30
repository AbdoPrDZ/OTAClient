package com.otaclient.utils

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.os.Build
import androidx.core.content.edit
import com.google.gson.Gson
import com.otaclient.data.DeviceInfo
import com.otaclient.data.Manifest
import java.io.File

object AppStorage {
  private const val PREF_NAME = "OTACenter"
  private lateinit var preferences: SharedPreferences

  fun init(context: Context) {
    preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
  }

  var API_BASE_URL: String
      get() = preferences.getString("API_BASE_URL", null) ?: "http://172.16.40.112"
      private set(value) = preferences.edit(commit = true) { putString("API_BASE_URL", value) }

  var API_VERSION: String
      get() = preferences.getString("API_VERSION", null) ?: "v1"
      private set(value) = preferences.edit(commit = true) { putString("API_VERSION", value) }

  var API_KEY: String
      get() = preferences.getString("API_KEY", null) ?: "v1.xxxxxxxxxxxxxx"
      private set(value) = preferences.edit(commit = true) { putString("API_KEY", value) }

  var RUNNING_BUNDLE_DIR: File?
      get() = preferences.getString("RUNNING_BUNDLE_DIR", null)?.let { File(it).takeIf { f -> f.exists() && f.isDirectory } }
      set(value) = preferences.edit(commit = true) { putString("RUNNING_BUNDLE_DIR", value?.absolutePath) }

  var DEVICE_INFO: DeviceInfo?
    get() = preferences.getString("DEVICE_INFO", null)?.let { Gson().fromJson(it, DeviceInfo::class.java) }
    set(value) = preferences.edit(commit = true) { putString("DEVICE_INFO", Gson().toJson(value)) }

  private var APP_MANIFEST: Manifest? = null

  private var BASE_MANIFEST: Manifest? = null

  var MANIFEST: Manifest
      get() = APP_MANIFEST ?: BASE_MANIFEST!!
      set(value) { APP_MANIFEST = value }

  fun loadManifest(context: Context) {
    if (BASE_MANIFEST == null) {
      val content = context.assets.open("manifest.json").bufferedReader().use { it.readText() }

      BASE_MANIFEST = Gson().fromJson(content, Manifest::class.java)
    }

    if (RUNNING_BUNDLE_DIR != null) {
      val bundleManifestFile = File(RUNNING_BUNDLE_DIR, "manifest.json")

      if (bundleManifestFile.exists()) {
        val manifestContent = bundleManifestFile.bufferedReader().use { it.readText() }

        APP_MANIFEST = try { Gson().fromJson(manifestContent, Manifest::class.java) } catch (e: Exception) { null }
      }
    }
  }

  @SuppressLint("HardwareIds")
  fun getDeviceInfo(resolver: ContentResolver):  DeviceInfo {
    DEVICE_INFO = DeviceInfo(
      androidId = Settings.Secure.getString(
        resolver,
        Settings.Secure.ANDROID_ID
      ),
      manufacturer = Build.MANUFACTURER,
      brand = Build.BRAND,
      model = Build.MODEL,
      androidVersion = Build.VERSION.RELEASE,
      sdkVersion = Build.VERSION.SDK_INT,
      supportedAbis = Build.SUPPORTED_ABIS.toList()
    )

    return DEVICE_INFO!!
  }

}
