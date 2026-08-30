package io.github.hello09x.fakeplayer.core.network;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeChannelTest {

    @Test
    void closingThePipelineClosesTheChannelAndReleasesItsEventLoop() {
        var channel = new FakeChannel(null, InetAddress.getLoopbackAddress());
        try {
            assertTrue(channel.isOpen());
            assertTrue(channel.isActive());

            channel.pipeline().close().syncUninterruptibly();

            assertFalse(channel.isOpen());
            assertFalse(channel.isActive());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void disconnectingThePipelineClosesTheChannel() {
        var channel = new FakeChannel(null, InetAddress.getLoopbackAddress());
        try {
            channel.pipeline().disconnect().syncUninterruptibly();

            assertFalse(channel.isOpen());
            assertFalse(channel.isActive());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void channelsShareAndReleaseTheirEventLoop() {
        var first = new FakeChannel(null, InetAddress.getLoopbackAddress());
        var second = new FakeChannel(null, InetAddress.getLoopbackAddress());
        var eventLoop = first.eventLoop();
        try {
            assertSame(eventLoop, second.eventLoop());

            first.close().syncUninterruptibly();
            assertFalse(eventLoop.isShuttingDown());

            second.close().syncUninterruptibly();
            assertTrue(eventLoop.terminationFuture().awaitUninterruptibly(5, TimeUnit.SECONDS));
            assertTrue(eventLoop.isTerminated());
        } finally {
            first.close().syncUninterruptibly();
            second.close().syncUninterruptibly();
        }
    }
}
