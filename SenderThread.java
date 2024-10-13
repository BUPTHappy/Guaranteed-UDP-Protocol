import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;

/*
 * SenderThread monitors send queues and sends packets whenever there are packets in the queues
 */

public class SenderThread extends Thread {
    private final DatagramSocket sock;
    private final LinkedList<GUDPEndPoint> senderList;
    private boolean runFlag = true;
    private boolean debug = true;
    private GUDPSocket.drop senderDrop; //这个需要用到

    public SenderThread(DatagramSocket sock, LinkedList<GUDPEndPoint> senderList, GUDPSocket.drop senderDrop) {
        this.sock = sock;
        this.senderList = senderList;
        this.senderDrop = senderDrop;
    }

    public void stopSenderThread() {
        this.runFlag = false;
    }
    //丢包仿真 check if there is simulation of dropping packets
    private boolean packetDropSimu(){
        if(senderDrop == GUDPSocket.drop.RANDOM){
            Random rand = new Random();
            boolean dropped = rand.nextDouble()<0.2;
            return dropped;
        }
        return false;
    }
    @Override
    public void run() {

        try {
            synchronized (senderList) {
                while (runFlag && senderList.isEmpty()) {   //如果发送队列是空的，但是线程还在运行中
                    try {
                        senderList.wait();
                        System.out.println("senderlist is empty,wait");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            while (runFlag) {
                synchronized (senderList) {
                    //除此之外，如果队列有数据：
                    if (!senderList.isEmpty()) {
                        for (GUDPEndPoint endPoint : senderList) {
                            System.out.println("get endpoint " + endPoint.getRemoteEndPoint() + "to FMS");
                            FSMSender(endPoint);  //运行状态机，处理该端点
                            if (endPoint.getFinished()) { //如果发送检测到finished标志（这里还不代表发送完成）
                                if (endPoint.isEmptyQueue()) {//还要检查发送队列是否为空
                                    senderList.remove(endPoint);
                                    //return
                                }
                            }
                        }
                    }
                    if (senderList.isEmpty()) {
                        senderList.notifyAll();
                    } else {
                        boolean allQueuesEmpty = true;
                        for (GUDPEndPoint endPoint : senderList) {
                            if (!endPoint.isEmptyQueue()) {
                                allQueuesEmpty = false;
                                break;  //有队列中的端点还没完成，不找了，等50ms之后继续下一个发送循环
                            }
                        }
                        if (allQueuesEmpty) {
                            try {
                                senderList.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }

                    if (!runFlag) {
                        senderList.notifyAll();
                        break;
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }finally {
            if (sock != null && !sock.isClosed()) {
                sock.close();
            }
        }
    }
    /* public void run() */

    public void FSMSender(GUDPEndPoint endPoint) {
        switch (endPoint.getState()) {
            case INIT:
                endPoint.setState(GUDPEndPoint.endPointState.WAIT);
                break;

            case WAIT:
                if (!endPoint.isEmptyQueue()) {
                    endPoint.setState(GUDPEndPoint.endPointState.SEND);
                } else {
                    senderList.notifyAll();
                }
                break;

            case SEND:
                try{
                    while(endPoint.getNextseqnum()<endPoint.getBase()+endPoint.getWindowSize() && endPoint.getNextseqnum()<=endPoint.getLast()){

                        GUDPPacket gudpPacket =endPoint.getPacket(endPoint.getNextseqnum());
                        if(gudpPacket!=null){
                            DatagramPacket udppacket=gudpPacket.pack();
                            if(!packetDropSimu()){
                                sock.send(udppacket);
                            }
                        }

                        if(endPoint.getBase() == endPoint.getNextseqnum()){
                            endPoint.startTimer();
                        }
                        endPoint.setNextseqnum(endPoint.getNextseqnum()+1);
                    }
                }catch (IOException e) {
                    throw new RuntimeException(e);
                }
                endPoint.setState(GUDPEndPoint.endPointState.WAIT);
                break;

            case RCV:
                if(endPoint.getBase() == endPoint.getNextseqnum()){
                    endPoint.stopTimer();
                    endPoint.setRetry(0);
                }else{
                    endPoint.stopTimer();
                    endPoint.startTimer();
                }
                endPoint.setState(GUDPEndPoint.endPointState.SEND);
                break;

            case TIMEOUT:
                if(endPoint.getRetry()>=endPoint.getMaxRetry()){
                    //Resend packets and restart the timer.
                    //If maximum retransmission, terminate the SendThread, which should
                    //trigger the program to terminate (assuming you monitor it in send and finish methods).
                    //endPoint.setFinished(true);
                    stopSenderThread();
                    break;
                }else{

                    for(int i = endPoint.getBase();i<endPoint.getNextseqnum();i++){
                        //do some actually resend
                        try {
                            GUDPPacket packet = endPoint.getPacket(i);
                            if (packet != null) {
                                if (!packetDropSimu()) {
                                    sock.send(packet.pack());
                                }
                            }
                        }catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    endPoint.startTimer();
                    endPoint.setState(GUDPEndPoint.endPointState.SEND);
                    endPoint.setRetry(endPoint.getRetry()+1);
                }
                break;

            case DEFAULT:
                endPoint.setState(GUDPEndPoint.endPointState.INIT);
                break;

        }
    }
}