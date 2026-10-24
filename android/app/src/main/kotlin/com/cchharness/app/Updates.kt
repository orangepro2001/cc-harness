package com.cchharness.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cchharness.core.Release
import java.io.File

/** The installed build, as reported by the package manager. */
data class AppVersion(val code: Int, val name: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val done: Long, val total: Long) : UpdateState

    /** Downloaded and checksum-verified, waiting for the system installer. */
    data class Ready(val release: Release, val file: File) : UpdateState

    /** Android still has to be told this app may install packages; install again afterwards. */
    data class NeedsPermission(val release: Release, val file: File) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

enum class InstallResult { Started, NeedsPermission }

interface ApkInstaller {
    fun install(apk: File): InstallResult
}

/** Hands the APK to Android's package installer (the user confirms the update there). */
class PackageApkInstaller(private val context: Context) : ApkInstaller {
    override fun install(apk: File): InstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return InstallResult.NeedsPermission
        }
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return InstallResult.Started
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        fun authority(context: Context) = "${context.packageName}.updates"

        /** Downloaded updates live here (see res/xml/update_paths.xml). */
        fun updateDir(context: Context) = File(context.cacheDir, "updates")
    }
}
