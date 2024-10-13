import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.io.IOException;
import java.nio.Buffer;
import java.util.LinkedList;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/*
 * ReceiverThread receives incoming packets from remote hosts and put them in the receiverList.
 */
public class ReceiverThread extends Thread {
    private final DatagramSocket sock;
    private final LinkedList<GUDPEndPoint> receiverList;
    private final SenderThread s;
    private final LinkedList<GUDPEndPoint> senderList;
    private boolean runFlag = true;
    private boolean debug = true;
    private GUDPSocket.drop senderDrop;
    private GUDPSocket.drop receiverDrop;

    public ReceiverThread(DatagramSocket sock, LinkedList<GUDPEndPoint> receiverList, SenderThread s, LinkedList<GUDPEndPoint> senderList, GUDPSocket.drop senderDrop, GUDPSocket.drop receiverDrop) {
        this.sock = sock;
        this.receiverList = receiverList;
        this.s = s;
        this.senderList = senderList;
        this.senderDrop = senderDrop;
        this.receiverDrop = receiverDrop;
    }

    public void stopReceiverThread() {
        this.runFlag = false;
    }

    public boolean getRunFlag() {
        return runFlag;
    }

    private GUDPPacket ackPacket(InetSocketAddress sockAddr, int seq) {
        ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        GUDPPacket gudpACKPacket = new GUDPPacket(buffer);
        gudpACKPacket.setType(GUDPPacket.TYPE_ACK);
        gudpACKPacket.setVersion(GUDPPacket.GUDP_VERSION);
        gudpACKPacket.setSocketAddress(sockAddr);
        gudpACKPacket.setPayloadLength(0);
        gudpACKPacket.setSeqno(seq);
        return gudpACKPacket;
    }

    private void setEndPointParameters(GUDPEndPoint endPoint, int seq) {
        //endPoint.setBase(seq);
        //endPoint.setNextseqnum(seq);
        //endPoint.setLast(seq);
        endPoint.setExpectedseqnum(seq + 1);  //下一个期望到来的序列号，是bsn+1
    }

    @Override
    public void run() {
        /*
         * This is a loop that continously runs until the ReceiveThread is terminated.
         * Receive incoming packets and process each packet according to the type of packets
         * ACK: Receive ACK as a part of GBN sender logic. Remove all ACKed packets from senderList.
         *      Progress to RCV and call FSMSender. Also notify senderList
         * BSN: Create a new remoteEndPoint if it does not exist. Then, add BSN to its receive queue and send ACK.
         *      If existing remoteEndPoint was already finished. Reset remoteEndPoint and add the BSN and send ACK.
         *      Otherwise, just send ACK with the expected sequence number.
         * DATA: If DATA packet with expected sequence number, add it to receive queue and send ACK.
         *       Otherwise, just send ACK with the expected sequence number.
         *       (If DATA packet for non-existing remoteEndPoint arrives, do nothing)
         * FIN: Same as DATA. But also set "finished" to true to indicate file reception is completed.
         *
         * You need to synchronize receiverList in all cases except ACK where you need to synchornize senderList.
         *
         * IMPORTANT:
         * If a BSN of a new tranmission is lost, DATA may arrive before you reset the remoteEndPoint.
         * Thus, you should check that the DATA seq is within the expected range based on the Window size.
         * Otherwise, you can silently ignore the DATA packet without sending an ACK.
         */
        while (runFlag) {
            try {
                byte[] buffer = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                sock.receive(packet);

                InetSocketAddress socketAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                GUDPPacket gudpPacket = GUDPPacket.unpack(packet);
                switch (gudpPacket.getType()) {
                    case GUDPPacket.TYPE_ACK:
                        synchronized (senderList) {
                            GUDPEndPoint endPoint = null;
                            for (GUDPEndPoint ep : senderList) {
                                if (ep.getRemoteEndPoint().equals(socketAddress)) {
                                    endPoint = ep;
                                    break;
                                }
                            }
                            endPoint.removeAllACK(gudpPacket.getSeqno() - 1);
                            endPoint.setBase(gudpPacket.getSeqno());
                            endPoint.setState(GUDPEndPoint.endPointState.RCV);
                            s.FSMSender(endPoint);
                            senderList.notifyAll();
                        }
                        break;

                    case GUDPPacket.TYPE_BSN:
                        synchronized (receiverList) {
                            GUDPEndPoint endPoint = null;
                            for (GUDPEndPoint ep : receiverList) {
                                if (ep.getRemoteEndPoint().equals(socketAddress)) {
                                    endPoint = ep;
                                    break;
                                }
                            }
                            if (endPoint == null) { //如果没在接收队列中找到该端点，就新创建一个
                                endPoint = new GUDPEndPoint(packet.getAddress(), packet.getPort());
                                //初始化该端点变量
                                setEndPointParameters(endPoint, gudpPacket.getSeqno());
                                //把BSN包加入到该端点的接收队列中（接收列表内的端点的队列都是接收队列，区别于发送队列所维护的参数）
                                endPoint.add(gudpPacket);
                                //把该端点放入接收队列
                                receiverList.add(endPoint);
                                //发送针对该BSN的ACK回复
                                GUDPPacket gudpACKpacket = ackPacket(socketAddress, endPoint.getExpectedseqnum()); //ACK包的序列号就是下一个期望数据的序列号
                                DatagramPacket ACKpacket = gudpACKpacket.pack();
                                sock.send(ACKpacket);
                            } else if (endPoint.getFinished()) {   //这里的finished是接受端设置的：receiving process would set the finished flag when receiving is finished.
                                endPoint.setFinished(false);
                                setEndPointParameters(endPoint, gudpPacket.getSeqno());  //reset端点参数
                                endPoint.add(gudpPacket); //把该BSN放到重启后的队列
                                //回复ACK包
                                GUDPPacket gudpACKpacket = ackPacket(socketAddress, endPoint.getExpectedseqnum());
                                DatagramPacket ACKpacket = gudpACKpacket.pack();
                                sock.send(ACKpacket);
                            } else {
                                //endPoint.add(gudpPacket); 那这里到底放不放这个BSN？按理说如果端点已经存在，而且没有finish它应该已经有BSN在queue里了
                                GUDPPacket gudpACKpacket = ackPacket(socketAddress, endPoint.getExpectedseqnum());
                                DatagramPacket ACKpacket = gudpACKpacket.pack();
                                sock.send(ACKpacket);
                            }
                        }
                        break;

                    case GUDPPacket.TYPE_DATA:
                        synchronized (receiverList) {
                            GUDPEndPoint endPoint = null;
                            for (GUDPEndPoint ep : receiverList) {
                                if (ep.getRemoteEndPoint().equals(socketAddress)) {
                                    endPoint = ep;
                                    break;
                                }
                            }
                            if (endPoint == null) {
                                break; //If DATA packet for non-existing remoteEndPoint arrives, do nothing
                            }

                            // 判断是否是下一个期望的数据包或在接收窗口范围内
                            if (gudpPacket.getSeqno() >= endPoint.getExpectedseqnum() &&
                                    gudpPacket.getSeqno() < endPoint.getExpectedseqnum() + endPoint.getWindowSize()) {

                                // 加入处理队列并更新下一个期望的序列号
                                endPoint.add(gudpPacket);
                                endPoint.setExpectedseqnum(endPoint.getExpectedseqnum() + 1);

                                // 发送 ACK，确认收到该数据包
                                GUDPPacket gudpACKpacket = ackPacket(socketAddress, endPoint.getExpectedseqnum());
                                DatagramPacket ACKpacket = gudpACKpacket.pack();
                                sock.send(ACKpacket);
                            } else {
                                //如果不满足期望序列号的条件，不处理数据，单纯发ACK
                                GUDPPacket gudpACKpacket = ackPacket(socketAddress, endPoint.getExpectedseqnum()); //这里的期望数据序列没有动，还是之前的
                                DatagramPacket ACKpacket = gudpACKpacket.pack();
                                sock.send(ACKpacket);
                            }
                        }
                        break;

                    case GUDPPacket.TYPE_FIN:
                        synchronized (receiverList) {
                            GUDPEndPoint endPoint = null;
                            for (GUDPEndPoint ep : receiverList) {
                                if (ep.getRemoteEndPoint().equals(socketAddress)) {
                                    endPoint = ep;
                                    break;
                                }
                            }
                            if (endPoint == null) {
                                break; //If DATA packet for non-existing remoteEndPoint arrives, do nothing
                            }

                            // 判断是否是下一个期望的数据包或在接收窗口范围内
                            if (gudpPacket.getSeqno() >= endPoint.getExpectedseqnum() &&
                                    gudpPacket.getSeqno() < endPoint.getExpectedseqnum() + endPoint.getWindowSize()) {

                                // 加入处理队列并更新下一个期望的序列号
                                endPoint.add(gudpPacket);
                                endPoint.setExpectedseqnum(endPoint.getExpectedseqnum() + 1);
                                endPoint.setFinished(true); //set "finished" to true to indicate file reception is completed

                                // 发送 ACK，确认收到该数据包
                                GUDPPacket gudpACKpacket = ackPacket(socketAddress, endPoint.getExpectedseqnum());
                                DatagramPacket ACKpacket = gudpACKpacket.pack();
                                sock.send(ACKpacket);
                            } else {
                                //如果不满足期望序列号的条件，不处理数据，单纯发ACK
                                GUDPPacket gudpACKpacket = ackPacket(socketAddress, endPoint.getExpectedseqnum()); //这里的期望数据序列没有动，还是之前的
                                DatagramPacket ACKpacket = gudpACKpacket.pack();
                                sock.send(ACKpacket);
                            }
                        }
                        break;

                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    } /* public void run() */
} /* public class ReceiverThread */

