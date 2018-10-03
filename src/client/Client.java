package client;

import filehelper.FileDescription;
import org.apache.log4j.Logger;

import java.io.*;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


class DownloadTask implements Runnable{
    private String remotePeerIp;
    private int remotePeerPort;
    private FileDescription fd;
    private int numFragment;
    private ConcurrentHashMap<String, ByteBuffer> fileMap;
    public static Lock receivedAllCheck = new ReentrantLock();
    private ArrayList<String> clients;

    public DownloadTask(ConcurrentHashMap<String, ByteBuffer> fileMap, FileDescription fd, int numFragment,
                        String remotePeerIp, int remotePeerPort, ArrayList<String> clients) {
        this.fileMap = fileMap;
        this.remotePeerIp = remotePeerIp;
        this.remotePeerPort = remotePeerPort;
        this.fd = fd;
        this.numFragment = numFragment;
        this.clients = clients;
    }
    @Override
    public void run() {
        SocketChannel sockChan = null;
        try {
            sockChan = SocketChannel.open();
            try {
                sockChan.connect(new InetSocketAddress(remotePeerIp, remotePeerPort));
            } catch (ConnectException e) {
                /**
                 * The client is off for now, we should select another one.
                 * We will choose the first available client.
                 */
                int i = 0;
                for (i = 0; i < clients.size(); i++) {
                    String[] peerData = clients.get(i).split(":");
                    try {
                        sockChan.connect(new InetSocketAddress(peerData[0], Integer.parseInt(peerData[1])));
                        break;
                    } catch (ConnectException e2) {
                        continue;
                    }
                }
                if (i == clients.size()) {
                    /**
                     * No client available for the fragment
                     */
                    return;
                }
            }
            sockChan.configureBlocking(false);

            /**
             * Request file from peer
             */
            ByteBuffer downloadRequest = ByteBuffer.allocate(4 + this.fd.fileName.length() + 4);
            downloadRequest.putInt(this.fd.fileName.length());
            downloadRequest.put(this.fd.fileName.getBytes());
            downloadRequest.putInt(this.numFragment);
            downloadRequest.flip();
            while (downloadRequest.remaining() > 0) {
                sockChan.write(downloadRequest);
            }

            /**
             * This peer will receive a response of the form:
             * <fragment_size><file_fragment>
             */
            ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
            while (lengthBuffer.remaining() > 0)
                sockChan.read(lengthBuffer);
            ByteBuffer fileFragment = ByteBuffer.allocate(lengthBuffer.getInt(0));
            while (fileFragment.remaining() > 0) {
                sockChan.read(fileFragment);
            }
            /*
             * Check whether the whole file has been received
             */
            receivedAllCheck.lock();
            fileMap.put(this.fd.fileName+this.numFragment, fileFragment);
            int num_of_fragments = this.fd.dimension % UploadWorker.FRAGMENT_SIZE == 0 ?
                        this.fd.dimension / UploadWorker.FRAGMENT_SIZE : this.fd.dimension / UploadWorker.FRAGMENT_SIZE + 1;
            boolean allArePresent = true;
            for (int i = 0; i < num_of_fragments; i++) {
                if (!fileMap.containsKey(this.fd.fileName + i)) {
                    allArePresent = false;
                    break;
                }
            }
            if (allArePresent) {
                /*
                 * Notify the master thread that all of the fragments were received
                 */
                synchronized(Client.o) {
                    Client.o.notify();
                }
            }
            receivedAllCheck.unlock();
            sockChan.close();

        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}

/*
 * Such a worker will deal with sending a particular fragment
 */
class UploadWorker implements Runnable{
    private SelectionKey receiverKey;
    private SocketChannel receiver;
    private String fileName;
    private int num_of_fragment;
    public static final int FRAGMENT_SIZE = 4096;
    public static Object syncObj = new Object();

    public UploadWorker (SelectionKey receiver, String fileName, int num_of_fragment) {
        this.receiver = (SocketChannel)receiver.channel();
        this.receiverKey = receiver;
        this.fileName = fileName;
        this.num_of_fragment = num_of_fragment;
    }

    @Override
    public void run() {
        File file = Client.mapNameToPath.get(fileName);
        try {
            Client.logger.info("Serving fragment " + this.num_of_fragment + " of file " + this.fileName);
            RandomAccessFile aFile = new RandomAccessFile(file.getAbsolutePath(), "rw");
            FileChannel inChannel = aFile.getChannel();
            long fSize = inChannel.size();
            long offset = (long)FRAGMENT_SIZE * num_of_fragment;
            int alloc_size = (int)(fSize - offset > FRAGMENT_SIZE ? FRAGMENT_SIZE : fSize - offset);
            ByteBuffer fileBuff = ByteBuffer.wrap(new byte[alloc_size]);
            synchronized(syncObj) {
                inChannel = inChannel.position(offset);

                while (fileBuff.remaining() > 0)
                    inChannel.read(fileBuff);
            }
            ByteBuffer buff = ByteBuffer.allocate(4 + alloc_size);
            buff.putInt(alloc_size);
            buff.put(fileBuff.array());
            buff.flip();
            while (buff.remaining() > 0)
                receiver.write(buff);
            receiver.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

public class Client extends AbstractClient {
    private String clientIp;
    private String serverIp;
    private int clientPort;
    private int serverPort;
    public final int FRAGMENT_SIZE = 4096;
    public static final Object o = new Object();
    public static ExecutorService pool = Executors.newFixedThreadPool(4);
    public static Logger logger = Logger.getLogger(Client.class.getName());
    private int ID;
    public static Map<String, File> mapNameToPath = new HashMap<String, File>();


    public Client(String clientHost, int clientPort, String serverHost, int serverPort, int ID) {
        this.clientIp = clientHost;
        this.clientPort = clientPort;
        this.serverIp = serverHost;
        this.serverPort = serverPort;
        this.ID = ID;
    }

    @Override
    public void publishFile(File file) throws IOException {
        logger.info("Publishing file " + file.getName());
        SocketChannel sockChan = SocketChannel.open();
        sockChan.connect(new InetSocketAddress(serverIp, serverPort));
        sockChan.configureBlocking(false);

        long fileSize = file.length();
        int fragmentSize = (int) (FRAGMENT_SIZE < fileSize ? FRAGMENT_SIZE : fileSize);
        FileDescription fd = new FileDescription(file.getName(), (int)fileSize, fragmentSize);
        mapNameToPath.put(file.getName(), file);

        /*
         * Construct buffer and send a message of the form : p<port><size><serialized_file_description>
         */
        ByteBuffer buffFile = formatBufferFileDescription(fd);
        ByteBuffer buff = ByteBuffer.allocate(5);
        buff.put(new String("p").getBytes());
        buff.putInt(this.clientPort);
        buff.flip();
        while (buff.remaining() > 0) {
            sockChan.write(buff);
        }
        while (buffFile.remaining() > 0) {
            sockChan.write(buffFile);
        }
        sockChan.close();
    }

    @Override
    public File retrieveFile(String filename) throws IOException {
        logger.info("Retrieving file " + filename);
        SocketChannel sockChan = SocketChannel.open();
        sockChan.connect(new InetSocketAddress(serverIp, serverPort));
        sockChan.configureBlocking(false);

        /*
         * Construct buffer and send a message of the form : d<file_name_size><size>
         */
        ByteBuffer buff = ByteBuffer.allocate(5 + filename.length());
        buff.put(new String("d").getBytes());
        buff.putInt(filename.length());
        buff.put(filename.getBytes());
        buff.flip();
        while (buff.remaining() > 0) {
            sockChan.write(buff);
        }

        /**
         * At this step, the client will get A HEADER and two objects:
         *     -> HEADER can be 'y' or 'n', depending on the existence of file
         *     -> A FileDescription for the respective file
         *     -> A Map containing information about what client possesses that fragment of file
         */
        ByteBuffer confirmationFileExists = ByteBuffer.allocate(1);
        while(confirmationFileExists.remaining() > 0)
            sockChan.read(confirmationFileExists);
        if ((char)(confirmationFileExists.get(0)) == 'n') {
            /**
             * File does not exist, so exit function.
             */
            logger.error("File does not exist");
            System.out.println("File does not exist");
            sockChan.close();
            return null;
        }
        FileDescription fd = (FileDescription)readObjectFromSocket(sockChan);
        Map<String, ArrayList<String>> clientsFiles = (Map<String, ArrayList<String>>)readObjectFromSocket(sockChan);
        sockChan.close();

        int num_of_fragments = fd.dimension % fd.firstFragmentSize == 0 ?
                    fd.dimension / fd.firstFragmentSize : fd.dimension/fd.firstFragmentSize + 1;
        ConcurrentHashMap<String, ByteBuffer> fileFragmentsHash = new ConcurrentHashMap<String, ByteBuffer>();
        for (int i = 0; i < num_of_fragments; i++) {
            if (!clientsFiles.containsKey(fd.fileName+i)){
                logger.error("No client has fragment " + i + " of file " + filename);
                return null;
            }
            /**
             * Take randomly the first peer to download the file from
             */
            Random r = new Random();
            String[] peer;
            do {
                 peer = clientsFiles.get(fd.fileName + i).get(r.nextInt(clientsFiles.get(fd.fileName + i).size())).split(":");
            } while (Integer.parseInt(peer[1]) == this.clientPort);
            pool.submit(new DownloadTask(fileFragmentsHash, fd, i, peer[0], Integer.parseInt(peer[1]), clientsFiles.get(fd.fileName + i)));
        }
        logger.info("Started threads for downloading file " + filename);

        synchronized (Client.o) {
            try {
                Client.o.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        String fName = "Client" + ID + "_" + fd.fileName;
        mapNameToPath.put(fd.fileName, new File(fName));
        FileChannel channel = new RandomAccessFile(fName, "rw").getChannel();
        for (int i = 0; i < num_of_fragments; i++) {
            ByteBuffer fragment = fileFragmentsHash.get(fd.fileName + i);
            fragment.flip();
            while (fragment.remaining() > 0) {
                channel.write(fragment);
            }
        }
        logger.info("Wrote file " + filename + " on disk");

        /**
         * Notify the server that this client can server this file from now on.
         * Send a message of the form: u<fname_size><port><fname>
         */
        ByteBuffer updateMessage = ByteBuffer.allocate(9 + fd.fileName.length());
        updateMessage.put((byte)('u'));
        updateMessage.putInt(fd.fileName.length());
        updateMessage.putInt(clientPort);
        updateMessage.put(fd.fileName.getBytes());
        updateMessage.flip();

        sockChan = SocketChannel.open();
        sockChan.connect(new InetSocketAddress(serverIp, serverPort));
        sockChan.configureBlocking(false);
        while (updateMessage.remaining() > 0)
            sockChan.write(updateMessage);

        sockChan.close();
        channel.close();
        return new File(fName);

    }
    public Object readObjectFromSocket(SocketChannel sockChan) {
        ByteBuffer lengthBuffer = ByteBuffer.wrap(new byte[4]);
        while (lengthBuffer.remaining() > 0) {
            try {
                sockChan.read(lengthBuffer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        int numBytes = lengthBuffer.getInt(0);
        ByteBuffer objectBytes = ByteBuffer.wrap(new byte[numBytes]);
        while (objectBytes.remaining() > 0) {
            try {
                sockChan.read(objectBytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(objectBytes.array()));
            try {
                Object o = ois.readObject();
                return o;
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public ByteBuffer formatBufferFileDescription (FileDescription fd) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        for(int i=0;i<4;i++) baos.write(0);
        try {
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(fd);
            oos.close();
            final ByteBuffer wrap = ByteBuffer.wrap(baos.toByteArray());
            wrap.putInt(0, baos.size() - 4);
            return wrap;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
