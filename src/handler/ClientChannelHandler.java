package handler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import org.xbill.DNS.*;

public class ClientChannelHandler {
    private final static int BUFFER_SIZE = 8 * 1024;

    private static final Logger logger = Logger.getAnonymousLogger();

    private static final int STAGE_INIT = 0;
    private static final int STAGE_SOCKS_HELLO_OK = 1;
    private static final int STAGE_TARGET_SENT_TO_REMOTE = 2;
    private static final int STAGE_REMOTE_CONNECTED = 3;

    private int port = 0;

    private Selector selector;
    private SocketChannel localSocketChannel;
    private SocketChannel remoteSocketChannel;
    private int stage;
    private boolean destroy = false;
    private List<byte[]> byteQueue = new LinkedList<byte[]>();
    private long lastExecuteTime = -1;
    SocketChannel dnsSocket;

    String remoteHost = "";
    int remotePort;

    public ClientChannelHandler(Selector selector, SocketChannel localSocketChannel, SocketChannel dnsSocket) {
        this.localSocketChannel = localSocketChannel;
        try {
            this.selector = selector;
            this.localSocketChannel.configureBlocking(false);
            this.localSocketChannel.register(selector, SelectionKey.OP_READ);
            this.dnsSocket = dnsSocket;
            stage = STAGE_INIT;
        } catch (IOException e) {
            logger.severe(e.getMessage());
        }
    }

    public void handle(SelectionKey key) {
//        if (lastExecuteTime != -1 && (System.currentTimeMillis() - lastExecuteTime) >= 10 * 1000) {
//            this.destroy = true;
//            return;
//        }
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel == this.localSocketChannel || channel == this.remoteSocketChannel) {
            try {
                if (key.isReadable()) {
                    read(key, channel);
                }
                else if (key.isWritable()) {
                    write(key, channel);
                }
                else if (key.isConnectable()) {
                    connect(key, channel);
                }
                lastExecuteTime = System.currentTimeMillis();
            } catch (Exception e) {
                this.destroy = true;
            }
        }
    }

    private void read(SelectionKey key, SocketChannel socketChannel) throws IOException {
        byte[] data = readData(socketChannel);
        byte[] fullData = data;
        if (data == null) {
            this.destroy = true;
            return;
        }

        if (stage == STAGE_INIT && socketChannel == this.localSocketChannel) {
            byte socksVer = data[0];
            if (socksVer != 5) {
                // socks5 version is wrong.
                System.out.println("SOCKS version is wrong");
                socketChannel.write(ByteBuffer.wrap(new byte[]{5, (byte) 255}));
                this.destroy = true;
            } else {
                // socks5 version is ok.
                socketChannel.write(ByteBuffer.wrap(new byte[]{5, 0}));
                stage = STAGE_SOCKS_HELLO_OK;
            }
        } else if (stage == STAGE_SOCKS_HELLO_OK && socketChannel == this.localSocketChannel) {
            data = Arrays.copyOfRange(data, 3, data.length);
            // connect remote proxy server.

            InetAddress address = null;

            if (data[0] == 1) {          //IP
                byte[] addr = new byte[]{data[1], data[2], data[3], data[4]};
                this.remotePort = (((0xFF & data[5]) << 8) + (0xFF & data[6]));
                address = InetAddress.getByAddress(addr);
            } else if (data[0] == 3) {     //DNS
                int len = 0xFF & data[1];
                byte nameBytes[] = new byte[len];
                System.arraycopy(data, 2, nameBytes, 0, len);
                String nameStr = new String(nameBytes, "UTF-8");
                Name name = org.xbill.DNS.Name.fromString(nameStr, Name.root);
                Record rec = Record.newRecord(name, Type.A, DClass.IN);
                Message msg = Message.newQuery(rec);
                dnsSocket.write(ByteBuffer.wrap(msg.toWire()));
                address = Address.getByName(nameStr);
                this.remotePort = (((0xFF & data[2 + len + 1]) << 8) + (0xFF & data[2 + len + 2]));
            } else {
                socketChannel.write(ByteBuffer.wrap(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 1, 1}));
            }

            try {
                remoteSocketChannel = SocketChannel.open();
                remoteSocketChannel.configureBlocking(false);
                remoteSocketChannel.register(this.selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ);
                remoteSocketChannel.connect(new InetSocketAddress(address, this.remotePort));
            } catch (Exception e) {
                logger.severe("fail to connect remote proxy server.");
                //fail to connect
                socketChannel.write(ByteBuffer.wrap(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 1, 1}));
                this.destroy = true;
                return;
            }
            // tell socks5 client, success to connect.
            fullData[1] = 0; //request granted
            socketChannel.write(ByteBuffer.wrap(fullData));

            // add the received data into queue.
            byteQueue.add(data);
            stage = STAGE_TARGET_SENT_TO_REMOTE;
        } else if (stage == STAGE_TARGET_SENT_TO_REMOTE && socketChannel == this.localSocketChannel) {
            // add the received data into queue
            // when this client is starting to connect proxy server but not connected.
            byteQueue.add(data);
        } else if (stage == STAGE_REMOTE_CONNECTED) {
            if (socketChannel == this.localSocketChannel) {
                writeToSocketChannel(data, this.remoteSocketChannel);
            } else {
                writeToSocketChannel(data, this.localSocketChannel);
            }
        }

    }

    private byte[] readData(SocketChannel socketChannel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
        int readBytes = socketChannel.read(buf);
        if (readBytes <= 0) {
            return null;
        }
        buf.flip();
        byte[] data = new byte[readBytes];
        buf.get(data);
        return data;
    }

    private void writeToSocketChannel(byte[] data, SocketChannel socketChannel) throws IOException {
        ByteBuffer dataBuf = ByteBuffer.wrap(data);
        while (dataBuf.remaining() > 0) {
            socketChannel.write(dataBuf);
        }
    }

    private void connect(SelectionKey key, SocketChannel socketChannel)
            throws IOException {
        if (this.remoteSocketChannel == socketChannel) {
            if (socketChannel.finishConnect()) {
                key.interestOps(SelectionKey.OP_READ);
                this.stage = STAGE_REMOTE_CONNECTED;
                // this client once connected proxy server and send the received data to proxy server.
                if (byteQueue.size() > 0) {
                    for (byte[] tmpData : byteQueue) {
                        writeToSocketChannel(tmpData, this.remoteSocketChannel);
                    }
                }
            }
        }
    }

    private void write(SelectionKey key, SocketChannel socketChannel)
            throws IOException {
    }

    public boolean isDestroy() {
        return destroy;
    }

    public void destroy() {
        if (this.localSocketChannel != null) {
            try {
                this.localSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (this.remoteSocketChannel != null) {
            try {
                this.remoteSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

}
