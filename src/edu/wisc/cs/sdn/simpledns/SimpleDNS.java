package edu.wisc.cs.sdn.simpledns;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;

public class SimpleDNS {
    private static final int initPort = 8053;

    public static void main(String[] args) {
        final ServerArgs serverArgs = parseArgs(args);
        try {
            DatagramSocket socket = new DatagramSocket(initPort);
            while (true) {
                IncomingPacketInfo incomingInfo = receiveInitPacket(socket, serverArgs);
                DNS resultingDns = handleQuestions(incomingInfo.dnsInfo, serverArgs);
                replyToClient(resultingDns, incomingInfo.srcIp, incomingInfo.srcPort, socket);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private static void replyToClient(DNS dns, String srcIp, int srcPort, DatagramSocket socket) throws IOException {
        sendDNSRequest(dns, srcIp, srcPort, socket);
    }

    /**
     * Just parsing the arguments into a neat data structure
     *
     * @param args
     * @return the wrapped parsed args
     */
    private static ServerArgs parseArgs(String[] args) {
        if ((args.length == 4) && (args[0].equals("-r")) && (args[2].equals("-e"))) {
            return new ServerArgs(args[1], args[3]);
        } else {
            throw new IllegalArgumentException("Required format: java edu.wisc.cs.sdn.simpledns.SimpleDNS " +
                    "-r <root server ip> -e <ec2 csv>");
        }
    }

    /**
     * This just wraps the args
     */
    private static class ServerArgs {
        final String rootSvrIp;
        final String ec2Csv;

        ServerArgs(String rootSvrIp, String ec2Csv) {
            this.rootSvrIp = rootSvrIp;
            this.ec2Csv = ec2Csv;
        }
    }

    /**
     * Waits for an incoming packet and handles it
     *
     * @throws IOException
     */
    private static IncomingPacketInfo receiveInitPacket(DatagramSocket socket, ServerArgs serverArgs) throws IOException {
        byte[] buff = new byte[2048]; // what size should this be?
        DatagramPacket pk = new DatagramPacket(buff, buff.length);
        System.out.println("Waiting for packet!");
        socket.receive(pk);
        System.out.println("Packet Received! + " + Arrays.toString(pk.getData()));
        DNS dns = DNS.deserialize(pk.getData(), pk.getLength());
        System.out.println("DNS info: " + dns);
        if (dns.getOpcode() == DNS.OPCODE_STANDARD_QUERY) {
            System.out.println("Opcode is 0");
            return new IncomingPacketInfo(pk.getAddress().getHostAddress(), pk.getPort(), dns); // is this the correct stuff
        } else {
            throw new RuntimeException("incorrect opcode");
        }
    }

    static class IncomingPacketInfo {
        final String srcIp;
        final int srcPort;
        final DNS dnsInfo;

        IncomingPacketInfo(String srcIp, int srcPort, DNS dnsInfo) {
            this.srcIp = srcIp;
            this.srcPort = srcPort;
            this.dnsInfo = dnsInfo;
        }
    }

    /**
     * Handles all questions within a DNS packet
     *
     * @param dns
     */
    private static DNS handleQuestions(DNS dns, ServerArgs serverArgs) throws SocketException {
        final int dnsPort = 53;
        DatagramSocket dnsResolutionSocket = new DatagramSocket(dnsPort);
        for (DNSQuestion q : dns.getQuestions()) {
            switch (q.getType()) {
                case DNS.TYPE_A:
                    System.out.println("Received question with type A");
                    break;
                case DNS.TYPE_NS:
                    System.out.println("Received question with type NS");
                    break;
                case DNS.TYPE_CNAME:
                    System.out.println("Received question with type CNAME");
                    break;
                case DNS.TYPE_AAAA:
                    System.out.println("Received question with type AAAA");
                    break;
                default:
                    throw new RuntimeException("Received question invalid type");
            }
            // goals:
            // able to correctly query one database norecursion
//            DatagramPacket res = dns.isRecursionDesired() ?  recQueryDNSServer() : queryDNSServer();
//            how are you issuing dns requests? are you using sockets or tcp connections or dig commands?
            // at this
//            q.
//            DNS retDns = new DNS();
//            retDns.
            try {
                return queryDNSServer(q.getName(), serverArgs.rootSvrIp, dnsResolutionSocket, dnsPort);
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
        throw new RuntimeException("No questions, this shouldn't happen");
    }

    private static DNS queryDNSServer(final String name, final String rootSvrIp, DatagramSocket socket, int dnsPort) throws IOException {
//            how are you issuing dns requests? are you using sockets or tcp connections or dig commands?
        byte[] buff = new byte[2048]; // what size should this be?
        DatagramPacket pk = new DatagramPacket(buff, buff.length);
        sendDNSRequestWithTimeout(name, rootSvrIp, socket, dnsPort);
        System.out.println("Waiting for packet!");
        socket.receive(pk);
        System.out.println("Packet Received! + " + Arrays.toString(pk.getData()));
        DNS dns = DNS.deserialize(pk.getData(), pk.getLength());
        System.out.println("DNS info: " + dns);
        return dns;
    }

    private static void sendDNSRequestWithTimeout(final String name, final String svrIp, final DatagramSocket socket, final int dnsPort) throws IOException {
        Thread requestThread = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(1000);
                    System.out.println("Timeout complete");
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                }
                try {
                    sendDNSRequest(generateDNSForRequest(name), svrIp, dnsPort, socket);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        requestThread.start();
        System.out.println("Thread started");
    }

    private static DNS generateDNSForRequest(String name) {
        DNS dns = new DNS();
        dns.setId((short) -1);
        dns.setQuery(true);
        dns.setAuthenicated(true);
        dns.setQuestions(new ArrayList<DNSQuestion>(Arrays.asList(new DNSQuestion(name, DNS.TYPE_A)))); // maybe change type
        return dns;
    }

    private static void sendDNSRequest(DNS dns, String svrIp, int dnsPort, DatagramSocket socket) throws IOException {
        byte[] buffer = dns.serialize();
        InetAddress receiverAddress = InetAddress.getByName(svrIp);
        DatagramPacket packet = new DatagramPacket(
                buffer, buffer.length, receiverAddress, dnsPort); // what port?
        System.out.println("Sent DNS request packet");
            socket.send(packet);
    }

//    private static DatagramPacket recQueryDNSServer() {
//        // while
//    }
}


//        final int queryPort = 5010;
//        final DatagramSocket socket = new DatagramSocket(queryPort);
//        final DatagramSocket socket socket= new DatagramSocket();
// send request to DNS server after a timeout just for test
//        Thread sendDNSReqAfterSleep = new Thread(new Runnable() {
////            public void run() {
////                    try {
////                        Thread.sleep(1000);
////                        sendDNSRequest();
////                    }
////                    catch(Exception e) {
////                        System.err.println(e.getMessage());
////                    }
////            }

//        });
//        sendDNSReqAfterSleep.start();
// listen for a response from the server. What port?
//        DatagramSocket datagramSocket = new DatagramSocket(queryPort);
// print results s

//        final int port = 5010;
//        DatagramSocket datagramSocket = new DatagramSocket();
//        byte[] buffer = DNS.serializeName(name);
//        InetAddress receiverAddress = InetAddress.getLocalHost();