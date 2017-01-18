package com.kaltura.playkit.plugins.ads.ima;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider;
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.drm.StreamingDrmSessionManager;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataRenderer;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveVideoTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.SimpleExoPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static android.content.ContentValues.TAG;

/**
 * Video player that can play content video and ads.
 */
public class ExoPlayerWithAdPlayback extends RelativeLayout implements  ExoPlayer.EventListener {
    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private DefaultTrackSelector mTrackSelector;
    private android.os.Handler mainHandler = new Handler();
    private SimpleExoPlayer player;

    private DataSource.Factory mediaDataSourceFactory;
    private Context mContext;

    @Override
    public void onLoadingChanged(boolean isLoading) {

    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (playbackState == ExoPlayer.STATE_READY && playWhenReady == true) {
            if (mIsAdDisplayed) {
                for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                    callback.onPlay();
                }
            }
        }

        if (playbackState == ExoPlayer.STATE_READY && playWhenReady == false) {
            if (mIsAdDisplayed) {
                for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                    callback.onPause();
                }
            }
        }

        if (playbackState == ExoPlayer.STATE_ENDED) {
            if (mIsAdDisplayed) {
                for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                    callback.onEnded();
                }
            } else {
                // Alert an external listener that our content video is complete.
                if (mOnContentCompleteListener != null) {
                    mOnContentCompleteListener.onContentComplete();
                }
            }
        }
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {

    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        if (mIsAdDisplayed) {
            for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                callback.onError();
            }
        }
    }

    @Override
    public void onPositionDiscontinuity() {
        Log.d(TAG, "onPositionDiscontinuity");
    }

    /**
     * Interface for alerting caller of video completion.
     */
    public interface OnContentCompleteListener {
        public void onContentComplete();
    }

    // The wrapped video player.
    private SimpleExoPlayerView mVideoPlayer;

    // The SDK will render ad playback UI elements into this ViewGroup.
    private ViewGroup mAdUiContainer;

    // Used to track if the current video is an ad (as opposed to a content video).
    private boolean mIsAdDisplayed;

    // Used to track the current content video URL to resume content playback.
    private String mContentVideoUrl;

    // The saved position in the ad to resume if app is backgrounded during ad playback.
    private long mSavedAdPosition;

    // The saved position in the content to resume to after ad playback or if app is backgrounded
    // during content playback.
    private long mSavedContentPosition;

    // Called when the content is completed.
    private OnContentCompleteListener mOnContentCompleteListener;

    // VideoAdPlayer interface implementation for the SDK to send ad play/pause type events.
    private VideoAdPlayer mVideoAdPlayer;

    // ContentProgressProvider interface implementation for the SDK to check content progress.
    private ContentProgressProvider mContentProgressProvider;

    private final List<VideoAdPlayer.VideoAdPlayerCallback> mAdCallbacks =
            new ArrayList<VideoAdPlayer.VideoAdPlayerCallback>(1);

    public ExoPlayerWithAdPlayback(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public ExoPlayerWithAdPlayback(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public ExoPlayerWithAdPlayback(Context context) {
        super(context,null);
        this.mContext = context;
        init();
    }

    public ViewGroup getmAdUiContainer() {
        return mAdUiContainer;
    }

    private EventLogger mEventLogger = new EventLogger();
    private static class EventLogger implements  ExoPlayer.EventListener, AudioRendererEventListener, VideoRendererEventListener, MetadataRenderer.Output, AdaptiveMediaSourceEventListener, ExtractorMediaSource.EventListener, StreamingDrmSessionManager.EventListener {


        @Override
        public void onLoadingChanged(boolean isLoading) {

        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {

        }

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest) {

        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {

        }

        @Override
        public void onPositionDiscontinuity() {

        }

        @Override
        public void onAudioEnabled(DecoderCounters counters) {

        }

        @Override
        public void onAudioSessionId(int audioSessionId) {

        }

        @Override
        public void onAudioDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {

        }

        @Override
        public void onAudioInputFormatChanged(Format format) {

        }

        @Override
        public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {

        }

        @Override
        public void onAudioDisabled(DecoderCounters counters) {

        }

        @Override
        public void onVideoEnabled(DecoderCounters counters) {

        }

        @Override
        public void onVideoDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {

        }

        @Override
        public void onVideoInputFormatChanged(Format format) {

        }

        @Override
        public void onDroppedFrames(int count, long elapsedMs) {

        }

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

        }

        @Override
        public void onRenderedFirstFrame(Surface surface) {

        }

        @Override
        public void onVideoDisabled(DecoderCounters counters) {

        }

        @Override
        public void onLoadStarted(DataSpec dataSpec, int dataType, int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs) {

        }

        @Override
        public void onLoadCompleted(DataSpec dataSpec, int dataType, int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded) {

        }

        @Override
        public void onLoadCanceled(DataSpec dataSpec, int dataType, int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded) {

        }

        @Override
        public void onLoadError(DataSpec dataSpec, int dataType, int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded, IOException error, boolean wasCanceled) {

        }

        @Override
        public void onUpstreamDiscarded(int trackType, long mediaStartTimeMs, long mediaEndTimeMs) {

        }

        @Override
        public void onDownstreamFormatChanged(int trackType, Format trackFormat, int trackSelectionReason, Object trackSelectionData, long mediaTimeMs) {

        }

        @Override
        public void onLoadError(IOException error) {

        }

        @Override
        public void onDrmKeysLoaded() {

        }

        @Override
        public void onDrmSessionManagerError(Exception e) {

        }

        @Override
        public void onMetadata(Metadata metadata) {

        }
    }



    private static class TrackSelectionHelper {

        public TrackSelectionHelper(DefaultTrackSelector trackSelector, TrackSelection.Factory videoTrackSelectionFactory) {

        }
    }

    private TrackSelectionHelper mTrackSelectionHelper;

   // @Override
   // protected void onFinishInflate() {
   //     super.onFinishInflate();
   //     init();
   // }

    public SimpleExoPlayerView getSimpleExoPlayerView() {
        return mVideoPlayer;
    }

    private void init() {
        mIsAdDisplayed = false;
        mSavedAdPosition = 0;
        mSavedContentPosition = 0;
        mVideoPlayer = new SimpleExoPlayerView(getContext());
        mVideoPlayer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mVideoPlayer.setId(new Integer(123456789));
        mVideoPlayer.setUseController(false);
        TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveVideoTrackSelection.Factory(BANDWIDTH_METER);
        mTrackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);

        player = ExoPlayerFactory.newSimpleInstance(getContext(), mTrackSelector, new DefaultLoadControl(), null);
        mVideoPlayer.setPlayer(player);


        FrameLayout adFrameLayout = new FrameLayout(getContext());
        adFrameLayout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mAdUiContainer = (ViewGroup) adFrameLayout;

        // Define VideoAdPlayer connector.
        mVideoAdPlayer = new VideoAdPlayer() {
            @Override
            public void playAd() {
                mIsAdDisplayed = true;
                mVideoPlayer.getPlayer().setPlayWhenReady(true);
            }

            @Override
            public void loadAd(String url) {
                mIsAdDisplayed = true;
                //mVideoPlayer.getPlayer().prepare(buildMediaSource(Uri.parse(mContentVideoUrl), "mp4"));
                initializePlayer(Uri.parse(url));
            }

            @Override
            public void stopAd() {
                mVideoPlayer.getPlayer().stop();
            }

            @Override
            public void pauseAd() {
                mVideoPlayer.getPlayer().setPlayWhenReady(false);
            }

            @Override
            public void resumeAd() {
                playAd();
            }

            @Override
            public void addCallback(VideoAdPlayerCallback videoAdPlayerCallback) {
                mAdCallbacks.add(videoAdPlayerCallback);
            }

            @Override
            public void removeCallback(VideoAdPlayerCallback videoAdPlayerCallback) {
                mAdCallbacks.remove(videoAdPlayerCallback);
            }

            @Override
            public VideoProgressUpdate getAdProgress() {
                if (!mIsAdDisplayed || mVideoPlayer.getPlayer().getDuration() <= 0) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                return new VideoProgressUpdate(mVideoPlayer.getPlayer().getCurrentPosition(),
                        mVideoPlayer.getPlayer().getDuration());
            }
        };

        mContentProgressProvider = new ContentProgressProvider() {
            @Override
            public VideoProgressUpdate getContentProgress() {
                if (mIsAdDisplayed || mVideoPlayer.getPlayer().getDuration() <= 0) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                return new VideoProgressUpdate(mVideoPlayer.getPlayer().getCurrentPosition(),
                        mVideoPlayer.getPlayer().getDuration());
            }
        };

        mVideoPlayer.getPlayer().addListener(this);
    }


    /**
     * Set a listener to be triggered when the content (non-ad) video completes.
     */

    public void setOnContentCompleteListener(OnContentCompleteListener listener) {
        mOnContentCompleteListener = listener;
    }

    /**
     * Set the path of the video to be played as content.
     */
    public void setContentVideoPath(String contentVideoUrl) {
        mContentVideoUrl = contentVideoUrl;
    }

    /**
     * Save the playback progress state of the currently playing video. This is called when content
     * is paused to prepare for ad playback or when app is backgrounded.
     */
    public void savePosition() {
        if (mIsAdDisplayed) {
            mSavedAdPosition = mVideoPlayer.getPlayer().getCurrentPosition();
        } else {
            mSavedContentPosition = mVideoPlayer.getPlayer().getCurrentPosition();
        }
    }

    /**
     * Restore the currently loaded video to its previously saved playback progress state. This is
     * called when content is resumed after ad playback or when focus has returned to the app.
     */
    public void restorePosition() {
        if (mIsAdDisplayed) {
            mVideoPlayer.getPlayer().seekTo(mSavedAdPosition);
        } else {
            mVideoPlayer.getPlayer().seekTo(mSavedContentPosition);
        }
    }

    /**
     * Pauses the content video.
     */
    public void pause() {
        mVideoPlayer.getPlayer().setPlayWhenReady(false);
    }

    /**
     * Plays the content video.
     */
    public void play() {
        mVideoPlayer.getPlayer().setPlayWhenReady(true);
    }

    /**
     * Seeks the content video.
     */
    public void seek(int time) {
        if (mIsAdDisplayed) {
            // When ad is playing, set the content video position to seek to when ad finishes.
            mSavedContentPosition = time;
        } else {
            mVideoPlayer.getPlayer().seekTo(time);
        }
    }

    /**
     * Returns current content video play time.
     */
    public long getCurrentContentTime() {
        if (mIsAdDisplayed) {
            return mSavedContentPosition;
        } else {
            return mVideoPlayer.getPlayer().getCurrentPosition();
        }
    }

    /**
     * Pause the currently playing content video in preparation for an ad to play, and disables
     * the media controller.
     */
    public void pauseContentForAdPlayback() {
        //mVideoPlayer.getPlayer().disablePlaybackControls();
        savePosition();
        mVideoPlayer.getPlayer().stop();
    }

    /**
     * Resume the content video from its previous playback progress position after
     * an ad finishes playing. Re-enables the media controller.
     */
    public void resumeContentAfterAdPlayback() {
        if (mContentVideoUrl == null || mContentVideoUrl.isEmpty()) {
            Log.w("ImaExample", "No content URL specified.");
            return;
        }
//        mIsAdDisplayed = false;
//        mVideoPlayer.setVideoPath(mContentVideoUrl);
//        mVideoPlayer.getPlayer().enablePlaybackControls();
//        mVideoPlayer.seekTo(mSavedContentPosition);
//        mVideoPlayer.play();
        mIsAdDisplayed = false;
        mVideoPlayer.getPlayer().prepare(buildMediaSource(Uri.parse(mContentVideoUrl), "mp4"));
        restorePosition();
        play();
    }

    /**
     * Returns the UI element for rendering video ad elements.
     */
    public ViewGroup getAdUiContainer() {
        return mAdUiContainer;
    }

    /**
     * Returns an implementation of the SDK's VideoAdPlayer interface.
     */
    public VideoAdPlayer getVideoAdPlayer() {
        return mVideoAdPlayer;
    }

    /**
     * Returns if an ad is displayed.
     */
    public boolean getIsAdDisplayed() {
        return mIsAdDisplayed;
    }

    public ContentProgressProvider getContentProgressProvider() {
        return mContentProgressProvider;
    }

    private void initializePlayer(Uri currentSourceUri) {
        TrackSelection.Factory videoTrackSelectionFactory =
                new AdaptiveVideoTrackSelection.Factory(BANDWIDTH_METER);
        mTrackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);
        mTrackSelectionHelper = new TrackSelectionHelper(mTrackSelector, videoTrackSelectionFactory);
        player = ExoPlayerFactory.newSimpleInstance(getContext(), mTrackSelector, new DefaultLoadControl(), null);
        player.addListener(this);
        player.addListener(mEventLogger);
        player.setAudioDebugListener(mEventLogger);
        player.setVideoDebugListener(mEventLogger);
        player.setId3Output(mEventLogger);

        mVideoPlayer.setPlayer(player);

        mVideoPlayer.getPlayer().setPlayWhenReady(true); //TODO

        MediaSource mediaSource = buildMediaSource(currentSourceUri, null);

        mVideoPlayer.getPlayer().prepare(mediaSource);
    }

    private com.google.android.exoplayer2.source.MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        mediaDataSourceFactory = buildDataSourceFactory(true);
        int type = Util.inferContentType(!TextUtils.isEmpty(overrideExtension) ? "." + overrideExtension
                : uri.getLastPathSegment());
        switch (type) {
            case C.TYPE_SS:
                return new SsMediaSource(uri, buildDataSourceFactory(false),
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mainHandler, mEventLogger);
            case C.TYPE_DASH:
                return new DashMediaSource(uri, buildDataSourceFactory(false),
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mainHandler, mEventLogger);
            case C.TYPE_HLS:
                return new HlsMediaSource(uri, mediaDataSourceFactory, mainHandler, mEventLogger);
            case C.TYPE_OTHER:
                return new ExtractorMediaSource(uri, mediaDataSourceFactory, new DefaultExtractorsFactory(),
                        mainHandler, mEventLogger);
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #BANDWIDTH_METER} as a listener to the new
     *     DataSource factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        return new DefaultDataSourceFactory(getContext(), useBandwidthMeter ? BANDWIDTH_METER : null,
                buildHttpDataSourceFactory(useBandwidthMeter));
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @param useBandwidthMeter Whether to set {@link #BANDWIDTH_METER} as a listener to the new
     *     DataSource factory.
     * @return A new HttpDataSource factory.
     */
    private HttpDataSource.Factory buildHttpDataSourceFactory(boolean useBandwidthMeter) {
        return new DefaultHttpDataSourceFactory(Util.getUserAgent(getContext(), "AdPlayKit"), useBandwidthMeter ? BANDWIDTH_METER : null);
    }

}
