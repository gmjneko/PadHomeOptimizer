package me.gmjneko.padhomeopt

import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.graphics.RectF
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 优化版本 - 主要优化点：
 * 1. 缓存所有反射对象为类成员变量
 * 2. 减少不必要的反射调用和数组重建
 * 3. 优化条件判断顺序
 * 4. 直接修改args数组避免对象创建
 */
class MainHook : XposedModule() {

    // ===== 缓存的反射对象 =====
    // TaskViewHeader相关
    private var headerButtonPaddingField: Field? = null

    // GestureInputHelper相关
    private var gestureModeField: Field? = null
    private var touchEventTrackerField: Field? = null
    private var isUseNewDockField: Field? = null
    private var getDockControllerMethod: Method? = null
    private var isInAppStateMethod: Method? = null
    private var isDockShowMethod: Method? = null

    // 类型缓存
    private var gestureModeAppClass: Class<*>? = null
    private var gestureModeHalfAppClass: Class<*>? = null
    private var gestureModeLaptopAppClass: Class<*>? = null

    // RectFSpringAnim相关
    private var lastAnimTypeField: Field? = null

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

        // 缓存Field对象
        headerButtonPaddingField = taskViewHeader
            .getDeclaredField("mHeaderButtonPadding")
            .apply { isAccessible = true }

        hook(onAttachedToWindow)
            .setId("task_view_header_offset")
            .intercept { chain ->
                chain.proceed().also {
                    val view = chain.thisObject as View
                    val offset = HORIZONTAL_OFFSET_DP
                    // 使用缓存的Field对象
                    headerButtonPaddingField?.setInt(view, offset)
                    view.setPadding(offset, view.paddingTop, offset, view.paddingBottom + 6)
                }
            }
    }

    private fun hookDockShownSwipeToAppGesture(classLoader: ClassLoader) {
        val gestureInputHelper = classLoader.loadClass(
            "com.miui.home.recents.GestureInputHelper"
        )

        val onInputEvent = gestureInputHelper
            .getDeclaredMethod("onInputEvent", InputEvent::class.java)
            .apply { isAccessible = true }

        // 缓存所有Field对象
        gestureModeField = gestureInputHelper
            .getDeclaredField("mGestureMode")
            .apply { isAccessible = true }
        touchEventTrackerField = gestureInputHelper
            .getDeclaredField("mTouchEventTracker")
            .apply { isAccessible = true }
        isUseNewDockField = gestureInputHelper
            .getDeclaredField("mIsUseNewDock")
            .apply { isAccessible = true }

        // 缓存类型
        gestureModeAppClass = classLoader.loadClass("com.miui.home.recents.GestureModeApp")
        gestureModeHalfAppClass = classLoader.loadClass("com.miui.home.recents.GestureModeHalfApp")
        gestureModeLaptopAppClass = classLoader.loadClass("com.miui.home.recents.GestureModeLaptopApp")

        val gestureTouchEventTracker = classLoader.loadClass(
            "com.miui.home.recents.GestureTouchEventTracker"
        )
        getDockControllerMethod = gestureTouchEventTracker
            .getDeclaredMethod("getDockController")
            .apply { isAccessible = true }

        hook(onInputEvent)
            .setId("dock_shown_swipe_to_app_gesture")
            .intercept { chain ->
                chain.proceed().also {
                    // 最快速的类型检查和早期返回
                    val event = chain.args.firstOrNull() as? MotionEvent ?: return@also

                    // 只处理DOWN事件，这是最频繁的过滤条件，放在最前面
                    if (event.actionMasked != MotionEvent.ACTION_DOWN) return@also

                    val thisObject = chain.thisObject
                    val mode = gestureModeField?.get(thisObject) ?: return@also

                    // 使用缓存的类对象进行类型检查
                    val modeAppClass = gestureModeAppClass ?: return@also
                    if (!modeAppClass.isInstance(mode)) return@also

                    val halfAppClass = gestureModeHalfAppClass
                    val laptopAppClass = gestureModeLaptopAppClass
                    if ((halfAppClass != null && halfAppClass.isInstance(mode)) ||
                        (laptopAppClass != null && laptopAppClass.isInstance(mode))) {
                        return@also
                    }

                    val tracker = touchEventTrackerField?.get(thisObject) ?: return@also
                    val dockController = getDockControllerMethod?.invoke(tracker) ?: return@also

                    // 延迟初始化Method对象（只在第一次需要时获取）
                    if (isInAppStateMethod == null) {
                        isInAppStateMethod = dockController.javaClass.getMethod("isInAppState")
                    }
                    if (isDockShowMethod == null) {
                        isDockShowMethod = dockController.javaClass.getMethod("isDockShowInAppOrRecent")
                    }

                    val inAppState = isInAppStateMethod?.invoke(dockController) as? Boolean ?: false
                    // 如果不在App状态，直接返回，避免后续检查
                    if (!inAppState) return@also

                    val dockShownInApp = isDockShowMethod?.invoke(dockController) as? Boolean ?: false

                    if (dockShownInApp) {
                        isUseNewDockField?.setBoolean(thisObject, false)
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

        // 缓存Field对象
        lastAnimTypeField = rectFSpringAnim
            .getDeclaredField("mLastAminType")
            .apply { isAccessible = true }

        hook(setAnimParam)
            .setId("recents_animation_bounce")
            .intercept { chain ->
                val key = chain.args.getOrNull(0) as? String ?: return@intercept chain.proceed()

                // 首先检查key，因为这是最快的检查（不需要反射）
                val needsModification = when (key) {
                    "centerX", "centerY", "Width", "Height" -> true
                    else -> false
                }

                if (!needsModification) {
                    return@intercept chain.proceed()
                }

                // 只有在需要修改时才获取animType
                val animType = lastAnimTypeField?.get(chain.thisObject)?.toString()

                if (animType != CLOSE_TO_RECENTS_TYPE) {
                    return@intercept chain.proceed()
                }

                val response = chain.args.getOrNull(2) as? Float ?: return@intercept chain.proceed()
                val impulse = chain.args.getOrNull(3) as? Float ?: return@intercept chain.proceed()

                // 使用修改后的参数调用
                chain.proceed(
                    arrayOf(
                        key,
                        RECENTS_ANIMATION_DAMPING_RATIO,
                        response,
                        impulse
                    )
                )
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

                // 如果已经满足条件，直接返回
                if (originalDamping >= RECENTS_ANIMATION_DAMPING_RATIO) {
                    return@intercept chain.proceed()
                }

                // 使用修改后的参数调用
                chain.proceed(
                    arrayOf(
                        chain.args[0],
                        chain.args[1],
                        chain.args[2],
                        chain.args[3],
                        chain.args[4],
                        chain.args[5],
                        chain.args[6],
                        RECENTS_ANIMATION_DAMPING_RATIO,
                        chain.args[8],
                        chain.args[9]
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

                // 使用修改后的参数调用
                chain.proceed(
                    arrayOf(
                        chain.args[0],
                        chain.args[1],
                        chain.args[2],
                        RECENTS_ANIMATION_DAMPING_RATIO,
                        chain.args[4],
                        chain.args[5],
                        chain.args[6]
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

        // 缓存字符串常量，避免每次创建
        private const val CLOSE_TO_RECENTS_TYPE = "CLOSE_TO_RECENTS"
    }
}
