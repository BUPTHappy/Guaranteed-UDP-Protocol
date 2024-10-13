//EDIT 10.1 8:28
import java.net.*;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.LinkedList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class GUDPSocket implements GUDPSocketAPI {
    DatagramSocket datagramSocket;  // a socket for sending and receiving datagram packets
    LinkedList<GUDPEndPoint> senderList;  // list of the send queues, one element per destination (remoteEndPoint)
    LinkedList<GUDPEndPoint> receiverList;  // list of the receive queues, one element per destination (remoteEndPoint)
    SenderThread s; // Thread sending packets from send queues to destinations
    ReceiverThread r;  // Thread receiving packets and putting them into corresponding receive queues
    /*
     * Variables below are for testing. You don't need to use them.
     */
    private boolean debug = true;
    public enum drop {
        NOTHING,
        FIRST_BSN,
        FIRST_DATA,
        FIRST_ACK,
        FIRST_FIN,
        RANDOM,
        ALL,
    }
    private drop senderDrop = drop.NOTHING;
    private drop receiverDrop = drop.NOTHING;

    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
        /*
         * - initialize senderList and receiverList
         * - initialize s and r
         * - start s and r threads
         */
        this.senderList = new LinkedList<GUDPEndPoint>();
        this.receiverList = new LinkedList<GUDPEndPoint>();
        this.s = new SenderThread(socket, senderList, senderDrop);
        this.s.setName("SenderThread");
        this.r = new ReceiverThread(socket, receiverList, s, senderList, senderDrop, receiverDrop);
        this.r.setName("ReceiverThread");
        this.s.start();
        System.out.println("SenderThread started");
        this.r.start();
        System.out.println("ReceiverThread started");
    }

    //创建bsn数据包
    private int  bsnPacket(GUDPEndPoint endPoint) throws IOException {
        synchronized (this.senderList) {
            InetSocketAddress address = endPoint.getRemoteEndPoint();
            //生成随机数
            Random random = new Random();
            int randomNUM = random.nextInt();
            endPoint.setBase(randomNUM); //重新初始化端点参数
            endPoint.setNextseqnum(randomNUM);

            byte[] buffer = new byte[0];
            DatagramPacket BSN = new DatagramPacket(buffer, buffer.length, address);
            GUDPPacket bsnPacket = GUDPPacket.encapsulate(BSN);
            bsnPacket.setType(GUDPPacket.TYPE_BSN);
            bsnPacket.setSeqno(randomNUM);
            endPoint.add(bsnPacket);
            endPoint.setLast(randomNUM);
            this.senderList.add(endPoint);
            return this.senderList.indexOf(endPoint);  //返回在列表中的位置索引
        }
    }
    //检查缓冲队列
    private boolean hasPacket(LinkedList<GUDPEndPoint> sendqueue){
        for (GUDPEndPoint ep : sendqueue){
            if (!ep.isEmptyQueue()){}
            return true;
        }
        return false;
    }

    public void send(DatagramPacket packet) throws IOException {
        if (!this.s.isAlive()){
            s.start();
        }
        if (!this.r.isAlive()){
            r.start();
        }

        GUDPEndPoint endPoint = null;
        GUDPEndPoint realPoint = new GUDPEndPoint(packet.getAddress(),packet.getPort());
        GUDPPacket gudpPacket = GUDPPacket.encapsulate(packet);


        synchronized (this.senderList) {
            for (GUDPEndPoint ep : this.senderList) {
                if (realPoint.getRemoteEndPoint().getAddress().equals(ep.getRemoteEndPoint().getAddress())
                        && realPoint.getRemoteEndPoint().getPort() == ep.getRemoteEndPoint().getPort()) {
                    endPoint = ep;
                    int lastSeq = endPoint.getLast();
                    gudpPacket.setSeqno(lastSeq+1);
                    ep.setLast(lastSeq+1);
                    ep.add(gudpPacket);
                    break;
                }
            }
            if (endPoint == null) {
                int index= bsnPacket(realPoint);
                endPoint = this.senderList.get(index);
                int last = endPoint.getLast();
                gudpPacket.setSeqno(last+1);
                endPoint.setLast(last+1);
                this.senderList.get(index).add(gudpPacket);
            }
            this.senderList.notifyAll(); //通知全部线程
        }
    }

    public void receive(DatagramPacket packet) throws IOException {
        if(!r.isAlive()){
            r.start();
            System.out.println("ReceiverThread started");
        }

        InetAddress address = packet.getAddress();
        int port = packet.getPort();

        synchronized (this.receiverList) {
            while (this.receiverList.isEmpty() || !hasPacket(this.receiverList)) {
                try {
                    this.receiverList.wait();
                } catch (InterruptedException e) {
                    throw new RemoteException();
                }
            }
            if (address == null) {
                for (GUDPEndPoint ep : this.receiverList) {
                    if (ep.isEmptyQueue())
                        continue;
                    ep.remove().decapsulate(packet);
                    return;
                }
            }
            for (GUDPEndPoint ep : this.receiverList) {
                if (ep.isEmptyQueue())
                    continue;
                if (!(address.equals(ep.getRemoteEndPoint().getAddress())
                        && port == ep.getRemoteEndPoint().getPort()))
                    continue;
                GUDPPacket remove = ep.remove();
                remove.decapsulate(packet);
                return;
            }
        }
    }

    public void finish() throws IOException {

        for (GUDPEndPoint ep : this.senderList){
            int lastnum = ep.getLast();

            byte[] buffer = new byte[0];
            DatagramPacket FINPacket = new DatagramPacket(buffer, buffer.length,ep.getRemoteEndPoint());
            GUDPPacket packet = GUDPPacket.encapsulate(FINPacket);
            packet.setType(GUDPPacket.TYPE_FIN);

            packet.setSeqno(lastnum+1);
            ep.setLast(lastnum+1);

            ep.add(packet);
            ep.setFinished(true);  //给每个包在创建FIN时都设置finished标志

            synchronized (senderList){
                senderList.notifyAll();
            }
        }

        while (true) {
            boolean allFinished = true;
            for (GUDPEndPoint ep : this.senderList) {
                if ( !ep.getFinished() || !ep.isEmptyQueue()) {
                    allFinished = false;
                    break;
                }
            }
            if (allFinished) {
                break;
            }

            if (!s.isAlive()) {
                s.start();
                System.out.println("SenderThread started");
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void close() throws IOException {
        s.stopSenderThread();
        r.stopReceiverThread();
        datagramSocket.close();
    }
}
