package org.openhab.binding.irobot.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class RawMQTT {
    private Socket socket;

    public static class Packet {
        public byte message;
        public byte[] payload;

        Packet(byte msg, byte[] data) {
            message = msg;
            payload = data;
        }

        public boolean isValidPasswdPacket() {
            return message == PasswdPacket.MESSAGE && payload.length >= PasswdPacket.HEADER_SIZE;
        }
    };

    public static class PasswdPacket extends Packet {
        static final byte MESSAGE = (byte) 0xF0; // MQTT Reserved
        static final int MAGIC = 0x293bccef;
        static final byte HEADER_SIZE = 5;
        private ByteBuffer buffer;

        public PasswdPacket(Packet raw) {
            super(raw.message, raw.payload);
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        public int getMagic() {
            return buffer.getInt(0);
        }

        public byte getStatus() {
            return buffer.get(4);
        }

        public String getPassword() {
            if (getStatus() != 0) {
                return null;
            }

            int length = payload.length - HEADER_SIZE;
            byte[] passwd = new byte[length];

            buffer.position(HEADER_SIZE);
            buffer.get(passwd);

            return new String(passwd);
        }

    }

    private static class MQTTTrustManager implements X509TrustManager {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] certs, String authMethod) throws CertificateException {
            /*
             * TODO: Retrieve Roomba CA certificate and implement proper verification
             * logger.debug("Auth method: " + authMethod);
             * for (X509Certificate cert : certs) {
             * logger.debug("Cert: " + cert.toString());
             * }
             */
        }
    }

    public RawMQTT(InetAddress host, int port) throws KeyManagementException, NoSuchAlgorithmException, IOException {
        SSLContext sc = SSLContext.getInstance("SSL");

        sc.init(null, new TrustManager[] { new MQTTTrustManager() }, new java.security.SecureRandom());
        socket = sc.getSocketFactory().createSocket(host, 8883);
    }

    public void close() throws IOException {
        socket.close();
    }

    public void requestPassword() throws IOException {
        final byte[] passwdRequest = new byte[7];
        ByteBuffer buffer = ByteBuffer.wrap(passwdRequest).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(PasswdPacket.MESSAGE);
        buffer.put(PasswdPacket.HEADER_SIZE);
        buffer.putInt(PasswdPacket.MAGIC);
        buffer.put((byte) 0);

        socket.getOutputStream().write(passwdRequest);
    }

    public Packet readPacket() throws IOException {
        byte[] header = new byte[2];
        int l = receive(header);

        if (l < header.length) {
            return null;
        }

        byte[] data = new byte[header[1]];
        l = receive(data);

        if (l != header[1]) {
            return null;
        } else {
            return new Packet(header[0], data);
        }
    }

    private int receive(byte[] data) throws IOException {
        int received = 0;
        byte[] buffer = new byte[1024];
        InputStream in = socket.getInputStream();

        while (received < data.length) {
            int l = in.read(buffer);

            if (l <= 0) {
                break; // EOF
            }

            if (received + l > data.length) {
                l = data.length - received;
            }

            System.arraycopy(buffer, 0, data, received, l);
            received += l;
        }

        return received;
    }
}
