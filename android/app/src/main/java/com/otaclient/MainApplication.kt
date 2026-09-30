package com.otaclient

import android.app.Application
import android.content.Context
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.defaults.DefaultReactNativeHost
import com.otaclient.BuildConfig
import com.otaclient.utils.AppStorage
import java.io.File

import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class MainApplication : Application(), ReactApplication {

  private val rnHost = object : DefaultReactNativeHost(this) {

    override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

    override fun getPackages() = PackageList(this).packages.apply {
      // Packages that cannot be autolinked yet can be added manually here, for example:
      // add(MyReactNativePackage())
    }

    override fun getJSMainModuleName(): String = "index"

    override fun getJSBundleFile(): String? {
      return AppStorage.RUNNING_BUNDLE_DIR?.let {
        val appVersion = BuildConfig.VERSION_NAME
        val bundleAppVersion = AppStorage.MANIFEST.runtimeVersion

        if (appVersion == bundleAppVersion) {
          File(it, AppStorage.MANIFEST.bundle).takeIf { f -> f.exists() }?.absolutePath
        } else {
          null
        }
      }
    }

  } 

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      rnHost,
     )
   }

  override fun onCreate() {
    super.onCreate()

    AppStorage.init(this)

    loadReactNative(this)
  }

}
