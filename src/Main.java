import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.xbill.DNS.*;

import handler.*;

public class Main {

    private final static int BUFFER_SIZE = 8 * 1024;
    private static int port;
    private static InetAddress host;

    private static Selector selector;
    private static ServerSocketChannel serverSocketChannel = null;

    private static SocketChannel dnsSocket;

    private static Map<SocketChannel, SocketChannel> clients = new HashMap<>();
    private static Map<SocketChannel, SocketChannel> servers = new HashMap<>();


    public static void main(String args[]) {
        if (args.length < 2) {
            System.err.println("Not enough args\nShould be <host> <port>");
            System.exit(-1);
        }
        try {
            port = Integer.valueOf(args[1]);
            host = InetAddress.getByName(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Incorrect args");
        } catch (UnknownHostException e) {
            System.err.println("Unknown host " + args[2]);
        }
        try {
            selector = Selector.open();
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.bind(new InetSocketAddress(host, port));
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            String dnsServers[] = ResolverConfig.getCurrentConfig().servers();
            dnsSocket = SocketChannel.open();
            dnsSocket.configureBlocking(false);
            if (dnsServers == null) dnsSocket.connect(new InetSocketAddress("8.8.8.8", 53));
            else dnsSocket.connect(new InetSocketAddress(dnsServers[0], 53));
            dnsSocket.register(selector, SelectionKey.OP_READ | SelectionKey.OP_CONNECT);
            while (!dnsSocket.isConnected()) dnsSocket.finishConnect();
            startServer();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private static final byte[] OK = new byte[]{0x05, 0x00, 0x00, 0x03, /*dns name*/0x00, /*port*/0x00, 0x00, 0x00};
    private static final byte[] GREETING_OK = new byte[]{0x05, (byte) 0xFF};

    private static byte[] CURRENT_RESP = null;

    private static void startServer() throws IOException {
        List<ClientChannelHandler> handlerList = new LinkedList<ClientChannelHandler>();
        System.out.println("Server started");
        while (true) {
            selector.select();
            for (SelectionKey key : selector.selectedKeys()) {
                if (key.isValid()) {
                    try {
                        if (key.isConnectable()) System.out.println("hui");
                        if (key.isAcceptable()) {
                            ServerSocketChannel serverSocket = (ServerSocketChannel) key.channel();
                            SocketChannel socketChannel = serverSocket.accept();
                            if (socketChannel != null)
                                handlerList.add(new ClientChannelHandler(selector, socketChannel, dnsSocket));
                            //   accept(key);
                        } else {
                            List<ClientChannelHandler> toDestroyList = new LinkedList<>();
                            for (ClientChannelHandler handler : handlerList) {
                                handler.handle(key);
                                if (handler.isDestroy()) {
                                    toDestroyList.add(handler);
                                }
                            }
                            for (ClientChannelHandler handler : toDestroyList) {
                                handler.destroy();
                                handlerList.remove(handler);
                            }
                        }
//                        } else if (key.isConnectable()) {
//                            connect(key);
//                        } else if (key.isWritable()) {
//                            write(key);
//                        } else if (key.isReadable()) {
//                            read(key);
//                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        close(key);
                    }
                }
            }
        }
    }
}
/*
    static SocketChannel dstChannel;

    private static void accept(SelectionKey key) throws IOException {
        SocketChannel newChannel = ((ServerSocketChannel) key.channel()).accept();
        if (newChannel != null) {
            newChannel.configureBlocking(false);
            newChannel.register(key.selector(), SelectionKey.OP_READ);

            SocketChannel tmpChannel = SocketChannel.open();
            tmpChannel.configureBlocking(false);
            tmpChannel.connect(new InetSocketAddress(port));
            tmpChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_CONNECT);

            dstChannel = tmpChannel;
        }
    }

    private static void read(SelectionKey key) throws IOException {
        SocketChannel channel = ((SocketChannel) key.channel());
        Attachment attachment = ((Attachment) key.attachment());
        if (attachment == null) {
            key.attach(attachment = new Attachment());
            attachment.in = ByteBuffer.allocate(BUFFER_SIZE);
        }
        if (channel.read(attachment.in) < 1) {
            close(key);
        } else if (attachment.peer == null) {
            readHeader(key, attachment);
        } else {
            //proxy
            attachment.peer.interestOps(attachment.peer.interestOps() | SelectionKey.OP_WRITE);
            // а у первого убираем интерес прочитать, т.к пока не записали текущие данные, читать ничего не будем
            key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
            // готовим буфер для записи
            attachment.in.flip();
        }
    }

    private static void readHeader(SelectionKey key, Attachment attachment) throws IllegalStateException, IOException {
        byte[] ar = attachment.in.array();
        if (ar[attachment.in.position() - 1] == 0) {
            // Если последний байт \0 это конец ID пользователя.
            if (ar[0] != 5 || ar[1] != 1 || (ar[3] != 3 && ar[3] != 0 && ar[3] != 1)) {
                throw new IllegalStateException("Bad Request");
            }

            //TODO: add case: ar[3] == 1

            int dnsNameLen = ar[4];

            StringBuilder sb = new StringBuilder();
            String host = "";


            for (int i = 0; i < dnsNameLen; i++) {
                int ch = ar[5 + i];
                sb.append((char) ch);
            }
            host = sb.toString();

            int c = ar[5 + dnsNameLen];
            int p = (c << 8);
            c = ar[6 + dnsNameLen];
            p += c;

            switch (ar[3]) {
                case 0:             //Greeting
                    byte greeting[] = new byte[]{0x05, 0x00};
                    dstChannel.write(ByteBuffer.wrap(greeting));
                    break;
                case 1:             //IPv4
                    byte ok[] = ar;
                    ok[1] = 0x00;
                    dstChannel.write(ByteBuffer.wrap(ok));
                    break;
                case 3:             //DNS
                    Name addr = Name.fromString(host);

                    break;
                default:
                    throw new IllegalStateException("Bad Request");
            }
        }
    }

    private static void write(SelectionKey key) throws IOException {
        // Закрывать сокет надо только записав все данные
        SocketChannel channel = ((SocketChannel) key.channel());
        Attachment attachment = ((Attachment) key.attachment());
        if (channel.write(attachment.out) == -1) {
            close(key);
        } else if (attachment.out.remaining() == 0) {
            if (attachment.peer == null) {
                // Дописали что было в буфере и закрываемся
                close(key);
            } else {
                // если всё записано, чистим буфер
                attachment.out.clear();
                // Добавялем ко второму концу интерес на чтение
                attachment.peer.interestOps(attachment.peer.interestOps() | SelectionKey.OP_READ);
                // А у своего убираем интерес на запись
                key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);
            }
        }
    }

    private static void connect(SelectionKey key) throws IOException {
        SocketChannel channel = ((SocketChannel) key.channel());
        Attachment attachment = ((Attachment) key.attachment());
        // Завершаем соединение
        channel.finishConnect();
        // Создаём буфер и отвечаем OK
        attachment.in = ByteBuffer.allocate(BUFFER_SIZE);
        attachment.in.put(CURRENT_RESP).flip();
        if (attachment.peer != null) {
            attachment.out = ((Attachment) attachment.peer.attachment()).in;
            ((Attachment) attachment.peer.attachment()).out = attachment.in;
            // Ставим второму концу флаги на на запись и на чтение
            // как только она запишет OK, переключит второй конец на чтение
            attachment.peer.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
        }
        key.interestOps(0);
    }

    private static void close(SelectionKey key) throws IOException {
        key.cancel();
        key.channel().close();
        SelectionKey peerKey = null;
        if (key.attachment() != null)
            peerKey = ((Attachment) key.attachment()).peer;
        if (peerKey != null) {
            ((Attachment) peerKey.attachment()).peer = null;
            if ((peerKey.interestOps() & SelectionKey.OP_WRITE) == 0) {
                if (((Attachment) peerKey.attachment()).out != null)
                    ((Attachment) peerKey.attachment()).out.flip();
            }
            peerKey.interestOps(SelectionKey.OP_WRITE);
        }
    }
}*/
