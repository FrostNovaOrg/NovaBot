package org.frostnova.nova.bilibili.timeline;

import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.core.config.EventConfig;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.listener.NovaDefaultLiveOffEventListener;
import org.frostnova.nova.core.listener.NovaHandlerListener;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveOffDispatchTest {
    private static final String PLATFORM = "bilibili";
    private static final long UID = 9200000001L;
    private static final long START = 1000000L;
    @TempDir Path dir;

    @Test
    void failedRetriesMustLeaveSingleOffTimeline() {
        var timeline = mock(TimelineWriter.class);
        try (var context = open(timeline, mock(NovaEventHandler.class), mock(NovaEventHandler.class))) {
            assertThrows(IllegalStateException.class, () -> context.publishEvent(off()));
            assertThrows(IllegalStateException.class, () -> context.publishEvent(off()));
            context.publishEvent(off());
            var events = org.mockito.ArgumentCaptor.forClass(org.frostnova.nova.core.timeline.TimelineEvent.class);
            verify(timeline, atLeastOnce()).record(events.capture());
            assertEquals(1, events.getAllValues().stream().filter(e -> e.type() == TimelineEventType.LIVE_OFF).count(),
                    "one session must leave one live-off timeline entry");
        }
    }

    @Test
    void failedOffMustBlockNotificationAndReport() {
        var notification = mock(NovaEventHandler.class);
        var report = mock(NovaEventHandler.class);
        try (var context = open(mock(TimelineWriter.class), notification, report)) {
            assertThrows(IllegalStateException.class, () -> context.publishEvent(off()));
            assertThrows(IllegalStateException.class, () -> context.publishEvent(off()));
            assertEquals(0, mockingDetails(notification).getInvocations().size(), "failed off must not push notification");
            assertEquals(0, mockingDetails(report).getInvocations().size(), "failed off must not push report");
            context.publishEvent(off());
            verify(notification).handle(any(BilibiliLiveOffEvent.class), any());
            verify(report).handle(any(BilibiliLiveOffEvent.class), any());
        }
    }

    @Test
    void totalFailureMustStillPushAndRecordOff() throws Exception {
        var timeline = mock(TimelineWriter.class);
        var notification = mock(NovaEventHandler.class);
        var report = mock(NovaEventHandler.class);
        try (var context = open(timeline, notification, report, "totals")) {
            assertDoesNotThrow(() -> context.publishEvent(off()), "total failure must allow live-off dispatch");
            verify(notification, times(1)).handle(any(BilibiliLiveOffEvent.class), any());
            verify(report, times(1)).handle(any(BilibiliLiveOffEvent.class), any());
            var events = org.mockito.ArgumentCaptor.forClass(org.frostnova.nova.core.timeline.TimelineEvent.class);
            verify(timeline, atLeastOnce()).record(events.capture());
            assertEquals(1, events.getAllValues().stream().filter(e -> e.type() == TimelineEventType.LIVE_OFF).count());
        }
    }

    @Test
    void archiveWriteFailureMustStillPushAndRecordOff() throws Exception {
        var timeline = mock(TimelineWriter.class);
        var notification = mock(NovaEventHandler.class);
        var report = mock(NovaEventHandler.class);
        try (var context = open(timeline, notification, report, "disk")) {
            assertDoesNotThrow(() -> context.publishEvent(off()), "archive write failure must allow live-off dispatch");
            verify(notification, times(1)).handle(any(BilibiliLiveOffEvent.class), any());
            verify(report, times(1)).handle(any(BilibiliLiveOffEvent.class), any());
            var events = org.mockito.ArgumentCaptor.forClass(org.frostnova.nova.core.timeline.TimelineEvent.class);
            verify(timeline, atLeastOnce()).record(events.capture());
            assertEquals(1, events.getAllValues().stream().filter(e -> e.type() == TimelineEventType.LIVE_OFF).count());
        }
    }

    private BilibiliLiveOffEvent off() {
        return new BilibiliLiveOffEvent(new LiveStreamerInfo(UID, "sample", 9200000002L),
                Instant.ofEpochMilli(START + 3600000));
    }

    private AnnotationConfigApplicationContext open(TimelineWriter timeline, NovaEventHandler notification,
                                                   NovaEventHandler report) {
        try {
            return open(timeline, notification, report, "beforeStatus");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private AnnotationConfigApplicationContext open(TimelineWriter timeline, NovaEventHandler notification,
                                                    NovaEventHandler report, String failure) throws java.io.IOException {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        var data = spy(new DefaultLiveDataService(props));
        data.setLiveStatus(PLATFORM, UID, true);
        data.setLiveStartTime(PLATFORM, UID, START);
        var archive = new LiveSessionArchive(props);
        if (failure.equals("beforeStatus")) {
            doThrow(new IllegalStateException("state unavailable"))
                    .doThrow(new IllegalStateException("state unavailable")).doCallRealMethod()
                    .when(data).setLiveStatus(PLATFORM, UID, false);
        } else if (failure.equals("totals")) {
            doThrow(new IllegalStateException("totals unavailable")).when(data).mergeLiveDataIntoTotal(PLATFORM, UID);
        } else if (failure.equals("disk")) {
            Files.createDirectory(dir.resolve("sessions.jsonl"));
        }
        var history = new LiveRoomInfoHistory(new NovaStateStore(props));
        var offListener = new NovaDefaultLiveOffEventListener(data, archive, new LiveInterventionTracker(), history,
                new LiveSessionDetailArchiver(data, history, new LiveDetailArchive(props)));
        var source = mock(AbstractDataSource.class);
        var user = new PushUser();
        user.setUid(UID); user.setPlatform(PLATFORM);
        var target = new PushTarget();
        target.setPlatform("test"); target.setType(PushTargetType.GROUP); target.setNum(9200000004L);
        for (var handler : new NovaEventHandler[]{notification, report}) {
            var message = new PushMessage();
            message.setEventClass(BilibiliLiveOffEvent.class); message.setHandlerInstance(handler);
            message.setTarget(target); target.getMessages().add(message);
        }
        user.getTargets().add(target);
        when(source.getUser(PLATFORM, UID)).thenReturn(Optional.of(user));
        var pushGate = mock(PushGate.class);
        when(pushGate.allowedFor(any(), any(), any())).thenReturn(true);
        var context = new AnnotationConfigApplicationContext();
        context.register(EventConfig.class);
        context.registerBean("liveOff", NovaDefaultLiveOffEventListener.class, () -> offListener);
        context.registerBean("handler", NovaHandlerListener.class, () -> new NovaHandlerListener(source, pushGate, timeline));
        context.registerBean("recorder", BilibiliLiveTimelineRecorder.class, () -> new BilibiliLiveTimelineRecorder(timeline));
        context.refresh();
        return context;
    }
}
