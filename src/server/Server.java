package server;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;


import filehelper.FileDescription;

class SendTask implements Runnable {
    private SocketChannel channel;
    private ByteBuffer buff;
    private SelectionKey key;
    public SendTask(SocketChannel channel, ByteBuffer buff, SelectionKey key) {
        this.channel = channel;
        this.buff = buff;
        this.key = key;
    }
    @Override
    public void run() {
        synchronized (Server.writeMap) {
            try {
                if (buff.remaining() == 0) {
                    Server.writeMap.remove(key);
                    channel.close();
                } else {
                    channel.write(buff);
                }
            } catch (IOException e) {

            }
        }
    }
}

public class Server {
    public static int BUF_SIZE = 4096;
    private static Map<String, FileDescription> fds = new HashMap<String, FileDescription>();
    private static Map<String, Integer> mapIpPort = new HashMap<String, Integer>();
    static Logger logger = Logger.getLogger(Server.class.getName());
    public static Map<SelectionKey, ByteBuffer> readMap = new HashMap<SelectionKey, ByteBuffer>();
    public static Map<SelectionKey, ByteBuffer> writeMap = new HashMap<SelectionKey, ByteBuffer>();
    public static ExecutorService pool = Executors.newFixedThreadPool(4);
    /*
     * The key for the following two map is of the form:
     * <file_name><fragment_num>.
     * The value is of the form <IP>:<PORT>.
     */
    private static Map<String, ArrayList<String>> ips = new HashMap<String, ArrayList<String>>();

    public static void main (String[] args) {
        if (args.length < 1) {
            System.err.print("Usage: java Server <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);

        logger.info("Starting server...");

        /*
         * Data structures used for publish operation
         */
        Map<SelectionKey, ByteBuffer> lengthMap = new HashMap<SelectionKey, ByteBuffer>();
        Map<SelectionKey, Boolean> readLength = new HashMap<SelectionKey, Boolean>();

        /*
         * Data structures used for download operation
         */
        Map<SelectionKey, ByteBuffer> fileNamesMap = new HashMap<SelectionKey, ByteBuffer>();

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
                        register(selector, (ServerSocketChannel)key.channel());

                    } else if (key.isConnectable()) {
                        // a connection was established with a remote server.

                    } else if (key.isReadable()) {
                        // a channel is ready for reading
                        SocketChannel channel = (SocketChannel) key.channel();
                        if (readLength.containsKey(key)) {

                            /*
                             * We are going to read all the information about publish stuff (the
                             * length of the fd and the file description itself).
                             */

                            boolean readLengthVal = readLength.get(key);
                            if (readLengthVal) {
                                ByteBuffer lengthBuffer = lengthMap.get(key);
                                int br = channel.read(lengthBuffer);
                                if (lengthBuffer.remaining() == 0) {
                                    readLength.put(key, false);
                                    ByteBuffer dataBuffer = ByteBuffer.allocate(lengthBuffer.getInt(0));
                                    dataBuffer.clear();
                                    lengthBuffer.clear();
                                    readMap.put(key, dataBuffer);
                                }
                            } else {
                                ByteBuffer dataBuffer = readMap.get(key);
                                int m = channel.read(dataBuffer);
                                if (dataBuffer.remaining() == 0) {
                                    ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(dataBuffer.array()));
                                    try {
                                        FileDescription fd = (FileDescription)ois.readObject();
                                        readLength.remove(key);
                                        readMap.remove(key);

                                        /*
                                         * Insert into the maps this file
                                         */
                                        fds.put(fd.fileName, fd);
                                        fragmentFile(fd, channel);
                                        logger.info("Served publish request for file: " + fd.fileName);
                                    } catch (ClassNotFoundException e) {
                                        e.printStackTrace();
                                    }
                                    channel.close();
                                    key.cancel();
                                } else {
                                    //readMap.put(key, dataBuffer);
                                }
                            }
                        } else if (readMap.containsKey(key)) {
                            ByteBuffer buf = readMap.get(key);
                            try {
                                int bytesRead = channel.read(buf);
                                if (buf.remaining() == 0) {

                                     if ((char)(buf.array()[0]) == 'p') {
                                         /*
                                          * This is for publish.
                                         */
                                         lengthMap.put(key, ByteBuffer.wrap(new byte[4]));
                                         int clPort = buf.getInt(1);
                                         mapIpPort.put(channel.socket().getInetAddress().toString().substring(1), clPort);
                                         readLength.put(key, true);
                                     } else if ((char)(buf.array()[0]) == 'u') {
                                         /**
                                          * This if for update, a message of the form u<fname_size><port><fname>
                                          */
                                         if (buf.array().length == 5) {
                                             logger.info("Received update request");
                                             ByteBuffer wholePayload = ByteBuffer.allocate(buf.getInt(1) + 9);
                                             wholePayload.put(buf.array());
                                             readMap.put(key, wholePayload);
                                         } else {
                                             int clientPort = buf.getInt(5);
                                             String clientIp = channel.socket().getInetAddress().toString().substring(1);
                                             String fName = "";
                                             for (int i = 9; i < buf.array().length; i++) {
                                                 fName += (char)buf.array()[i];
                                             }
                                             mapIpPort.put(clientIp, clientPort);
                                             FileDescription fd = fds.get(fName);
                                             fragmentFile(fd, channel);
                                             logger.info("Served update request");
                                             channel.close();
                                             key.cancel();
                                         }
                                     }
                                     else if ((char)(buf.array()[0]) == 'd') {
                                         /*
                                          * This is for download.
                                          */
                                         if (!fileNamesMap.containsKey(key)) {
                                             logger.info("Download request received.");
                                             /*
                                              * Set up buffer for reading file name
                                              */
                                             ByteBuffer fileNameBuff = ByteBuffer.allocate(readMap.get(key).getInt(1));
                                             fileNamesMap.put(key, fileNameBuff);
                                             ByteBuffer newBuff = ByteBuffer.allocate(5 + fileNameBuff.array().length);
                                             newBuff.put(readMap.get(key).array());
                                             readMap.put(key, newBuff);
                                         } else if (readMap.get(key).remaining() == 0){
                                               ByteBuffer buff = readMap.get(key);
                                             /*
                                             * Finished reading file name
                                             */
                                             byte[] fileNameBytes = new byte[buff.getInt(1)];
                                             for (int i = 0; i < fileNameBytes.length; i++) {
                                                 fileNameBytes[i] = buff.get(5+i);
                                             }
                                             String s = new String(fileNameBytes, Charset.forName("UTF-8"));
                                             logger.info("Download request, for file: " + s);
                                             FileDescription fileDescription = fds.get(s);

                                             /*
                                              * Listen to write events as well.
                                              */
                                             key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                                             key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);

                                             if (fileDescription != null) {
                                                 /*
                                                  * Set up what we are going to write on socket using a channel
                                                  */
                                                 ByteBuffer wrap = getByteObject(fileDescription);

                                                 Map<String, ArrayList<String>> fileClients = getMapFragments(fileDescription);
                                                 ByteBuffer wrap2 = getByteObject(fileClients);

                                                 ByteBuffer finalWrap = ByteBuffer.allocate(1 + wrap.array().length + wrap2.array().length);
                                                 ByteBuffer confirmationFileFound = ByteBuffer.allocate(1);
                                                 confirmationFileFound.put(new String("y").getBytes());
                                                 finalWrap.put(confirmationFileFound.array());
                                                 finalWrap.put(wrap.array());
                                                 finalWrap.put(wrap2.array());
                                                 finalWrap.flip();
                                                 writeMap.put(key, finalWrap);
                                                 /*
                                                  * Clean up the reading variables
                                                  */
                                                 readMap.remove(key);
                                                 fileNamesMap.remove(key);
                                                 logger.info("Served download request, file found.");

                                             } else {
                                                ByteBuffer tempBuff = ByteBuffer.allocate(1);
                                                String tempS = "n";
                                                tempBuff.put(tempS.getBytes());
                                                tempBuff.flip();
                                                writeMap.put(key, tempBuff);
                                                logger.error("Served download request, file not found");
                                             }
                                         }
                                     }
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        } else {
                            ByteBuffer buf = ByteBuffer.wrap(new byte[5]);
                            int nr = channel.read(buf);

                            if (nr < 0) {
                                channel.close();
                            } else {
                                readMap.put(key, buf);
                            }
                        }
                    } else if (key.isWritable()) {
                        // a channel is ready for writing
                        SocketChannel channel = (SocketChannel)key.channel();
                        ByteBuffer buff = writeMap.get(key);

                        if (buff.remaining() == 0) {
                            key.cancel();
                        } else {
                            pool.submit(new SendTask(channel, buff, key));
                        }
                    }
                    keyIterator.remove();

                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    private static void register(Selector selector, ServerSocketChannel serverSocket)
            throws IOException {

        SocketChannel client = serverSocket.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
        logger.info("registered new connection");
    }
    private static void fragmentFile(FileDescription fd, SocketChannel s) {
        int num_of_fragments = fd.dimension % fd.firstFragmentSize == 0 ?
                fd.dimension / fd.firstFragmentSize : fd.dimension / fd.firstFragmentSize + 1;
        for (int i = 0; i < num_of_fragments; i++) {
            if (ips.containsKey(fd.fileName+i)) {
                ArrayList<String> value = ips.get(fd.fileName + i);
                value.add(s.socket().getInetAddress().toString().substring(1) + ":" + mapIpPort.get(s.socket().getInetAddress().toString().substring(1)));
            } else {
                ArrayList<String> value = new ArrayList<String> ();
                value.add(s.socket().getInetAddress().toString().substring(1) + ":" + mapIpPort.get(s.socket().getInetAddress().toString().substring(1)));
                ips.put(fd.fileName + i, value);
            }
        }
    }
    private static Map<String, ArrayList<String>> getMapFragments(FileDescription fd) {
        Map<String, ArrayList<String>> result = new HashMap<String, ArrayList<String>>();
        int num_of_fragments = fd.dimension % fd.firstFragmentSize == 0 ?
                fd.dimension / fd.firstFragmentSize : fd.dimension / fd.firstFragmentSize + 1;
        for (int i = 0; i < num_of_fragments; i++) {
            String s = fd.fileName + i;
            result.put(s, ips.get(s));
        }
        return result;
    }
    private static ByteBuffer getByteObject(Object o) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = 0; i < 4; i++) baos.write(0);
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(baos);
            oos.writeObject(o);
            oos.close();
            ByteBuffer wrap = ByteBuffer.wrap(baos.toByteArray());
            wrap.putInt(0, baos.size() - 4);
            return wrap;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
