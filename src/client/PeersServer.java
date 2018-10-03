package client;

import client.Client;
import client.UploadWorker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class PeersServer implements Runnable {
    private int port;
    public PeersServer(int port) {
        this.port = port;
    }
    @Override
    public void run() {
        /*
         * A packet for downloading a file will be of the form d<numbytes><filanem><fragment_num>
         */
        try {
            ServerSocketChannel sockServChan = ServerSocketChannel.open();
            sockServChan.socket().bind(new InetSocketAddress(port));
            sockServChan.configureBlocking(false);
            Selector selector = Selector.open();
            SelectionKey k = sockServChan.register(selector, SelectionKey.OP_ACCEPT);
            while (true) {
                int readyChannels = selector.select();
                if (readyChannels == 0) continue;

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {

                    SelectionKey key = keyIterator.next();

                    if (key.isAcceptable()) {
                        // a connection was accepted by a ServerSocketChannel.
                        register(selector, (ServerSocketChannel) key.channel());

                    } else if (key.isConnectable()) {
                        // a connection was established with a remote server.

                    } else if (key.isReadable() && key.isValid()) {
                        /*
                         * For downloading a file, this peer will receive a message of the form:
                         * <request_size><file_name><fragment_num>
                         */
                        SocketChannel sockChan = (SocketChannel)key.channel();

                        /*
                         * Get the fileName length
                         */
                        ByteBuffer header = ByteBuffer.allocate(4);
                        while (header.remaining() > 0) {
                            sockChan.read(header);
                        }

                        int fileNameLength = header.getInt(0);
                        ByteBuffer fileName = ByteBuffer.allocate(fileNameLength);
                        ByteBuffer fragmentNum = ByteBuffer.allocate(4);
                        while (fileName.remaining() > 0) {
                            sockChan.read(fileName);
                        }
                        while (fragmentNum.remaining() > 0) {
                            sockChan.read(fragmentNum);
                        }

                        /*
                         * Send this task of serving the File in the work pool
                         */
                        Client.logger.info("Received request for downloading file " + new String(fileName.array()));
                        Client.pool.submit(new UploadWorker(key, new String(fileName.array()), fragmentNum.getInt(0)));
                        key.cancel();

                    } else if (key.isWritable()) {

                    }
                    keyIterator.remove();
                }
            }
            //sockServChan.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void register(Selector selector, ServerSocketChannel serverSocket)
            throws IOException {

        SocketChannel client = serverSocket.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }
}
