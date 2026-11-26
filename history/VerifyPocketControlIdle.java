// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exercises the same control endpoint used by the Android head link. */
public final class VerifyPocketControlIdle {
    private static final int CONTROL_PORT = 52999;
    private static final int HEARTBEAT = 10_000;
    private static final int HEARTBEAT_BYTES = 33;

    public static void main(String[] args) throws Exception {
        InetAddress address = InetAddress.getByName("127.0.0.1");
        try (var link = new Cv10PocketHeadLink(address, System.out::println);
             var first = connect(address)) {
            verifyHeartbeat(first);
            Thread.sleep(9_000);
            verifyHeartbeat(first);
            System.out.println("Heartbeat succeeded on the same socket after 9 seconds idle");

            first.getOutputStream().write(HEARTBEAT >>> 8);
            check(first.getInputStream().read() == -1, "The incomplete control frame did not time out");
            System.out.println("Incomplete control frame timed out");

            try (var next = connect(address)) {
                verifyHeartbeat(next);
                link.close();
                check(next.getInputStream().read() == -1, "Stopping the bridge did not close its socket");
            }
        }
        try (var reopened = new Cv10PocketHeadLink(address, System.out::println);
             var client = connect(address)) {
            verifyHeartbeat(client);
            System.out.println("Both listener ports were released and the reopened bridge answered");
        }
    }

    private static Socket connect(InetAddress address) throws IOException {
        var socket = new Socket(address, CONTROL_PORT);
        socket.setSoTimeout(11_000);
        return socket;
    }

    private static void verifyHeartbeat(Socket socket) throws IOException {
        long timestamp = System.nanoTime();
        byte[] request = ByteBuffer.allocate(6 + HEARTBEAT_BYTES)
                .putShort((short) HEARTBEAT).putInt(HEARTBEAT_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).put((byte) 1).putLong(timestamp).array();
        socket.getOutputStream().write(request);
        var input = new DataInputStream(socket.getInputStream());
        check(input.readUnsignedShort() == HEARTBEAT, "Unexpected control packet type");
        check(input.readInt() == HEARTBEAT_BYTES, "Unexpected heartbeat payload length");
        byte[] data = input.readNBytes(HEARTBEAT_BYTES);
        check(data.length == HEARTBEAT_BYTES, "Incomplete heartbeat reply");
        var reply = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        check(reply.get() == 0 && reply.getLong() == timestamp, "Heartbeat reply did not echo the request");
        check(reply.getLong() <= reply.getLong(), "Heartbeat processing timestamps moved backwards");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
