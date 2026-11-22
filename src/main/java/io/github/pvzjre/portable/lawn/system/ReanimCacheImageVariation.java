/*
 * Ported from src/Lawn/System/ReanimationLawn.h:38-43 (class ReanimCacheImageVariation).
 *
 * One cached off-screen render of a seed with a non-normal DrawVariation (zen-garden /
 * imitater / marigold colours), keyed by the (mSeedType, mDrawVariation) pair. Instances live in
 * ReanimatorCache.mImageVariationList and own the MemoryImage they point at
 * (ReanimatorCache::ReanimatorCacheDispose drops both together).
 */
package io.github.pvzjre.portable.lawn.system;

import io.github.pvzjre.portable.sexy.graphics.MemoryImage;

/**
 * Ported from src/Lawn/System/ReanimationLawn.h:38-43 (class ReanimCacheImageVariation).
 *
 * <p>Plain value holder; upstream has no constructor and neither does this port. {@link #mImage} is
 * an owning raw pointer upstream ({@code MemoryImage*}) and is a plain reference here.
 */
public class ReanimCacheImageVariation {

    /** Ported from src/Lawn/System/ReanimationLawn.h:40. */
    public int mSeedType;

    /** Ported from src/Lawn/System/ReanimationLawn.h:41. */
    public int mDrawVariation;

    /** Ported from src/Lawn/System/ReanimationLawn.h:42. */
    public MemoryImage mImage;
}
