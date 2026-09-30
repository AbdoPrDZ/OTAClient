package com.otaclient.data


data class UpdateInfo(
  val id: String,
  val name: String,
)

data class AppInfoAvailableUpdates(
  val version: UpdateInfo?,
  val bundle: UpdateInfo?,
)

data class AppInfo(
  val appId: String,
  val versionId: String,
  val bundleId: String,
  val availableUpdates: AppInfoAvailableUpdates,
  val session: String,
)
