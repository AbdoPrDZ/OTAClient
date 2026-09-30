package com.otaclient.data

data class APIResponse<T>(
  val success: Boolean,
  val message: String?,
  val data: T?
)
