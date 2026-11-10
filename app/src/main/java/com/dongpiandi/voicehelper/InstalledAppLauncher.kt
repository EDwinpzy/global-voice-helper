package com.dongpiandi.voicehelper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** Opens any launcher or Leanback app by its visible label or a known alias. */
internal object InstalledAppLauncher {
    private const val TAG = "VoiceHelperAppLaunch"

    fun open(
        context: Context,
        requestedName: String,
    ): Boolean {
        val app = find(context, requestedName) ?: return false
        val packageManager = context.packageManager
        val intent =
            packageManager.getLaunchIntentForPackage(app.packageName)
                ?: app.componentName?.let { component ->
                    Intent(Intent.ACTION_MAIN).apply {
                        this.component = ComponentName.unflattenFromString(component)
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                }
                ?: return false

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "app launched package=${app.packageName}")
            true
        } catch (error: Exception) {
            Log.w(TAG, "app launch failed package=${app.packageName} type=${error.javaClass.simpleName}")
            false
        }
    }

    internal fun find(
        context: Context,
        requestedName: String,
    ): LaunchableApp? {
        val packageManager = context.packageManager
        val launcherFilters =
            listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            )
        val apps =
            launcherFilters
                .flatMap { intent ->
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                }
                .mapNotNull { activity ->
                    val info = activity.activityInfo ?: return@mapNotNull null
                    val appInfo = info.applicationInfo ?: return@mapNotNull null
                    LaunchableApp(
                        packageName = info.packageName,
                        label = packageManager.getApplicationLabel(appInfo).toString(),
                        componentName = "${info.packageName}/${info.name}",
                    )
                }
                .filterNot { it.packageName == context.packageName }
        return AppLaunchMatcher.find(apps, requestedName)
    }
}
