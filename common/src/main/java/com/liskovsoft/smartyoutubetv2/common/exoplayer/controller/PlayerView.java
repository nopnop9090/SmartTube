package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

public interface PlayerView {
    void setQualityInfo(String info);
    /**
     * FORK ADDITION: show "18+" badge overlay when YouTube reports the video is age-restricted.
     * Triggered from ExoPlayerController.openXxx() based on MediaItemFormatInfo.getPlayabilityReason().
     */
    void setAgeRestriction(boolean isRestricted);
    void setVideo(Video video);
}
