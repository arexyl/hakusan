package app.hakusan.bench

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.Display
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * The conditions of a block and of each request, which a result is reported
 * with: a changed condition explains a changed time.
 *
 * No value identifies the device unit or its owner: the record holds the model
 * and the build, never a serial number or an account.
 */
internal class Conditions(private val activity: Activity) {
  private val power = activity.getSystemService(PowerManager::class.java)
  private val battery = activity.getSystemService(BatteryManager::class.java)

  /**
   * Requests the display mode of the current resolution that refreshes
   * [refresh] times per second, and returns it.
   *
   * Left alone, a display lowers its refresh rate while nothing changes, and an
   * output that publishes once would then get a longer frame period than an
   * output that publishes every frame. Whether the system honors the request
   * shows only in the frames that a trace records.
   *
   * @throws IllegalStateException when the display has no such mode.
   */
  fun requestDisplayMode(refresh: Int): Display.Mode {
    val display = checkNotNull(activity.display)
    val current = display.mode

    val mode =
      display.supportedModes.firstOrNull {
        it.physicalWidth == current.physicalWidth &&
          it.physicalHeight == current.physicalHeight &&
          it.refreshRate.roundToInt() == refresh
      }

    checkNotNull(mode) { "The display has no mode at $refresh Hz." }

    activity.window.attributes =
      activity.window.attributes.apply {
        preferredDisplayModeId = mode.modeId
        preferredRefreshRate = mode.refreshRate
      }

    return mode
  }

  /** Whether the display runs in [mode] now. */
  fun displays(mode: Display.Mode): Boolean =
    checkNotNull(activity.display).mode.modeId == mode.modeId

  /**
   * Checks that the window holds the whole [viewport].
   *
   * @throws IllegalStateException when the window is smaller.
   */
  fun requireWindow(viewport: IntSize) {
    val bounds = activity.windowManager.currentWindowMetrics.bounds

    check(
      bounds.width() >= viewport.width && bounds.height() >= viewport.height
    ) {
      "The window of ${bounds.width()} by ${bounds.height()} is too small."
    }
  }

  /** The conditions that hold for a whole block, read at its start. */
  fun block(requested: Display.Mode, decodeNice: Int): JSONObject {
    val display = checkNotNull(activity.display)
    val bounds = activity.windowManager.currentWindowMetrics.bounds
    val resolver = activity.contentResolver
    val flags = activity.applicationInfo.flags

    val modes =
      display.supportedModes.map {
        JSONObject()
          .put("width", it.physicalWidth)
          .put("height", it.physicalHeight)
          .put("refresh_hz", it.refreshRate.toDouble())
      }

    return JSONObject()
      .put(
        "os",
        JSONObject()
          .put("sdk", Build.VERSION.SDK_INT)
          .put("release", Build.VERSION.RELEASE)
          .put("build", Build.ID)
          .put("incremental", Build.VERSION.INCREMENTAL)
          .put("security_patch", Build.VERSION.SECURITY_PATCH),
      )
      .put(
        "device",
        JSONObject()
          .put("model", Build.MODEL)
          .put("soc", Build.SOC_MODEL)
          .put("page_size", Os.sysconf(OsConstants._SC_PAGESIZE)),
      )
      .put(
        "harness",
        JSONObject()
          .put("debuggable", flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
          .put("decode_thread", DECODE_THREAD)
          .put("decode_nice", decodeNice),
      )
      .put(
        "display",
        JSONObject()
          .put("window", JSONArray(listOf(bounds.width(), bounds.height())))
          .put("requested_refresh_hz", requested.refreshRate.toDouble())
          .put("refresh_hz", display.refreshRate.toDouble())
          .put("modes", JSONArray(modes))
          .put(
            "brightness",
            Settings.System.getInt(
              resolver,
              Settings.System.SCREEN_BRIGHTNESS,
              -1,
            ),
          )
          .put(
            "brightness_mode",
            Settings.System.getInt(
              resolver,
              Settings.System.SCREEN_BRIGHTNESS_MODE,
              -1,
            ),
          ),
      )
      .put("power_save", power.isPowerSaveMode)
  }

  /**
   * The conditions at one request, read before its request mark.
   *
   * The thermal headroom is null where the device reports none, and when the
   * platform limits the rate of the read.
   */
  fun request(): JSONObject {
    val headroom = power.getThermalHeadroom(0)

    val sticky: Intent? =
      activity.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
      )

    val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)

    return JSONObject()
      .put("thermal_status", power.currentThermalStatus)
      .put(
        "thermal_headroom",
        if (headroom.isNaN()) null else headroom.toDouble(),
      )
      .put(
        "battery_percent",
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
      )
      .put("charging", battery.isCharging)
      .put("battery_decidegrees", temperature ?: -1)
  }

  companion object {
    /** The name of the one thread that runs every decode. */
    const val DECODE_THREAD = "hakusan-bench-decode"
  }
}
