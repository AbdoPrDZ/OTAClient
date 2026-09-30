package com.otaclient.utils

import android.content.Context
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.otaclient.BuildConfig
import com.otaclient.data.APIResponse
import com.otaclient.data.AppInfo
import com.otaclient.data.Manifest
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.gson.gson
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo

data class CheckUpdate(
  val haveUpdate: Boolean,
  val haveVersionUpdate: Boolean,
  val haveBundleUpdate: Boolean,
  val appInfo: AppInfo?,
)

data class DownloadBundle(
  val downloadDir: File,
  val manifest: Manifest,
  val bundleFile: File,
  val manifestFile: File,
)

data class DownloadApp(
  val appFile: File,
  val appFileUri: android.net.Uri,
)


class OTAClient private constructor() {

  companion object {
    private val API_URL get() = "${AppStorage.API_BASE_URL}/ota-client/${AppStorage.API_VERSION}"

    private val DEVICE_INFO: String get() {
      val deviceInfo = AppStorage.DEVICE_INFO!!

      return "did=${deviceInfo.androidId};mf=${deviceInfo.manufacturer};br=${deviceInfo.brand};mdl=${deviceInfo.model};av=${deviceInfo.androidVersion};sdv=${deviceInfo.sdkVersion}"
    }

    @Volatile
    private var _instance: OTAClient? = null

    val instance: OTAClient get() {
      return _instance ?: synchronized(this) {
        _instance ?: OTAClient().also { _instance = it }
      }
    }
  }

  val client = HttpClient(CIO) {
    install(ContentNegotiation) {
      gson()
    }

    install(HttpTimeout) {
      requestTimeoutMillis = 5 * 60 * 1000L      // 5 minutes
      connectTimeoutMillis = 30 * 1000L           // 30 seconds
      socketTimeoutMillis = 5 * 60 * 1000L        // 5 minutes
    }
  }

  suspend fun healthCheck(): Boolean {
    try {
      val response = client.get("${API_URL}/health") {
        headers {
          append("Accept", "application/json")
          append("API-KEY", AppStorage.API_KEY)
          append("X-Device-Info", DEVICE_INFO)
        }
      }

      return response.status.isSuccess()
    } catch (e: Exception) {
      println("Health check error: ${e.message}")
      return false
    }
  }

  private suspend fun appInfo(): AppInfo? {
    try {
      println("DEVICE_INFO: $DEVICE_INFO")

      val response = client.submitForm(
        url = "$API_URL/app/info",
        formParameters = Parameters.build {
          append("package", BuildConfig.APPLICATION_ID)
          append("version", BuildConfig.VERSION_NAME)
          append("bundle", AppStorage.MANIFEST.version)
        }
      ) {
        headers {
          append("Accept", "application/json")
          append("API-KEY", AppStorage.API_KEY)
          append("X-Device-Info", DEVICE_INFO)
        }
      }

      println("appInfo response: ${response.bodyAsText()}")

      if (response.status.isSuccess()) {
        return response.body<APIResponse<AppInfo>>().data
      }

    } catch (e: Exception) {
      println("App Info check error: ${e.message}")
    }

    return null
  }

  suspend fun checkUpdate(): CheckUpdate {
    val appInfo = appInfo()

    var haveVersionUpdate = false
    var haveBundleUpdate = false

    if (appInfo != null) {
      println(appInfo.toString())
      if (appInfo.availableUpdates.version != null) {
        println("New version available: ${appInfo.availableUpdates.version.name}")
        haveVersionUpdate = true
      }
      if (appInfo.availableUpdates.bundle != null) {
        println("New bundle available: ${appInfo.availableUpdates.bundle.name}")
        haveBundleUpdate = true
      }
    } else {
      println("Failed to get device info")
    }

    return CheckUpdate(
      haveUpdate = haveVersionUpdate || haveBundleUpdate,
      haveVersionUpdate = haveVersionUpdate,
      haveBundleUpdate = haveBundleUpdate,
      appInfo = appInfo
    )
  }

  suspend fun downloadBundle(
    context: Context,
    check: CheckUpdate,
    onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> }
  ): DownloadBundle {
    val newUpdatePath = "${context.filesDir}/new-update-${System.currentTimeMillis()}"

    val newUpdateDir = File(newUpdatePath)
    val bundleFile = File("$newUpdatePath/archive.tar.gz")
    val extractDir = File("$newUpdatePath/extracted")

    try {
      newUpdateDir.mkdirs()

      client.downloadFile(
        "$API_URL/app/update/bundle/${check.appInfo!!.availableUpdates.bundle!!.id}",
        bundleFile,
        onProgress = onProgress,
        block = {
          headers {
            append("API-KEY", AppStorage.API_KEY)
            append("X-Device-Info", DEVICE_INFO)
            append("Authorization", "Bearer ${check.appInfo.session}")
          }
        }
      )

      if (!bundleFile.exists()) {
        throw Exception("Failed to download bundle")
      }

      println("Bundle downloaded to: ${bundleFile.absolutePath}, size: ${bundleFile.length() / 1024} Kb")

      extractDir.mkdirs()

      extractTarGz(bundleFile, extractDir)

      val listDir = extractDir.listFiles()?.toList() ?: emptyList()
      listDir.forEach { file ->
        println("Extracted file: ${file.absolutePath}")
      }

      val manifestFile = File("$extractDir/manifest.json")
      if (!manifestFile.exists()) {
        throw Exception("Manifest file not found")
      }

      val manifest = Gson().fromJson(manifestFile.reader(), Manifest::class.java)
      println("Manifest: $manifest")

      val bundleFile = File("$extractDir/${manifest.bundle}")
      if (!bundleFile.exists()) {
        throw Exception("Bundle file not found")
      }

      val finalDirPath = "${context.filesDir}/${manifest.runtimeVersion}-${manifest.version}"
      val finalBundlePath = "$finalDirPath/${manifest.bundle}"
      val finalManifestPath = "$finalDirPath/manifest.json"
      val finalDir = File(finalDirPath)
      val finalBundleFile = File(finalBundlePath)
      val finalManifestFile = File(finalManifestPath)

      finalDir.mkdirs()
      bundleFile.copyTo(finalBundleFile, overwrite = true)
      manifestFile.copyTo(finalManifestFile, overwrite = true)

      if (!finalDir.exists() || !finalDir.isDirectory || !finalBundleFile.exists() || !finalManifestFile.exists()) {
        throw Exception("Failed to create final bundle structure")
      }

      return DownloadBundle(finalDir, manifest, finalBundleFile, finalManifestFile)
    } catch (e: Exception) {
      newUpdateDir.deleteRecursively()
      throw Exception("Download error: ${e.message}")
    }
  }

  // suspend fun downloadApp(context: Context, check: CheckUpdate): DownloadApp {
  fun downloadApp(context: Context, check: CheckUpdate): String {
    return "$API_URL/app/update/version/${check.appInfo!!.availableUpdates.version!!.id}?did=${AppStorage.DEVICE_INFO!!.androidId}&token=${check.appInfo.session}"
    // val newUpdatePath = "${context.filesDir}/new-app-${System.currentTimeMillis()}"
    // val newUpdateDir = File(newUpdatePath)
    // val appFile = File("$newUpdatePath/app-release.apk")

    // try {
    //   newUpdateDir.mkdirs()

    //   client.downloadFile("$API_URL/app/update/version/${check.appInfo!!.availableUpdates.version!!.id}", appFile) {
    //     headers {
    //       append("API-KEY", AppStorage.API_KEY)
    //       append("X-Device-Info", DEVICE_INFO)
    //       append("Authorization", "Bearer ${check.appInfo.session}")
    //     }
    //   }

    //   if (!appFile.exists()) {
    //     throw Exception("Failed to download app")
    //   }

    //   println("App downloaded to: ${appFile.absolutePath}, size: ${appFile.length() / 1024} Kb")

    //   val appFileUri = FileProvider.getUriForFile(
    //     context,
    //     "${context.packageName}.fileprovider",
    //     appFile
    //   )

    //   return DownloadApp(appFile, appFileUri)
    // } catch (e: Exception) {
    //   newUpdateDir.deleteRecursively()
    //   throw Exception("Download error: ${e.message}")
    // }
  }

}

suspend fun HttpClient.downloadFile(
  fileUrl: String,
  file: File,
  block: HttpRequestBuilder.() -> Unit = {},
  onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> }
) {
this.prepareGet(fileUrl, block).execute { response ->
    if (response.status.isSuccess()) {
      val channel: ByteReadChannel = response.bodyAsChannel()
      val total = response.headers["Content-Length"]?.toLongOrNull()
      var downloaded = 0L

      file.outputStream().use { outputStream ->
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        while (true) {
          buffer.clear()
          val read = channel.readAvailable(buffer)
          if (read == -1) break
          outputStream.write(buffer.array(), 0, read)
          downloaded += read
          onProgress(downloaded, total)
        }
      }
    } else {
      throw Exception("Failed to download file: ${response.status}")
    }
  }
}

fun extractTarGz(archiveFile: File, outputDir: File) {
  // Ensure the output directory exists
  if (!outputDir.exists()) {
      outputDir.mkdirs()
  }

  TarArchiveInputStream(GzipCompressorInputStream(FileInputStream(archiveFile))).use { tarInput ->
    var entry = tarInput.nextEntry

    while (entry != null) {
        val outputFile = File(outputDir, entry.name)

        if (entry.isDirectory) {
            outputFile.mkdirs()
        } else {
            // Ensure parent directories exist for the file
            outputFile.parentFile?.mkdirs()
            
            // Write data to the local disk file
            outputFile.outputStream().use { outputStream ->
                tarInput.copyTo(outputStream)
            }
        }
        entry = tarInput.nextEntry
    }
  }
}
