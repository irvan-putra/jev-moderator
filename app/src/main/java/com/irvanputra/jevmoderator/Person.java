package com.irvanputra.jevmoderator;

/** One detected body in the camera frame. Position only: no identity, no face recognition. */
final class Person {
    float x0, y0, x1, y1;   // box, normalized 0..1 in the full frame
    float nx, ny;           // nose position, normalized
    boolean raised;
}
