package tv.seekr.previews.core

/**
 * A single preview thumbnail: which sprite sheet to load and the rectangle to crop
 * from it. Coordinates and dimensions are in sheet pixels (already adjusted for the
 * lookup's `scale`).
 *
 * The core artifact returns these raw coordinates so it stays platform-agnostic. The
 * seekr-android artifact turns them into ready-to-draw bitmaps for you.
 */
data class SeekrTile(
    val sheetUrl: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)
