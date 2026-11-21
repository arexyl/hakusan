package app.hakusan.bench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The harness viewport: a box of exactly [size] physical pixels at the top left
 * of the window, which shows the frames of [consumer].
 *
 * An output is drawn at the width of the viewport with unchanged aspect, with
 * bilinear filtering for every backend. Only its valid rows are drawn, so the
 * background shows where coverage is missing.
 */
@Composable
internal fun Viewport(consumer: DisplayConsumer, size: IntSize) {
  LaunchedEffect(consumer) { consumer.present() }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    Spacer(
      Modifier.layout { measurable, _ ->
          val placeable =
            measurable.measure(Constraints.fixed(size.width, size.height))

          layout(size.width, size.height) { placeable.place(0, 0) }
        }
        .drawBehind { draw(consumer) }
    )
  }
}

private fun DrawScope.draw(consumer: DisplayConsumer) {
  val frame = consumer.shown

  Marks.section(Marks.draw(frame)) {
    if (frame is Publication) {
      draw(frame)
    }
  }

  consumer.onDrawn(frame)
}

private fun DrawScope.draw(publication: Publication) {
  val bitmap = publication.bitmap
  val scale = size.width / bitmap.width

  // The filter is not confined to the source rows, so a destination row is
  // drawn only when its center maps at or above the center of the last valid
  // row. A complete output keeps every row.
  val bottom =
    if (publication.rows == bitmap.height) {
      size.height
    } else {
      floor((publication.rows - 0.5f) * scale + 0.5f)
    }

  clipRect(bottom = bottom) {
    drawImage(
      image = publication.image,
      dstSize =
        IntSize(size.width.roundToInt(), (bitmap.height * scale).roundToInt()),
      filterQuality = FilterQuality.Low,
    )
  }
}
