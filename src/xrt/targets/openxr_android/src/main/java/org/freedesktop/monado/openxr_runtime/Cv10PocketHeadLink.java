// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** The two TCP endpoints read by the original Pocket XR, restricted to the local address. */
final class Cv10PocketHeadLink implements AutoCloseable {
    private static final int IMU_PORT = 52998;
    private static final int CONTROL_PORT = 52999;
    private static final int HEARTBEAT = 10_000;
    private static final int HEARTBEAT_BYTES = 33;
    private static final int MAX_CONTROL_BYTES = 4096;
    private static final int READ_TIMEOUT_MS = 8_000;

    private final Listener imu;
    private final Listener control;

    Cv10PocketHeadLink(InetAddress address, Consumer<String> log) throws IOException {
        imu = new Listener(address, IMU_PORT, socket -> writeImu(socket, log), log);
        try {
            control = new Listener(address, CONTROL_PORT, Cv10PocketHeadLink::replyToControl, log);
        } catch (IOException e) {
            imu.close();
            throw e;
        }
        imu.worker.start();
        control.worker.start();
    }

    private static void writeImu(Socket socket, Consumer<String> log) throws IOException, InterruptedException {
        try (Cv10PocketImu source = Cv10PocketImu.open()) {
            OutputStream output = socket.getOutputStream();
            if (!source.writeNext(output, READ_TIMEOUT_MS)) {
                log.accept("CV10 IMU timed out before first frame");
                return;
            }
            log.accept("Sent first live CV10 IMU frame");
            while (source.writeNext(output, READ_TIMEOUT_MS)) {
                // The existing HID reader paces packets.
            }
        }
    }

    private static void replyToControl(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        OutputStream output = socket.getOutputStream();
        while (!Thread.currentThread().isInterrupted()) {
            // Pocket XR keeps this connection open between commands, even with no heartbeats.
            socket.setSoTimeout(0);
            int first = input.read();
            if (first < 0) return;
            socket.setSoTimeout(READ_TIMEOUT_MS);
            int type = (first << 8) | input.readUnsignedByte();
            int size = input.readInt();
            if (size < 0 || size > MAX_CONTROL_BYTES) throw new ProtocolException("Invalid control packet size");
            byte[] request = new byte[size];
            input.readFully(request);

            if (type == HEARTBEAT && size == HEARTBEAT_BYTES && request[0] == 1) {
                long receivedNs = System.nanoTime();
                long clientNs = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN).getLong(1);
                ByteBuffer reply = packet(type, HEARTBEAT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                reply.put((byte) 0).putLong(clientNs).putLong(receivedNs).putLong(System.nanoTime()).putLong(0L);
                output.write(reply.array());
            } else if (size >= Integer.BYTES && (request[0] & 0x80) != 0) {
                // Keep Pocket XR's software-selected display mode. This bridge controls no panel settings.
                int rpcId = ByteBuffer.wrap(request).getInt() & 0x7fff_ffff;
                ByteBuffer reply = packet(type, 8);
                reply.putInt(rpcId).put((byte) 0x22).put((byte) 2).put((byte) 0x08).put((byte) 1);
                output.write(reply.array());
            }
        }
    }

    private static ByteBuffer packet(int type, int size) {
        return ByteBuffer.allocate(6 + size).putShort((short) type).putInt(size);
    }

    @Override
    public void close() {
        imu.close();
        control.close();
    }

    private interface Connection {
        void serve(Socket socket) throws IOException, InterruptedException;
    }

    private static final class Listener implements AutoCloseable {
        private final ServerSocket server = new ServerSocket();
        private final InetAddress address;
        private final int port;
        private final Thread worker;
        private final Connection connection;
        private final Consumer<String> log;
        private Socket client;

        Listener(InetAddress address, int port, Connection connection, Consumer<String> log) throws IOException {
            this.address = address;
            this.port = port;
            this.connection = connection;
            this.log = log;
            try {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(address, port), 1);
            } catch (IOException error) {
                closeSocket(server);
                throw error;
            }
            worker = new Thread(this::listen, "cv10-pocket-" + port);
        }

        private void listen() {
            while (!worker.isInterrupted()) {
                Socket accepted;
                try {
                    accepted = server.accept();
                } catch (IOException error) {
                    if (!server.isClosed()) log.accept("Pocket XR listener failed on " + port + ": " + error);
                    return;
                }
                try (Socket socket = accepted) {
                    synchronized (this) {
                        if (server.isClosed()) return;
                        if (!address.equals(socket.getInetAddress())) continue;
                        client = socket;
                    }
                    socket.setTcpNoDelay(true);
                    log.accept("Accepted local Pocket XR connection on " + port);
                    connection.serve(socket);
                } catch (InterruptedException ended) {
                    worker.interrupt();
                } catch (IOException | IllegalStateException ended) {
                    if (server.isClosed()) return;
                    log.accept("Pocket XR connection ended on " + port + " (" + ended.getClass().getSimpleName() + ")");
                } finally {
                    synchronized (this) {
                        client = null;
                    }
                }
            }
        }

        @Override
        public synchronized void close() {
            worker.interrupt();
            closeSocket(server);
            closeSocket(client);
        }

        private static void closeSocket(AutoCloseable socket) {
            if (socket == null) return;
            try {
                socket.close();
            } catch (Exception ignored) {
                // Both an exit and a failed startup can close these sockets.
            }
        }
    }
}
