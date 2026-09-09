package app.hakusan

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRuntimeAndroidTest {
  @Test
  fun loadsPackagedNativeCodeIn64BitProcess() {
    assertTrue(Process.is64Bit())

    System.loadLibrary("androidx.graphics.path")
  }
}
