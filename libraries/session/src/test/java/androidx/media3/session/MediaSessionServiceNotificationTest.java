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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ForegroundServiceStartNotAllowedException;
import androidx.annotation.Nullable;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Exercises the real service dispatch, mocking only its final notification manager boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@SuppressWarnings("deprecation")
public class MediaSessionServiceNotificationTest {
  private static final int REASON = MediaSessionService.NOTIFICATION_UPDATE_MANUAL;

  private MediaNotificationManager manager;
  private MediaSession session;
  private ServiceController<? extends DefaultService> controller;

  @Before
  public void setUp() {
    manager = mock(MediaNotificationManager.class);
    session = mock(MediaSession.class);
    when(session.getId()).thenReturn("notification-test");
    when(manager.updateNotification(any(), anyBoolean(), anyInt()))
        .thenReturn(Futures.immediateVoidFuture());
  }

  @After
  public void tearDown() {
    if (controller != null) {
      controller.destroy();
    }
  }

  @Test
  public void defaultAsync_updatesOnceAndForwardsArguments() throws Exception {
    DefaultService service = create(DefaultService.class);
    service.onUpdateNotificationAsync(session, true, REASON).get();
    verify(manager).updateNotification(session, true, REASON);
  }

  @Test
  public void synchronousDefault_isHookOnlyIncludingTwoArgumentBridge() {
    DefaultService service = create(DefaultService.class);
    service.onUpdateNotification(session, true, REASON);
    service.onUpdateNotification(session, true);
    verify(manager, never()).updateNotification(any(), anyBoolean(), anyInt());
  }

  @Test
  public void oneArgumentOverride_withSuper_updatesOnce() throws Exception {
    OneArgumentService service = create(OneArgumentService.class);
    service.callSuper = true;
    service.onUpdateNotificationAsync(session, false, REASON).get();
    assertThat(service.hookCalls).isEqualTo(1);
    verify(manager).updateNotification(session, false, REASON);
  }

  @Test
  public void oneArgumentOverride_withoutSuper_doesNotUpdate() throws Exception {
    OneArgumentService service = create(OneArgumentService.class);
    service.onUpdateNotificationAsync(session, false, REASON).get();
    assertThat(service.hookCalls).isEqualTo(1);
    verify(manager, never()).updateNotification(any(), anyBoolean(), anyInt());
  }

  @Test
  public void threeArgumentOverride_withSuper_updatesOnce() throws Exception {
    ThreeArgumentService service = create(ThreeArgumentService.class);
    service.callSuper = true;
    service.onUpdateNotificationAsync(session, false, REASON).get();
    assertThat(service.hookCalls).isEqualTo(1);
    verify(manager).updateNotification(session, false, REASON);
  }

  @Test
  public void threeArgumentOverride_withoutSuper_doesNotUpdate() throws Exception {
    ThreeArgumentService service = create(ThreeArgumentService.class);
    service.onUpdateNotificationAsync(session, false, REASON).get();
    assertThat(service.hookCalls).isEqualTo(1);
    verify(manager, never()).updateNotification(any(), anyBoolean(), anyInt());
  }

  @Test
  public void conditionalThreeArgumentOverride_doesNotLeakPreviousSuperFlag() throws Exception {
    ThreeArgumentService service = create(ThreeArgumentService.class);
    for (boolean callSuper : new boolean[] {true, false, true, false}) {
      service.callSuper = callSuper;
      clearInvocations(manager);
      service.onUpdateNotificationAsync(session, false, REASON).get();
      verify(manager, times(callSuper ? 1 : 0)).updateNotification(session, false, REASON);
    }
    assertThat(service.hookCalls).isEqualTo(4);
  }

  @Test
  public void conditionalOneArgumentOverride_doesNotLeakPreviousSuperFlag() throws Exception {
    OneArgumentService service = create(OneArgumentService.class);
    for (boolean callSuper : new boolean[] {true, false, true, false}) {
      service.callSuper = callSuper;
      clearInvocations(manager);
      service.onUpdateNotificationAsync(session, false, REASON).get();
      verify(manager, times(callSuper ? 1 : 0)).updateNotification(session, false, REASON);
    }
  }

  @Test
  public void twoArgumentOverride_isNotNewlyEnabledByAsyncDispatch() throws Exception {
    TwoArgumentService service = create(TwoArgumentService.class);
    service.onUpdateNotificationAsync(session, true).get();
    assertThat(service.hookCalls).isEqualTo(0);
    verify(manager)
        .updateNotification(session, true, MediaSessionService.NOTIFICATION_UPDATE_PLAYER_EVENT);
  }

  @Test
  public void defaultAsync_returnsExactManagerFuture_successAndFailure() throws Exception {
    DefaultService service = create(DefaultService.class);
    for (boolean fail : new boolean[] {false, true}) {
      SettableFuture<Void> future = SettableFuture.create();
      when(manager.updateNotification(session, false, REASON)).thenReturn(future);
      ListenableFuture<Void> actual = service.onUpdateNotificationAsync(session, false, REASON);
      assertThat(actual).isSameInstanceAs(future);
      assertThat(actual.isDone()).isFalse();
      if (fail) {
        IllegalStateException failure = new IllegalStateException("notification failed");
        future.setException(failure);
        assertThat(assertThrows(ExecutionException.class, actual::get))
            .hasCauseThat()
            .isSameInstanceAs(failure);
      } else {
        future.set(null);
        assertThat(actual.get()).isNull();
      }
    }
    verify(manager, times(2)).updateNotification(session, false, REASON);
  }

  @Test
  public void defaultAsync_returnsExactManagerFuture_cancellation() {
    DefaultService service = create(DefaultService.class);
    SettableFuture<Void> future = SettableFuture.create();
    when(manager.updateNotification(session, false, REASON)).thenReturn(future);
    ListenableFuture<Void> actual = service.onUpdateNotificationAsync(session, false, REASON);
    assertThat(actual).isSameInstanceAs(future);
    actual.cancel(false);
    assertThat(future.isCancelled()).isTrue();
    verify(manager).updateNotification(session, false, REASON);
  }

  @Test
  public void asyncOverride_withoutSuper_returnsItsFutureAndSkipsManager() {
    AsyncService service = create(AsyncService.class);
    assertThat(service.onUpdateNotificationAsync(session, true, REASON))
        .isSameInstanceAs(service.customFuture);
    assertThat(service.asyncCalls).isEqualTo(1);
    verify(manager, never()).updateNotification(any(), anyBoolean(), anyInt());
  }

  @Test
  public void asyncOverride_withSuper_updatesOnceAndReturnsManagerFuture() {
    AsyncService service = create(AsyncService.class);
    service.callSuper = true;
    SettableFuture<Void> future = SettableFuture.create();
    when(manager.updateNotification(session, true, REASON)).thenReturn(future);
    assertThat(service.onUpdateNotificationAsync(session, true, REASON)).isSameInstanceAs(future);
    assertThat(service.asyncCalls).isEqualTo(1);
    verify(manager).updateNotification(session, true, REASON);
  }

  @Test
  public void manualAndTriggerAndPlayerInternalEntry_eachDispatchesOneUpdate() throws Exception {
    DefaultService service = create(DefaultService.class);
    service.addSession(session);
    clearInvocations(manager);
    service.manuallyUpdateMediaNotification(session);
    service.triggerNotificationUpdate();
    verify(manager, times(2)).updateNotification(session, false, REASON);
    ListenableFuture<Boolean> playerResult =
        service.onUpdateNotificationInternal(
            session, false, MediaSessionService.NOTIFICATION_UPDATE_PLAYER_EVENT);
    runMainLooperUntil(playerResult::isDone);
    assertThat(playerResult.get()).isTrue();
    verify(manager)
        .updateNotification(session, false, MediaSessionService.NOTIFICATION_UPDATE_PLAYER_EVENT);
  }

  @Test
  public void internalEntry_waitsForFutureAndMapsSuccessAndFailure() throws Exception {
    DefaultService service = create(DefaultService.class);
    SettableFuture<Void> update = SettableFuture.create();
    when(manager.updateNotification(session, false, REASON)).thenReturn(update);
    ListenableFuture<Boolean> success =
        service.onUpdateNotificationInternal(session, false, REASON);
    assertThat(success.isDone()).isFalse();
    update.set(null);
    runMainLooperUntil(success::isDone);
    assertThat(success.get()).isTrue();

    when(manager.updateNotification(session, false, REASON))
        .thenReturn(Futures.immediateFailedFuture(new IllegalStateException("failed")));
    ListenableFuture<Boolean> failure =
        service.onUpdateNotificationInternal(session, false, REASON);
    runMainLooperUntil(failure::isDone);
    assertThat(failure.get()).isFalse();
  }

  @Test
  public void internalEntry_foregroundFailure_notifiesListenerOnce() throws Exception {
    DefaultService service = create(DefaultService.class);
    AtomicInteger failures = new AtomicInteger();
    service.setListener(
        new MediaSessionService.Listener() {
          @Override
          public void onForegroundServiceStartNotAllowedException() {
            failures.incrementAndGet();
          }
        });
    when(manager.shouldRunInForeground(true)).thenReturn(true);
    when(manager.updateNotification(session, true, REASON))
        .thenReturn(
            Futures.immediateFailedFuture(new ForegroundServiceStartNotAllowedException("denied")));
    ListenableFuture<Boolean> result = service.onUpdateNotificationInternal(session, true, REASON);
    runMainLooperUntil(() -> result.isDone() && failures.get() == 1);
    assertThat(result.get()).isFalse();
    verify(manager).updateNotification(session, true, REASON);
  }

  private <T extends DefaultService> T create(Class<T> clazz) {
    ServiceController<T> typedController = Robolectric.buildService(clazz).create();
    controller = typedController;
    T service = typedController.get();
    ReflectionHelpers.setField(service, "mediaNotificationManager", manager);
    return service;
  }

  public static class DefaultService extends MediaSessionService {
    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
      return null;
    }
  }

  public static final class OneArgumentService extends DefaultService {
    boolean callSuper;
    int hookCalls;

    @Override
    public void onUpdateNotification(MediaSession session) {
      hookCalls++;
      if (callSuper) {
        super.onUpdateNotification(session);
      }
    }
  }

  public static final class TwoArgumentService extends DefaultService {
    int hookCalls;

    @Override
    public void onUpdateNotification(MediaSession session, boolean foreground) {
      hookCalls++;
    }
  }

  public static final class ThreeArgumentService extends DefaultService {
    boolean callSuper;
    int hookCalls;

    @Override
    public void onUpdateNotification(MediaSession session, boolean foreground, int reason) {
      hookCalls++;
      if (callSuper) {
        super.onUpdateNotification(session, foreground, reason);
      }
    }
  }

  public static final class AsyncService extends DefaultService {
    boolean callSuper;
    int asyncCalls;
    final ListenableFuture<Void> customFuture = SettableFuture.create();

    @Override
    public ListenableFuture<Void> onUpdateNotificationAsync(
        MediaSession session, boolean foreground, int reason) {
      asyncCalls++;
      return callSuper
          ? super.onUpdateNotificationAsync(session, foreground, reason)
          : customFuture;
    }
  }
}
