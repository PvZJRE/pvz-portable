package io.github.pvzjre.portable.pvzp.reanim;

import io.github.pvzjre.portable.lawn.EffectType;
import io.github.pvzjre.portable.sexy.SexyTransform2D;

/**
 * Ported from src/PvzpLib/Attachment.h:41-50 (class AttachEffect).
 *
 * <p>One entry of an {@link Attachment}: the effect kind, the id of the attached object in its
 * holder ({@link io.github.pvzjre.portable.pvzp.DataArray} id) and its offset matrix relative to the parent.
 */
public class AttachEffect {
    public int mEffectID;
    public int mEffectType;
    public SexyTransform2D mOffset;
    public boolean mDontDrawIfParentHidden;
    public boolean mDontPropogateColor;

    public AttachEffect() {
        mEffectID = 0;
        mEffectType = EffectType.EFFECT_OTHER;
        mOffset = new SexyTransform2D();
    }
}
