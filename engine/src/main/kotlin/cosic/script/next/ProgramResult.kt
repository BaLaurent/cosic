package cosic.script.next

import cosic.SourceRange
import cosic.SliderConfig
import cosic.WidgetConfig
import cosic.pattern.Pattern

/**
 * What evaluating a program gives: the pattern to play, as the program made it (its haps are whatever the program put
 * in them: whether they can sound is for the scheduler to find out), and the tempo in cycles per second the program, or a
 * program before it, set. [miniLocations], [widgets] and [sliders] are what the transpiler found: where the atoms of the
 * mini-notation strings are (for highlighting), and the calls that make widgets and sliders.
 */
internal class ProgramResult(
    val pattern: Pattern,
    val cps: Double,
    val miniLocations: List<SourceRange>,
    val widgets: List<WidgetConfig>,
    val sliders: List<SliderConfig>,
)
