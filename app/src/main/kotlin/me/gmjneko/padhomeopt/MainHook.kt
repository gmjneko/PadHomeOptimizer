package me.gmjneko.padhomeopt

import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.graphics.RectF
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method


class MainHook : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != MIUI_HOME_PACKAGE) return

        runCatching { taskViewHeaderOffset(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to hook TaskViewHeader", it) }

        runCatching { hookDockShownSwipeToAppGesture(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to hook dock shown swipe gesture", it) }

        runCatching { reduceRecentsAnimationBounce(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to reduce recents animation bounce", it) }

        runCatching { reduceTaskViewSpringBounce(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to reduce task view spring bounce", it) }

    }

    private fun taskViewHeaderOffset(classLoader: ClassLoader) {
        val taskViewHeader = classLoader.loadClass(
            "com.miui.home.recents.views.TaskViewHeader"
        )
        val onAttachedToWindow = taskViewHeader.getDeclaredMethod("onAttachedToWindow")
        val headerButtonPadding = taskViewHeader
            .getDeclaredField("mHeaderButtonPadding")
            .apply { isAccessible = true }

        hook(onAttachedToWindow)
            .setId("task_view_header_offset")
            .intercept { chain ->
                chain.proceed().also {
                    val view = chain.thisObject as View
                    // 将 dp 转换为 px，保证不同分辨率设备表现一致
                    val offset = HORIZONTAL_OFFSET_DP
                    headerButtonPadding.setInt(view, offset)
                    // 仅修改左右 padding，保留原有的上下 padding
                    view.setPadding(offset, view.paddingTop, offset, view.paddingBottom + 6)
                }
            }
    }

    private fun hookDockShownSwipeToAppGesture(classLoader: ClassLoader) {
        val gestureInputHelper = classLoader.loadClass(
            "com.miui.home.recents.GestureInputHelper"
        )

        // Hook onInputEvent（必须用这个，因为mIsUseNewDock在这里计算）
        val onInputEvent = gestureInputHelper
            .getDeclaredMethod("onInputEvent", InputEvent::class.java)
            .apply { isAccessible = true }

        val gestureModeField = gestureInputHelper
            .getDeclaredField("mGestureMode")
            .apply { isAccessible = true }
        val touchEventTrackerField = gestureInputHelper
            .getDeclaredField("mTouchEventTracker")
            .apply { isAccessible = true }
        val isUseNewDockField = gestureInputHelper
            .getDeclaredField("mIsUseNewDock")
            .apply { isAccessible = true }

        val gestureModeApp = classLoader.loadClass(
            "com.miui.home.recents.GestureModeApp"
        )
        val gestureModeHalfApp = classLoader.loadClass(
            "com.miui.home.recents.GestureModeHalfApp"
        )
        val gestureModeLaptopApp = classLoader.loadClass(
            "com.miui.home.recents.GestureModeLaptopApp"
        )
        val gestureTouchEventTracker = classLoader.loadClass(
            "com.miui.home.recents.GestureTouchEventTracker"
        )
        val getDockController = gestureTouchEventTracker
            .getDeclaredMethod("getDockController")
            .apply { isAccessible = true }

        // 缓存DockController的方法对象，避免每次getMethod
        var cachedIsInAppStateMethod: Method? = null
        var cachedIsDockShowMethod: Method? = null

        hook(onInputEvent)
            .setId("dock_shown_swipe_to_app_gesture")
            .intercept { chain ->
                chain.proceed().also {
                    val event = chain.args.firstOrNull() as? MotionEvent ?: return@also

                    // 早期返回：只处理DOWN事件
                    if (event.actionMasked != MotionEvent.ACTION_DOWN) return@also

                    val thisObject = chain.thisObject
                    val mode = gestureModeField.get(thisObject) ?: return@also

                    // 早期返回：类型检查
                    if (!gestureModeApp.isInstance(mode)) return@also
                    if (gestureModeHalfApp.isInstance(mode) || gestureModeLaptopApp.isInstance(mode)) return@also

                    val tracker = touchEventTrackerField.get(thisObject) ?: return@also
                    val dockController = getDockController.invoke(tracker) ?: return@also

                    // 使用缓存的方法对象，避免重复getMethod
                    val inAppStateMethod = cachedIsInAppStateMethod
                        ?: dockController.javaClass.getMethod("isInAppState")
                            .also { cachedIsInAppStateMethod = it }
                    val inAppState = inAppStateMethod.invoke(dockController) as? Boolean ?: false

                    val dockShowMethod = cachedIsDockShowMethod
                        ?: dockController.javaClass.getMethod("isDockShowInAppOrRecent")
                            .also { cachedIsDockShowMethod = it }
                    val dockShownInApp = dockShowMethod.invoke(dockController) as? Boolean ?: false

                    if (inAppState && dockShownInApp) {
                        isUseNewDockField.setBoolean(thisObject, false)
                    }
                }
            }
    }

    private fun reduceRecentsAnimationBounce(classLoader: ClassLoader) {
        val rectFSpringAnim = Class.forName(
            "com.miui.home.recents.util.RectFSpringAnim",
            false,
            classLoader
        )
        val setAnimParam = rectFSpringAnim
            .getDeclaredMethod(
                "setAnimParam",
                String::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            .apply { isAccessible = true }
        val lastAnimType = rectFSpringAnim
            .getDeclaredField("mLastAminType")
            .apply { isAccessible = true }

        hook(setAnimParam)
            .setId("recents_animation_bounce")
            .intercept { chain ->
                val key = chain.args.getOrNull(0) as? String ?: return@intercept chain.proceed()
                val animType = lastAnimType.get(chain.thisObject)?.toString()

                if (animType != "CLOSE_TO_RECENTS") {
                    return@intercept chain.proceed()
                }

                when (key) {
                    "centerX",
                    "centerY",
                    "Width",
                    "Height" -> {
//                        val originalDamping = chain.args.getOrNull(1) as? Float
                        val response = chain.args.getOrNull(2) as? Float
                            ?: return@intercept chain.proceed()
                        val impulse = chain.args.getOrNull(3) as? Float
                            ?: return@intercept chain.proceed()

                        chain.proceed(
                            arrayOf<Any?>(
                                key,
                                RECENTS_ANIMATION_DAMPING_RATIO,
                                response,
                                impulse
                            )
                        )
                    }

                    else -> chain.proceed()
                }
            }
    }

    private fun reduceTaskViewSpringBounce(classLoader: ClassLoader) {
        val springAnimationUtils = Class.forName(
            "com.miui.home.recents.util.SpringAnimationUtils",
            false,
            classLoader
        )
        val taskView = Class.forName(
            "com.miui.home.recents.views.TaskView",
            false,
            classLoader
        )

        val startTaskViewSpringAnim = springAnimationUtils
            .getDeclaredMethod(
                "startTaskViewSpringAnim",
                taskView,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Runnable::class.java
            )
            .apply { isAccessible = true }

        val startTaskViewThumbnailSpringAnim = springAnimationUtils
            .getDeclaredMethod(
                "startTaskViewThumbnailSpringAnim",
                taskView,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Runnable::class.java,
                RectF::class.java
            )
            .apply { isAccessible = true }

        hook(startTaskViewSpringAnim)
            .setId("task_view_spring_bounce")
            .intercept { chain ->
                val originalDamping = chain.args.getOrNull(7) as? Float
                    ?: return@intercept chain.proceed()
                if (originalDamping >= RECENTS_ANIMATION_DAMPING_RATIO) {
                    return@intercept chain.proceed()
                }

                chain.proceed(
                    arrayOf(
                        chain.args.getOrNull(0),
                        chain.args.getOrNull(1),
                        chain.args.getOrNull(2),
                        chain.args.getOrNull(3),
                        chain.args.getOrNull(4),
                        chain.args.getOrNull(5),
                        chain.args.getOrNull(6),
                        RECENTS_ANIMATION_DAMPING_RATIO,
                        chain.args.getOrNull(8),
                        chain.args.getOrNull(9)
                    )
                )
            }

        hook(startTaskViewThumbnailSpringAnim)
            .setId("task_view_thumbnail_spring_bounce")
            .intercept { chain ->
                val originalDamping = chain.args.getOrNull(3) as? Float
                    ?: return@intercept chain.proceed()
                if (originalDamping >= RECENTS_ANIMATION_DAMPING_RATIO) {
                    return@intercept chain.proceed()
                }

                chain.proceed(
                    arrayOf(
                        chain.args.getOrNull(0),
                        chain.args.getOrNull(1),
                        chain.args.getOrNull(2),
                        RECENTS_ANIMATION_DAMPING_RATIO,
                        chain.args.getOrNull(4),
                        chain.args.getOrNull(5),
                        chain.args.getOrNull(6)
                    )
                )
            }
    }

    private companion object {
        private const val TAG = "miui-home"
        private const val MIUI_HOME_PACKAGE = "com.miui.home"

        /** 左右水平间距（单位：dp） */
        private const val HORIZONTAL_OFFSET_DP = 24

        private const val RECENTS_ANIMATION_DAMPING_RATIO = 0.94f
    }
}
