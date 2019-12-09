package edu.wisc.cs.sdn.simpledns;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.*;
import java.util.*;

public class SimpleDNS {
    private static final int initPort = 8053;

    public static void main(String[] args) {
        final ServerArgs serverArgs;
        try {
            serverArgs = parseArgs(args);
            DatagramSocket socket = new DatagramSocket(initPort);
            while (true) {
                IncomingPacketInfo incomingInfo = receiveInitPacket(socket, serverArgs);
                DNS resultingDns = handleQuestions(incomingInfo.dnsInfo, serverArgs);
                resultingDns.setId(incomingInfo.dnsInfo.getId());
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
    private static ServerArgs parseArgs(String[] args) throws IOException {
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
        final Map<String, String> ec2Csv;

        ServerArgs(String rootSvrIp, String ec2Filename) throws IOException {
            this.rootSvrIp = rootSvrIp;
            this.ec2Csv = new HashMap<String, String>();
            BufferedReader csvReader = new BufferedReader(new FileReader(ec2Filename));
            String row;
            while ((row = csvReader.readLine()) != null) {
                String[] data = row.split(",");
                if (data.length != 2)
                    throw new RuntimeException("Improperly formed csv");
                this.ec2Csv.put(data[0],data[1]);
            }
            csvReader.close();
        }
    }

    /**
     * Waits for an incoming packet and handles it
     *
     * @throws IOException
     */
    private static IncomingPacketInfo receiveInitPacket(DatagramSocket socket, ServerArgs serverArgs) throws IOException {
        byte[] buff = new byte[1518]; // what size should this be?
        DatagramPacket pk = new DatagramPacket(buff, buff.length);
        System.out.println("Waiting for packet!");
        socket.receive(pk);
//        System.out.println("Packet Received! + " + Arrays.toString(pk.getData()));
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
            short q_type;
            switch (q.getType()) {
                case DNS.TYPE_A:
                    System.out.println("Received question with type A");
                    q_type = DNS.TYPE_A;
                    break;
                case DNS.TYPE_NS:
                    System.out.println("Received question with type NS");
                    q_type = DNS.TYPE_NS;
                    break;
                case DNS.TYPE_CNAME:
                    System.out.println("Received question with type CNAME");
                    q_type = DNS.TYPE_CNAME;
                    break;
                case DNS.TYPE_AAAA:
                    System.out.println("Received question with type AAAA");
                    q_type = DNS.TYPE_AAAA;
                    break;
                default:
                    throw new RuntimeException("Received question invalid type");
            }
            try {
                DNS retDns;
                if (dns.isRecursionDesired()) {
                    System.out.println("Recursive search");
                    retDns = recurQueryDNSServer(q, serverArgs.rootSvrIp, dnsResolutionSocket, dnsPort, dns, q_type, serverArgs.ec2Csv, serverArgs);
                } else {
                    System.out.println("non recursive search");
                    retDns = queryDNSServer(q, serverArgs.rootSvrIp, dnsResolutionSocket, dnsPort, dns);
                }
                dnsResolutionSocket.close();
                return retDns;
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
        throw new RuntimeException("No questions, this shouldn't happen");
    }

    private static DNS recurQueryDNSServer(DNSQuestion originalQuestion, String svrIp, DatagramSocket dnsResolutionSocket, int dnsPort, DNS dns, short q_type, Map<String, String> ec2Map, ServerArgs serverArgs) throws IOException {
        DNS lookedUpDns = queryDNSServer(originalQuestion, svrIp, dnsResolutionSocket, dnsPort, dns);
        // end when there are no more authorities
        if (lookedUpDns.getAnswers().size() != 0) {
            if (q_type == DNS.TYPE_A || q_type == DNS.TYPE_AAAA)
                resolveCNAMEs(originalQuestion, lookedUpDns, svrIp, dnsResolutionSocket, dnsPort, dns, q_type, ec2Map, serverArgs);
            if (q_type == DNS.TYPE_A)
                appendEC2TextRecords(lookedUpDns, ec2Map);
            return lookedUpDns;
        } else {
            for (DNSResourceRecord rr : lookedUpDns.getAuthorities()) {
                return recurQueryDNSServer(originalQuestion, rr.getData().toString(), dnsResolutionSocket, dnsPort, dns, q_type, ec2Map, serverArgs);
            }
            // FIXME what to do if there is no authorities and no
            throw new RuntimeException("No answers and no authority, what do I do");
//            return new DNS();
        }
    }

    private static void appendEC2TextRecords(DNS lookedUpDns, Map<String, String> ec2) {
        for (DNSResourceRecord rr : lookedUpDns.getAnswers()) {
            String key = rr.getData().toString();
            if (ec2.containsKey(key)) {
                lookedUpDns.getAnswers().add(generateTextEc2RR(key, ec2.get(key)));
            }
        }
    }

    private static DNSResourceRecord generateTextEc2RR(String ip, String location) {
//        DNSResourceRecord retRecord = new DNSResourceRecord();
//        retRecord.setName();
        return null;
    }

    private static void resolveCNAMEs(DNSQuestion originalQuestion, DNS lookedUpDns, String svrIp, DatagramSocket dnsResolutionSocket, int dnsPort, DNS dns, short q_type, Map<String, String> ec2File, ServerArgs serverArgs) throws IOException {
//        then you should recursively resolve the CNAME to obtain an A or AAAA record for the CNAME
        List<DNSResourceRecord> rrResults = new ArrayList<DNSResourceRecord>();
        for (DNSResourceRecord rr : lookedUpDns.getAnswers()) {
            if (rr.getType() == DNS.TYPE_CNAME) {
                DNSQuestion cnameQuestion = new DNSQuestion();
                cnameQuestion.setName(rr.getData().toString());
                cnameQuestion.setClass(DNS.CLASS_IN);
                cnameQuestion.setType(DNS.TYPE_A);
                DNS result = recurQueryDNSServer(cnameQuestion, serverArgs.rootSvrIp, dnsResolutionSocket, dnsPort, dns, q_type, ec2File, serverArgs);
                for (DNSResourceRecord ans : result.getAnswers()) {
                    rrResults.add(ans);
                }
            }
        }
        for (DNSResourceRecord ans : rrResults) {
            lookedUpDns.getAnswers().add(ans);
        }
    }

    private static DNS queryDNSServer(final DNSQuestion originalQuestion, final String rootSvrIp, DatagramSocket socket, int dnsPort, DNS originalDns) throws IOException {
//            how are you issuing dns requests? are you using sockets or tcp connections or dig commands?
        byte[] buff = new byte[1518]; // what size should this be?
        DatagramPacket pk = new DatagramPacket(buff, buff.length);
        sendDNSRequest(generateDNSForRequest(originalQuestion, originalDns), rootSvrIp, dnsPort, socket);
        System.out.println("Waiting for packet!");
        socket.receive(pk);
        DNS dns = DNS.deserialize(pk.getData(), pk.getLength());
        System.out.println("DNS info: " + dns);
        return dns;
    }

    private static DNS generateDNSForRequest(DNSQuestion originalQuestion, DNS originalDns) {
        DNS dns = new DNS();
        dns.setId(originalDns.getId());
        dns.setQuery(true);
        dns.setAuthenicated(true);
        dns.setQuestions(new ArrayList<DNSQuestion>(Arrays.asList(originalQuestion))); // maybe change type
        dns.setAdditional(new ArrayList<DNSResourceRecord>(originalDns.getAdditional()));
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
}