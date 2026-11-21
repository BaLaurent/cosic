package com.octopusman.cosic.editor

import cosic.ProgramInfo
import cosic.io.initial
import cosic.visual.VisualSession
import cosic.visual.WidgetType

/** What an evaluated program asks the editor to show: its widgets and its sliders, in the coordinates of the text that was evaluated. */
internal class WidgetSeeds(val blocks: List<BlockSeed>, val sliders: List<SliderSeed>) {
    companion object {
        val NONE = WidgetSeeds(emptyList(), emptyList())
    }
}

/**
 * The widgets and sliders of this program as seeds for the editor's anchors. A widget is placed at the end of its call ([cosic.WidgetConfig.to], where the transpiler found it) and is
 * as big as the surface the program's visuals made for it ([cosic.visual.VisualSurface]): a call that never ran made no surface, has nothing to show, and gets no place. A slider
 * has a place only when its first argument is a number literal, which is what moving it rewrites.
 */
internal fun ProgramInfo.toSeeds(): WidgetSeeds {
    val surfaces = (visuals as? VisualSession)?.surfaces.orEmpty().filter { it.widget != null }.associateBy { it.key }
    val blocks = widgets.mapNotNull { widget ->
        val surface = surfaces[widget.widgetId] ?: return@mapNotNull null
        val type = surface.widget?.type ?: WidgetType.ofMethod(widget.type) ?: return@mapNotNull null
        BlockSeed(widget.widgetId, type, widget.to, surface.width.toFloat(), surface.height.toFloat())
    }
    val sliderSeeds = sliders.mapNotNull { slider ->
        if (slider.value == null) null else SliderSeed(slider.id, slider.from, slider.min, slider.max, slider.step, slider.initial)
    }
    return WidgetSeeds(blocks, sliderSeeds)
}
