import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.xbill.DNS.*;

public class Main {

    private final static int BUFFER_SIZE = 8 * 1024;
    static int port;

    static Selector selector;
    private static ServerSocketChannel serverSocketChannel = null;

    static DatagramChannel dnsChannel;

    static Map<SocketChannel, ClientChannelHandler> clients = new HashMap<>();
    static Map<SocketChannel, ClientChannelHandler> remotes = new HashMap<>();
    static HashMap<Integer, ClientChannelHandler> dns = new HashMap<>();

    public static void main(String args[]) {
        if (args.length < 1) {
            System.err.println("No args were set. Port is 1080");
            port = 1080;
        } else {
            try {
                port = Integer.valueOf(args[0]);
                if (port < 0 || port > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException nfe) {
                System.err.println("Port value is not correct. Using default (1080)");
                port = 1080;
            }
        }
        try {
            //selector & serverSocketChannel settings:
            selector = Selector.open();
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.bind(new InetSocketAddress(port));
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

            //dns settings:
            String dnsServers[] = ResolverConfig.getCurrentConfig().servers();
            dnsChannel = DatagramChannel.open();
            dnsChannel.configureBlocking(false);

            if (dnsServers.length > 2) {
                dnsChannel.connect(new InetSocketAddress(dnsServers[2], 53));
            } else {
                dnsChannel.connect(new InetSocketAddress("8.8.8.8", 53));
            }
            dnsChannel.register(selector, SelectionKey.OP_READ);
            startServer();
        } catch (IOException e) {
            try {
                serverSocketChannel.close();
                dnsChannel.close();
                selector.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private static void startServer() throws IOException {
        System.out.println("Server started");
        try {
            while (true) {
                selector.select();
                for (SelectionKey key : selector.selectedKeys()) {
                    if (key.isValid()) {
                        if (key.isAcceptable() && serverSocketChannel == key.channel()) {
                            SocketChannel socketChannel = serverSocketChannel.accept();
                            if (socketChannel != null) {
                                ClientChannelHandler handler = new ClientChannelHandler(socketChannel);
                                clients.put(socketChannel, handler);
                                socketChannel.register(selector, SelectionKey.OP_READ);
                            }
                        } else if (key.isConnectable()) ((SocketChannel) key.channel()).finishConnect();
                        else if (key.isReadable()) {
                            List<ClientChannelHandler> toDestroyList = new LinkedList<>();
                            if (key.channel() instanceof SocketChannel) { //not a DatagramChannel
                                SocketChannel socketChannel = (SocketChannel) key.channel();
                                ClientChannelHandler handler = clients.get(socketChannel);
                                if (handler != null) {
                                    handler.localRead();
                                } else {
                                    handler = remotes.get(socketChannel);
                                    if (handler != null) {
                                        handler.remoteRead();
                                    }
                                }
                                if (handler != null)
                                    if (handler.isDestroy()) {
                                        toDestroyList.add(handler);
                                    }
                            } else if (key.channel().equals(dnsChannel)) {  //not a SocketChannel
                                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                                int length = dnsChannel.read(buffer);
                                if (length > 0) {
                                    Message message = new Message(buffer.array());
                                    Record[] records = message.getSectionArray(1);
                                    for (Record record : records) {
                                        if (record instanceof ARecord) {
                                            ARecord aRecord = (ARecord) record;
                                            int id = message.getHeader().getID();
                                            ClientChannelHandler handler = dns.get(id);
                                            if (handler != null && aRecord.getAddress() != null) {
                                                handler.connect(aRecord.getAddress());
                                                if (handler.isDestroy()) {
                                                    toDestroyList.add(handler);
                                                }
                                            }
                                            dns.remove(id);
                                            break;
                                        }
                                    }
                                    buffer.clear();
                                }
                            }
                            for (ClientChannelHandler handler : toDestroyList) {
                                handler.destroy();
                            }
                        }

                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            for (SocketChannel channel : remotes.keySet()) {
                channel.close();
            }
            for (SocketChannel channel : clients.keySet()) {
                channel.close();
            }
            serverSocketChannel.close();
            dnsChannel.close();
            selector.close();
        }
    }

}
