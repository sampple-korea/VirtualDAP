package com.virtualdap.fixture.music;

import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import java.util.Collections;
import java.util.List;

/** A real declared service for package-query regressions; it contains no playable content. */
public final class FixturePlaybackService extends MediaBrowserService {
    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle hints) {
        return new BrowserRoot("fixture", null);
    }
    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(Collections.emptyList());
    }
}
