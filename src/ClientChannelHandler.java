import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.xbill.DNS.*;


class ClientChannelHandler {
    private final static int BUFFER_SIZE = 8 * 1024;

    private final static int SOCKS5 = 0x05;
    private final static int AUTH_NUM = 0x01;               //greeting message
    private final static int NO_AUTH = 0x00;                //greeting message
    private final static int TCP_IP_STREAM_CONNECTION = 0x01;
    private final static int RESERVED = 0x00;

    private final static int IPV4 = 0x01;
    private final static int DNS = 0x03;

    private final static int SIZE_GREETINGS = 2;
    private final static int SIZE_CONNECTION = 10;
    private final static int SIZE_IP = 4;
    private final static int REQ_GRANTED = 0x00;
    private final static int ERROR = 0x01;

    private SocketChannel localSocketChannel;
    private SocketChannel remoteSocketChannel;

    private boolean destroy = false;

    private static final int STAGE_DEFAULT = 0;
    private static final int STAGE_REGISTERED = 1;
    private static final int STAGE_CONNECTED = 2;

    private int stage;

    private InetAddress address;
    private int remotePort;

    ClientChannelHandler(SocketChannel socketChannel) throws IOException {
        this.localSocketChannel = socketChannel;
        socketChannel.configureBlocking(false);
        stage = STAGE_DEFAULT;
    }

    void remoteRead() throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            if (remoteSocketChannel.isConnected()) {
                int numBytes = remoteSocketChannel.read(byteBuffer);
                if (numBytes > 0) {
                    localSocketChannel.write(ByteBuffer.wrap(byteBuffer.array(), 0, numBytes));
                } else if (numBytes == -1) {
                    this.destroy = true;
                }
            }
        } catch (IOException e) {
            if (localSocketChannel.isConnected())
                localSocketChannel.close();
            if (remoteSocketChannel.isConnected())
                remoteSocketChannel.close();
        } finally {
            byteBuffer.clear();
        }
    }

    void localRead() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            int bytes = localSocketChannel.read(byteBuffer);
            byteBuffer.flip();
            if (stage == STAGE_DEFAULT) {
                if (bytes > 0) {
                    int version = byteBuffer.get();
                    if (version != SOCKS5) {
                        System.out.println("Incorrect version: " + version);
                        throw new IOException();
                    }
                    int authNum = byteBuffer.get();
                    int authMethod = byteBuffer.get();
                    if (authNum != AUTH_NUM || authMethod != NO_AUTH) {
                        throw new IOException();
                    }

                    ByteBuffer buf = ByteBuffer.allocate(SIZE_GREETINGS);
                    buf.put((byte) SOCKS5);
                    buf.put((byte) NO_AUTH);
                    localSocketChannel.write(ByteBuffer.wrap(buf.array()));
                    buf.clear();
                    stage = STAGE_REGISTERED;
                } else if (bytes == -1) {
                    throw new IOException();
                }
            } else if (stage == STAGE_REGISTERED) {
                if (bytes > 0) {
                    int version = byteBuffer.get();
                    if (version != SOCKS5) {
                        System.out.println("Incorrect version: " + version);
                        throw new IOException();
                    }

                    int commandCode = byteBuffer.get();
                    int reserved = byteBuffer.get();
                    if (commandCode != TCP_IP_STREAM_CONNECTION || reserved != RESERVED) {
                        throw new IOException();
                    }

                    int addressType = byteBuffer.get();
                    if (addressType == IPV4) {
                        byte[] ip = new byte[SIZE_IP];
                        byteBuffer.get(ip);
                        address = InetAddress.getByAddress(ip);
                    } else if (addressType == DNS) {
                        int len = byteBuffer.get();
                        byte[] byteName = new byte[len];
                        byteBuffer.get(byteName);
                        String nameStr = new String(byteName);

                        Name name = Name.fromString(nameStr, Name.root);
                        Record record = Record.newRecord(name, Type.A, DClass.IN);
                        Message message = Message.newQuery(record);
                        Main.dnsChannel.write(ByteBuffer.wrap(message.toWire()));
                        Main.dns.put(message.getHeader().getID(), this);
                    } else {
                        throw new IOException();
                    }

                    remotePort = byteBuffer.getShort();

                    if (addressType == IPV4) {
                        connect();
                    }

                } else if (bytes == -1) {
                    throw new IOException();
                }
            } else if (stage == STAGE_CONNECTED) {
                if (localSocketChannel.isConnected()) {
                    if (bytes > 0) {
                        remoteSocketChannel.write(ByteBuffer.wrap(byteBuffer.array(), 0, bytes));
                    } else if (bytes == -1) {
                        throw new IOException();
                    }
                }
            }
        } catch (IOException e) {
            this.destroy = true;
            this.destroy();
        } finally {
            byteBuffer.clear();
        }
    }

    void connect(InetAddress address) throws IOException {
        this.address = address;
        this.connect();
    }

    private void connect() throws IOException {
        try {
            if (localSocketChannel.isConnected()) {
                remoteSocketChannel = SocketChannel.open(new InetSocketAddress(this.address, remotePort));
                ByteBuffer buffer = ByteBuffer.allocate(SIZE_CONNECTION);

                buffer.put((byte) SOCKS5);
                buffer.put(remoteSocketChannel.isConnected() ? (byte) REQ_GRANTED : (byte) ERROR);
                buffer.put((byte) RESERVED);
                buffer.put((byte) IPV4);
                buffer.put(InetAddress.getLocalHost().getAddress());
                buffer.putShort((short) Main.port);

                localSocketChannel.write(ByteBuffer.wrap(buffer.array()));
                buffer.clear();

                if (!remoteSocketChannel.isConnected()) {
                    this.destroy = true;
                    return;
                }
                remoteSocketChannel.configureBlocking(false);
                remoteSocketChannel.register(Main.selector, SelectionKey.OP_READ | SelectionKey.OP_CONNECT);
                Main.remotes.put(remoteSocketChannel, this);
                stage = STAGE_CONNECTED;
            }
        } catch (ConnectException e) {
            System.out.println(e.getMessage());
            if (localSocketChannel.isConnected())
                localSocketChannel.close();
            if (remoteSocketChannel != null && remoteSocketChannel.isConnected())
                remoteSocketChannel.close();
        } finally {
            if (!this.localSocketChannel.isConnected()) this.destroy = true;
        }
    }

    boolean isDestroy() {
        return destroy;
    }

    void destroy() {
        if (this.localSocketChannel != null) {
            try {
                Main.clients.remove(this.localSocketChannel);
                this.localSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (this.remoteSocketChannel != null) {
            try {
                Main.remotes.remove(this.remoteSocketChannel);
                this.remoteSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
}
