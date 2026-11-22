package io.github.pvzjre.portable.pvzp.reanim;

import io.github.pvzjre.portable.pvzp.DefinitionArrayDef;

/**
 * Ported from src/PvzpLib/Reanimator.h:80-92 (class ReanimatorDefinition) and
 * src/PvzpLib/Definition.cpp:266-274 (ReanimatorDefinitionConstructor).
 *
 * <p>{@code mTracks} is upstream's {@code ReanimatorTrackArray} ({@code tracks}, {@code count}),
 * stored as a {@link DefinitionArrayDef} for the generic {@code Definition} loader.
 */
public class ReanimatorDefinition {
    public DefinitionArrayDef mTracks;
    public float mFPS;
    public ReanimAtlas mReanimAtlas;

    public ReanimatorDefinition() {
        mTracks = new DefinitionArrayDef(new ReanimatorTrack[0]);
        mFPS = 12.0f;
        mReanimAtlas = null;
    }
}
