package cosic

/**
 * SCAFFOLD: public surface only. Step 5 replaces every body in this file.
 */
class CosicEngine(val sampleRate: Int, library: SoundLibrary) {
    fun evaluate(code: String): EvalReport = TODO("Step 5")

    fun play(): Unit = TODO("Step 5")

    fun stop(): Unit = TODO("Step 5")

    fun preview(sound: String, index: Int = 0, note: Double? = null): Unit = TODO("Step 5")

    @Volatile
    var snapshot: EngineSnapshot = EngineSnapshot(sampleRate, false, true, 0.5, 0L, 0.0, 0L, 0, emptyList(), null, emptyList())
        private set

    /** Audio thread only; interleaved stereo, `out.size >= frames * 2`. */
    fun render(out: FloatArray, frames: Int): Unit = TODO("Step 5")

    companion object {
        val SYNTHS: List<String> = listOf("sine", "sawtooth", "square", "triangle", "white", "pink", "brown")
        val DRUMS: List<String> = listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr")
    }
}
