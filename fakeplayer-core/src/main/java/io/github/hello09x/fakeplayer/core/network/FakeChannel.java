package io.github.hello09x.fakeplayer.core.network;

import io.netty.channel.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FakeChannel extends AbstractChannel {
    private final EventLoop eventLoop = SharedEventLoop.acquire();
    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final ChannelPipeline pipeline = new FakeChannelPipeline(this);
    private final InetAddress address;
    private final AtomicBoolean eventLoopReleased = new AtomicBoolean();
    private volatile boolean open = true;
    private volatile boolean active = true;

    public FakeChannel(@Nullable Channel parent, @NotNull InetAddress address) {
        super(parent);
        this.address = address;
    }

    @Override
    public ChannelConfig config() {
        config.setAutoRead(true);
        return config;
    }

    @Override
    protected void doBeginRead() throws Exception {
    }

    @Override
    protected void doBind(SocketAddress arg0) throws Exception {
    }

    @Override
    protected void doClose() throws Exception {
        this.open = false;
        this.active = false;
        if (this.eventLoopReleased.compareAndSet(false, true)) {
            SharedEventLoop.release(this.eventLoop);
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        this.doClose();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (; ; ) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            in.remove();
        }
    }

    @Override
    public boolean isActive() {
        return this.active;
    }

    @Override
    protected boolean isCompatible(EventLoop arg0) {
        return true;
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ChannelFuture close() {
        // AbstractChannel normally performs its idempotence check on the event
        // loop. Our final close intentionally terminates that loop, so a later
        // best-effort cleanup must return the completed close future without
        // attempting to enqueue work on an already terminated executor.
        return this.open ? super.close() : this.closeFuture();
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        if (!this.open) {
            promise.trySuccess();
            return promise;
        }
        return super.close(promise);
    }

    @Override
    protected SocketAddress localAddress0() {
        return new InetSocketAddress(address, 25565);
    }

    @Override
    public ChannelMetadata metadata() {
        return new ChannelMetadata(true);
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                safeSetSuccess(promise);
            }
        };
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return new InetSocketAddress(address, 25565);
    }

    @Override
    public EventLoop eventLoop() {
        return this.eventLoop;
    }

    /**
     * Legacy fake connections do not need a dedicated thread per fake player.
     * Share one loop while channels are live, then release the static reference
     * and terminate the loop after the final channel closes. A later generation
     * can acquire a fresh loop after a full unload/reload cycle.
     */
    private static final class SharedEventLoop {
        private static DefaultEventLoop current;
        private static int references;

        private static synchronized EventLoop acquire() {
            if (current == null || current.isShuttingDown() || current.isShutdown()) {
                current = new DefaultEventLoop();
                references = 0;
            }
            references++;
            return current;
        }

        private static synchronized void release(EventLoop eventLoop) {
            if (eventLoop != current || references <= 0) {
                return;
            }
            references--;
            if (references == 0) {
                var retired = current;
                current = null;
                retired.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
        }
    }
}
