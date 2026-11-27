// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import android.view.Display
import android.widget.Toast
import java.text.Collator

data class Cv10AppEntry(
    val component: ComponentName,
    val label: String,
    val isXr: Boolean,
    val category: String,
)

object Cv10AppCatalog {
    private const val IMMERSIVE_HMD = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"
    private const val OPENXR_RUNTIME_SERVICE = "org.khronos.openxr.OpenXRRuntimeService"
    private const val OPENXR_PERMISSION = "org.khronos.openxr.permission.OPENXR"
    private const val OPENXR_SYSTEM_PERMISSION = "org.khronos.openxr.permission.OPENXR_SYSTEM"

    fun load(context: Context, immersiveOnly: Boolean = false): List<Cv10AppEntry> {
        val pm = context.packageManager
        val immersiveApps = findActivities(context, IMMERSIVE_HMD)
        val immersiveEntries = immersiveApps.map { (component, info) ->
            Cv10AppEntry(component, info.loadLabel(pm).toString(), true, IMMERSIVE_HMD)
        }
        val entries = if (immersiveOnly) immersiveEntries else immersiveEntries + phoneEntries(context, immersiveApps)
        val collator = Collator.getInstance()
        return entries.sortedWith(compareBy(collator) { it.label })
    }

    private fun phoneEntries(context: Context, immersiveApps: Map<ComponentName, ResolveInfo>): List<Cv10AppEntry> {
        val pm = context.packageManager
        val immersivePackages = immersiveApps.keys.mapTo(hashSetOf()) { it.packageName }
        // Runtime packages can inherit the OpenXR permissions from the loader in their settings app.
        val runtimePackages = pm.queryIntentServices(Intent(OPENXR_RUNTIME_SERVICE), 0)
            .mapTo(hashSetOf()) { it.serviceInfo.packageName }
        val usesOpenXr = mutableMapOf<String, Boolean>()
        return findActivities(context, Intent.CATEGORY_LAUNCHER)
            .filterKeys { it.packageName !in immersivePackages }
            .map { (component, info) ->
                val isXr = component.packageName !in runtimePackages && usesOpenXr.getOrPut(component.packageName) {
                    requestsOpenXrPermission(pm, component.packageName)
                }
                Cv10AppEntry(component, info.loadLabel(pm).toString(), isXr, Intent.CATEGORY_LAUNCHER)
            }
    }

    private fun requestsOpenXrPermission(pm: PackageManager, packageName: String): Boolean = try {
        pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            .any { it == OPENXR_PERMISSION || it == OPENXR_SYSTEM_PERMISSION }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun findActivities(context: Context, category: String): Map<ComponentName, ResolveInfo> {
        return context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .filter { info ->
                val activity = info.activityInfo
                activity.exported && activity.packageName != context.packageName &&
                    (activity.permission == null ||
                        context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
            }
            .associateBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    fun launch(activity: Activity, entry: Cv10AppEntry, displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(entry.category)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        try {
            activity.startActivity(intent, options.toBundle())
            return true
        } catch (e: ActivityNotFoundException) {
            Log.w("Cv10Launcher", "Activity disappeared: ${entry.component}", e)
        } catch (e: SecurityException) {
            Log.w("Cv10Launcher", "Cannot open activity: ${entry.component}", e)
        }
        Toast.makeText(activity, activity.getString(R.string.cv10_app_launch_failed, entry.label), Toast.LENGTH_SHORT).show()
        return false
    }
}
