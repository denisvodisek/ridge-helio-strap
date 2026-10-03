package app.strap.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.fill.CalendarBlank as CalendarBlankFill
import com.adamglin.phosphoricons.fill.Moon as MoonFill
import com.adamglin.phosphoricons.fill.NotePencil as NotePencilFill
import com.adamglin.phosphoricons.fill.PersonSimpleRun as PersonSimpleRunFill
import com.adamglin.phosphoricons.fill.Watch as WatchFill
import com.adamglin.phosphoricons.light.ArrowLeft
import com.adamglin.phosphoricons.light.Barbell
import com.adamglin.phosphoricons.light.Bicycle
import com.adamglin.phosphoricons.light.Bug
import com.adamglin.phosphoricons.light.Cake
import com.adamglin.phosphoricons.light.Calendar
import com.adamglin.phosphoricons.light.CalendarBlank
import com.adamglin.phosphoricons.light.CaretRight
import com.adamglin.phosphoricons.light.CloudArrowUp
import com.adamglin.phosphoricons.light.CloudCheck
import com.adamglin.phosphoricons.light.GearSix
import com.adamglin.phosphoricons.light.Info
import com.adamglin.phosphoricons.light.Lightning
import com.adamglin.phosphoricons.light.Moon
import com.adamglin.phosphoricons.light.NotePencil
import com.adamglin.phosphoricons.light.PaperPlaneRight
import com.adamglin.phosphoricons.light.PersonSimpleRun
import com.adamglin.phosphoricons.light.PersonSimpleTaiChi
import com.adamglin.phosphoricons.light.PersonSimpleWalk
import com.adamglin.phosphoricons.light.Scales
import com.adamglin.phosphoricons.light.Sneaker
import com.adamglin.phosphoricons.light.Sparkle
import com.adamglin.phosphoricons.light.Stairs
import com.adamglin.phosphoricons.light.Stop
import com.adamglin.phosphoricons.light.SwimmingPool
import com.adamglin.phosphoricons.light.TennisBall
import com.adamglin.phosphoricons.light.Trash
import com.adamglin.phosphoricons.light.User
import com.adamglin.phosphoricons.light.ArrowsClockwise
import com.adamglin.phosphoricons.light.Watch
import com.adamglin.phosphoricons.light.X

/**
 * Every icon the app draws, in one place (DESIGN v2): Phosphor Light, thin and even, with Fill
 * for the selected tab. Swapping the set is a change here, not a hunt through screens.
 */
object RidgeIcons {
    val today: ImageVector get() = PhosphorIcons.Light.CalendarBlank
    val todayOn: ImageVector get() = PhosphorIcons.Fill.CalendarBlankFill
    val sleep: ImageVector get() = PhosphorIcons.Light.Moon
    val sleepOn: ImageVector get() = PhosphorIcons.Fill.MoonFill
    val activity: ImageVector get() = PhosphorIcons.Light.PersonSimpleRun
    val activityOn: ImageVector get() = PhosphorIcons.Fill.PersonSimpleRunFill
    val journal: ImageVector get() = PhosphorIcons.Light.NotePencil
    val journalOn: ImageVector get() = PhosphorIcons.Fill.NotePencilFill
    val strap: ImageVector get() = PhosphorIcons.Light.Watch
    val strapOn: ImageVector get() = PhosphorIcons.Fill.WatchFill

    val back: ImageVector get() = PhosphorIcons.Light.ArrowLeft
    val settings: ImageVector get() = PhosphorIcons.Light.GearSix
    val calendar: ImageVector get() = PhosphorIcons.Light.Calendar
    val info: ImageVector get() = PhosphorIcons.Light.Info
    val ask: ImageVector get() = PhosphorIcons.Light.Sparkle
    val send: ImageVector get() = PhosphorIcons.Light.PaperPlaneRight
    val chevron: ImageVector get() = PhosphorIcons.Light.CaretRight
    val close: ImageVector get() = PhosphorIcons.Light.X
    val stop: ImageVector get() = PhosphorIcons.Light.Stop
    val delete: ImageVector get() = PhosphorIcons.Light.Trash
    val profile: ImageVector get() = PhosphorIcons.Light.User
    val birthday: ImageVector get() = PhosphorIcons.Light.Cake
    val weight: ImageVector get() = PhosphorIcons.Light.Scales
    val sync: ImageVector get() = PhosphorIcons.Light.ArrowsClockwise
    val uploaded: ImageVector get() = PhosphorIcons.Light.CloudCheck
    val uploading: ImageVector get() = PhosphorIcons.Light.CloudArrowUp
    val diagnostics: ImageVector get() = PhosphorIcons.Light.Bug
    val workout: ImageVector get() = PhosphorIcons.Light.Barbell

    val tennis: ImageVector get() = PhosphorIcons.Light.TennisBall
    val treadmill: ImageVector get() = PhosphorIcons.Light.Sneaker
    val stairs: ImageVector get() = PhosphorIcons.Light.Stairs
    val run: ImageVector get() = PhosphorIcons.Light.PersonSimpleRun
    val walk: ImageVector get() = PhosphorIcons.Light.PersonSimpleWalk
    val ride: ImageVector get() = PhosphorIcons.Light.Bicycle
    val gym: ImageVector get() = PhosphorIcons.Light.Barbell
    val swim: ImageVector get() = PhosphorIcons.Light.SwimmingPool
    val yoga: ImageVector get() = PhosphorIcons.Light.PersonSimpleTaiChi
    val other: ImageVector get() = PhosphorIcons.Light.Lightning
}
