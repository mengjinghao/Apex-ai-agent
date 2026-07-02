package com.apex.util
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.apex.ui.main.MainActivity

/** * 应用图标切换管理器，通过启用/禁用 launcher alias 实现图标切换�?*/
object AppIconManager {
    enum class AppIconType {
        DEFAULT,        SIMPLE
}
private val mainActivityClassName = MainActivity::
class.java.name    private val ideLaunchAliasClassName = "${
mainActivityClassName
}
DefaultAlias"    private val defaultLauncherAliasClassName = "${
mainActivityClassName
}
DefaultLauncherAlias"    private val simpleLauncherAliasClassName = "${
mainActivityClassName
}
SimpleAlias"    fun getCurrentIconType(context: Context): AppIconType {
        return if (isAliasEnabled(context, simpleLauncherAliasClassName, defaultEnabled = false)) {
            AppIconType.SIMPLE
}
else {
            AppIconType.DEFAULT
}
}


    /**     * 修复组件状态，避免 IDE 启动入口被禁用导致无法从 Android Studio 拉起应用�?    */    fun ensureComponentState(context: Context): Boolean {
        val packageManager = context.packageManager        return runCatching {
            setAliasEnabled(packageManager, context, ideLaunchAliasClassName, true)            val simpleEnabled = isAliasEnabled(context, simpleLauncherAliasClassName, defaultEnabled = false)            val defaultLauncherEnabled = isAliasEnabled(                context,                defaultLauncherAliasClassName,                defaultEnabled = true            )            if (simpleEnabled && defaultLauncherEnabled) {
                setAliasEnabled(packageManager, context, defaultLauncherAliasClassName, false)
}
else if (!simpleEnabled && !defaultLauncherEnabled) {
                setAliasEnabled(packageManager, context, defaultLauncherAliasClassName, true)
}
}.isSuccess
}
fun switchIcon(context: Context, target: AppIconType): Boolean {
        val packageManager = context.packageManager        return runCatching {
            setAliasEnabled(packageManager, context, ideLaunchAliasClassName, true)            when (target) {
                AppIconType.DEFAULT -> {
                    setAliasEnabled(packageManager, context, defaultLauncherAliasClassName, true)                    setAliasEnabled(packageManager, context, simpleLauncherAliasClassName, false)
}
AppIconType.SIMPLE -> {
                    setAliasEnabled(packageManager, context, defaultLauncherAliasClassName, false)                    setAliasEnabled(packageManager, context, simpleLauncherAliasClassName, true)
}
}

}
.isSuccess
}
private fun setAliasEnabled(        packageManager: PackageManager,        context: Context,        className: String,        enabled: Boolean    ) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
}
else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}
packageManager.setComponentEnabledSetting(            ComponentName(context, className),            state,            PackageManager.DONT_KILL_APP        )
}
private fun isAliasEnabled(context: Context, className: String, defaultEnabled: Boolean): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(ComponentName(context, className))        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled            else -> defaultEnabled
}
}

}