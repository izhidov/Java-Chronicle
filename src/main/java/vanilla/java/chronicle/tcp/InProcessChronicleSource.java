/*
 * Copyright 2011 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vanilla.java.chronicle.tcp;

import vanilla.java.chronicle.Chronicle;
import vanilla.java.chronicle.EnumeratedMarshaller;
import vanilla.java.chronicle.Excerpt;
import vanilla.java.chronicle.impl.WrappedExcerpt;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A Chronicle as a service to be replicated to any number of clients.  Clients can restart from where ever they are up to.
 * <p/>
 * Can be used an in process component which wraps the underlying Chronicle
 * and offers lower overhead than using ChronicleSource
 *
 * @author peter.lawrey
 */
public class InProcessChronicleSource<C extends Chronicle> implements Chronicle {
    private final C chronicle;
    private final ServerSocketChannel server;

    private final String name;
    private final ExecutorService service;
    private final Logger logger;

    private volatile boolean closed = false;

    public InProcessChronicleSource(C chronicle, int port) throws IOException {
        this.chronicle = chronicle;
        server = ServerSocketChannel.open();
        server.socket().bind(new InetSocketAddress(port));
        name = chronicle.name() + "@" + port;
        logger = Logger.getLogger(getClass().getName() + "." + name);
        service = Executors.newCachedThreadPool(new NamedThreadFactory(name));
        service.execute(new Acceptor());
    }

    class Acceptor implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName(name + "-acceptor");
            try {
                while (!closed) {
                    SocketChannel socket = server.accept();
                    service.execute(new Handler(socket));
                }
            } catch (IOException e) {
                if (!closed)
                    logger.log(Level.SEVERE, "Acceptor dying", e);
            }
        }
    }

    class Handler implements Runnable {
        private final SocketChannel socket;

        public Handler(SocketChannel socket) throws SocketException {
            this.socket = socket;
            socket.socket().setSendBufferSize(256 * 1024);
//            socket.socket().setTcpNoDelay(true);
        }

        @Override
        public void run() {
            try {
                long index = readIndex(socket);
                Excerpt excerpt = chronicle.createExcerpt();
                ByteBuffer bb = TcpUtil.createBuffer(1, chronicle); // minimum size
                boolean first = true;
                OUTER:
                while (!closed) {
                    while (!excerpt.index(index)) {
//                        System.out.println("Waiting for " + index);
                        pause();
                        if (closed) break OUTER;
                    }
//                    System.out.println("Writing " + index);
                    int size = excerpt.capacity();
                    int remaining;

                    bb.clear();
                    if (first) {
//                        System.out.println("wi "+index);
                        bb.putLong(index);
                        first = false;
                        remaining = size + TcpUtil.HEADER_SIZE;
                    } else {
                        remaining = size + 4;
                    }
                    bb.putInt(size);
                    while (remaining > 0) {
                        int size2 = Math.min(remaining, bb.capacity());
                        bb.limit(size2);
                        excerpt.read(bb);
                        bb.flip();
//                        System.out.println("w " + ChronicleTest.asString(bb));
                        remaining -= bb.remaining();
                        while (bb.remaining() > 0 && socket.write(bb) > 0) ;
                    }
                    if (bb.remaining() > 0) throw new EOFException("Failed to send index=" + index);
                    index++;
//                    if (index % 20000 == 0)
//                        System.out.println(System.currentTimeMillis() + ": wrote " + index);
                }
            } catch (IOException e) {
                if (!closed)
                    logger.log(Level.INFO, "Connect " + socket + " died", e);
            }
        }

        private long readIndex(SocketChannel socket) throws IOException {
            ByteBuffer bb = ByteBuffer.allocate(8);
            while (bb.remaining() > 0 && socket.read(bb) > 0) ;
            if (bb.remaining() > 0) throw new EOFException();
            return bb.getLong(0);
        }
    }

    private final Object notifier = new Object();

    protected void pause() {
        try {
            synchronized (notifier) {
                notifier.wait(1000);
            }
        } catch (InterruptedException ie) {
            logger.warning("Interrupt ignored");
            ;
        }
    }

    void wakeSessionHandlers() {
        synchronized (notifier) {
            notifier.notifyAll();
        }
    }

    @Override
    public String name() {
        return chronicle.name();
    }

    @Override
    public Excerpt createExcerpt() {
        return new SourceExcerpt();
    }

    @Override
    public long size() {
        return chronicle.size();
    }

    @Override
    public long sizeInBytes() {
        return chronicle.sizeInBytes();
    }

    @Override
    public ByteOrder byteOrder() {
        return chronicle.byteOrder();
    }

    @Override
    public void close() {
        closed = true;
        chronicle.close();
        try {
            server.close();
        } catch (IOException e) {
            logger.warning("Error closing server port " + e);
        }
    }

    @Override
    public <E> void setEnumeratedMarshaller(EnumeratedMarshaller<E> marshaller) {
        chronicle.setEnumeratedMarshaller(marshaller);
    }

    private class SourceExcerpt extends WrappedExcerpt {
        public SourceExcerpt() {
            super(InProcessChronicleSource.this.chronicle.createExcerpt());
        }

        @Override
        public void finish() {
            super.finish();
            wakeSessionHandlers();
//            System.out.println("Wrote " + index());
        }
    }
}