package com.otaclient.data

data class DeviceInfo(
    val androidId: String?,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val supportedAbis: List<String>
)