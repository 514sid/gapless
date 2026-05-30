package io.github._514sid.gapless

/** Screen rotation applied to all rendered content inside [GaplessPlayer]. */
enum class GaplessRotation(internal val degrees: Int) {
    Deg0(0),
    Deg90(90),
    Deg180(180),
    Deg270(270)
}
