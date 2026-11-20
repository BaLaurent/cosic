package cosic.io

import java.util.concurrent.ConcurrentHashMap

/**
 * The values of the sliders of the program being played, by the id the transpiler gave them (`slider_<offset of the first argument>`).
 * `sliderWithID` stores the value written in the code when the program is evaluated and reads the slider's value whenever its pattern is
 * queried; the editor sets it when the user moves the slider (`sliderValues` of slider.mjs). Safe to use from any thread.
 */
class SliderBank {
    private val values = ConcurrentHashMap<String, Any>()

    fun set(id: String, value: Any) {
        values[id] = value
    }

    fun get(id: String): Any? = values[id]
}
