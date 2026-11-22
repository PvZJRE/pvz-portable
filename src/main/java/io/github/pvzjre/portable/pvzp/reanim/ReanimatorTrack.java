package io.github.pvzjre.portable.pvzp.reanim;

import io.github.pvzjre.portable.pvzp.DefinitionArrayDef;

/**
 * Ported from src/PvzpLib/Reanimator.h:65-77 (class ReanimatorTrack) and
 * src/PvzpLib/Definition.cpp:257-264 (ReanimatorTrackConstructor).
 *
 * <p>{@code mTransforms} is upstream's {@code ReanimatorTransformArray} ({@code mTransforms},
 * {@code count}); the port stores it as a {@link DefinitionArrayDef} so the generic
 * {@code Definition} loader can fill it.
 */
public class ReanimatorTrack {
    public String mName;
    public DefinitionArrayDef mTransforms;

    public ReanimatorTrack() {
        mName = "";
        mTransforms = new DefinitionArrayDef(new ReanimatorTransform[0]);
    }
}
