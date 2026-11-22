package io.github.pvzjre.portable.pvzp.reanim;

import io.github.pvzjre.portable.lawn.AttachmentID;
import io.github.pvzjre.portable.sexy.Color;
import io.github.pvzjre.portable.sexy.graphics.Image;

/**
 * Ported from src/PvzpLib/Reanimator.h:165-185 (class ReanimatorTrackInstance) and
 * src/PvzpLib/Reanimator.cpp:292-308 (ReanimatorTrackInstance::ReanimatorTrackInstance).
 *
 * <p>Per-track runtime state of one {@link Reanimation}. {@code mTrackColor} is an independent
 * multiplier from the whole-reanimation {@code Reanimation.mColorOverride}, both are applied in
 * {@link Reanimation#drawTrack}.
 */
public class ReanimatorTrackInstance {
    public int mBlendCounter;
    public int mBlendTime;
    public ReanimatorTransform mBlendTransform;
    public float mShakeOverride;
    public float mShakeX;
    public float mShakeY;
    public AttachmentIDRef mAttachmentID;
    public Image mImageOverride;
    public int mRenderGroup;
    public Color mTrackColor;
    public boolean mIgnoreClipRect;
    public boolean mTruncateDisappearingFrames;
    public boolean mIgnoreColorOverride;
    public boolean mIgnoreExtraAdditiveColor;

    public ReanimatorTrackInstance() {
        mBlendCounter = 0;
        mBlendTime = 0;
        mBlendTransform = new ReanimatorTransform();
        mShakeOverride = 0.0f;
        mShakeX = 0.0f;
        mShakeY = 0.0f;
        mAttachmentID = new AttachmentIDRef(AttachmentID.ATTACHMENTID_NULL);
        mRenderGroup = Reanimation.RENDER_GROUP_NORMAL;
        mIgnoreClipRect = false;
        mImageOverride = null;
        mTruncateDisappearingFrames = true;
        mTrackColor = new Color(255, 255, 255, 255);  // Color::White copied by value (C++ Color is a value type)
        mIgnoreColorOverride = false;
        mIgnoreExtraAdditiveColor = false;
    }
}
