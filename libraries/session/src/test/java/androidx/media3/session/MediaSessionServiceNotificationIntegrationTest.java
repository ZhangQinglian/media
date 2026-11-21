/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.session;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Real player -> controller listener -> service -> manager -> provider, with no mocked dispatch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MediaSessionServiceNotificationIntegrationTest {
  private static final String CHANNEL = "single-update-test";
  private static final int ID = 2001;

  private ExoPlayer player;
  private MediaSession session;
  private ServiceController<CountingService> controller;
  private CountingService service;
  private CountingProvider provider;

  @Before
  public void setUp() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    player = new TestExoPlayerBuilder(context).build();
    // A nonempty idle timeline avoids media loading and foreground-service side effects.
    player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));
    session = new MediaSession.Builder(context, player).build();
    controller = Robolectric.buildService(CountingService.class).create();
    service = controller.get();
    NotificationManager notifications = service.getSystemService(NotificationManager.class);
    notifications.createNotificationChannel(
        new NotificationChannel(CHANNEL, CHANNEL, NotificationManager.IMPORTANCE_LOW));
    provider = new CountingProvider(service);
    service.setMediaNotificationProvider(provider);
    service.setShowNotificationForIdlePlayer(
        MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS);
    service.addSession(session);
    runMainLooperUntil(() -> !provider.reasons.isEmpty());
    ShadowLooper.idleMainLooper();
    service.reasons.clear();
    provider.reasons.clear();
  }

  @After
  public void tearDown() {
    if (session != null) {
      session.release();
    }
    if (player != null) {
      player.release();
    }
    if (controller != null) {
      controller.destroy();
    }
  }

  @Test
  public void manualEntry_callsProviderExactlyOnce() {
    service.manuallyUpdateMediaNotification(session);
    ShadowLooper.idleMainLooper();
    assertThat(service.reasons).containsExactly(MediaSessionService.NOTIFICATION_UPDATE_MANUAL);
    assertThat(provider.reasons).containsExactly(MediaSessionService.NOTIFICATION_UPDATE_MANUAL);
  }

  @Test
  public void triggerEntry_callsProviderExactlyOncePerSession() {
    service.triggerNotificationUpdate();
    ShadowLooper.idleMainLooper();
    assertThat(service.reasons).containsExactly(MediaSessionService.NOTIFICATION_UPDATE_MANUAL);
    assertThat(provider.reasons).containsExactly(MediaSessionService.NOTIFICATION_UPDATE_MANUAL);
  }

  @Test
  public void realPlayerEvent_callsProviderExactlyOncePerDispatch() throws Exception {
    player.setPlayWhenReady(true);
    runMainLooperUntil(
        () -> service.reasons.contains(MediaSessionService.NOTIFICATION_UPDATE_PLAYER_EVENT));
    ShadowLooper.idleMainLooper();
    assertThat(service.reasons)
        .containsExactly(MediaSessionService.NOTIFICATION_UPDATE_PLAYER_EVENT);
    assertThat(provider.reasons)
        .containsExactly(MediaSessionService.NOTIFICATION_UPDATE_PLAYER_EVENT);
  }

  @Test
  public void providerAsyncCallback_stillPostsSecondNotificationWithoutRecreatingIt() {
    service.manuallyUpdateMediaNotification(session);
    ShadowLooper.idleMainLooper();
    provider.callback.onNotificationChanged(provider.notification("artwork-ready"));
    ShadowLooper.idleMainLooper();
    assertThat(provider.reasons).containsExactly(MediaSessionService.NOTIFICATION_UPDATE_MANUAL);
    assertThat(service.reasons).containsExactly(MediaSessionService.NOTIFICATION_UPDATE_MANUAL);
    assertThat(service.getSystemService(NotificationManager.class).getActiveNotifications())
        .hasLength(1);
    assertThat(
            service
                .getSystemService(NotificationManager.class)
                .getActiveNotifications()[0]
                .getNotification()
                .tickerText
                .toString())
        .isEqualTo("artwork-ready");
  }

  public static final class CountingService extends MediaSessionService {
    final List<Integer> reasons = new ArrayList<>();

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
      return null;
    }

    @Override
    public ListenableFuture<Void> onUpdateNotificationAsync(
        MediaSession session, boolean foreground, int reason) {
      reasons.add(reason);
      return super.onUpdateNotificationAsync(session, foreground, reason);
    }
  }

  private static final class CountingProvider implements MediaNotification.Provider {
    final List<Integer> reasons = new ArrayList<>();
    final Context context;
    Callback callback;

    CountingProvider(Context context) {
      this.context = context;
    }

    @Override
    public MediaNotification createNotification(
        MediaSession session,
        ImmutableList<CommandButton> buttons,
        MediaNotification.ActionFactory actions,
        Callback callback,
        int reason) {
      reasons.add(reason);
      this.callback = callback;
      return notification("initial");
    }

    MediaNotification notification(String ticker) {
      return new MediaNotification(
          ID,
          new NotificationCompat.Builder(context, CHANNEL)
              .setSmallIcon(android.R.drawable.ic_media_play)
              .setTicker(ticker)
              .build());
    }

    @Override
    public boolean handleCustomCommand(MediaSession session, String action, Bundle extras) {
      return false;
    }

    @Override
    public NotificationChannelInfo getNotificationChannelInfo() {
      return new NotificationChannelInfo(CHANNEL, CHANNEL);
    }
  }
}
