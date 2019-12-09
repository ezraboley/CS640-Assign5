package edu.wisc.cs.sdn.simpledns;

import edu.wisc.cs.sdn.simpledns.packet.*;

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
        final Map<Integer, Ec2Val> ec2Csv;

        ServerArgs(String rootSvrIp, String ec2Filename) throws IOException {
            this.rootSvrIp = rootSvrIp;
            this.ec2Csv = new HashMap<Integer, Ec2Val>();
            BufferedReader csvReader = new BufferedReader(new FileReader(ec2Filename));
            String row;
            while ((row = csvReader.readLine()) != null) {
                String[] dataComma = row.split(",");
                if (dataComma.length != 2)
                    throw new RuntimeException("Improperly formed csv");
                String[] dataSlash = dataComma[0].split("/");
                this.ec2Csv.put(toIPv4Address(dataSlash[0]),
                        new Ec2Val(Integer.parseInt(dataSlash[1]),dataComma[1]));
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

    static class Ec2Val {
        final int mask;
        final String location;

        Ec2Val(int mask, String location) {
            this.mask = mask;
            this.location = location;
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

    private static DNS recurQueryDNSServer(DNSQuestion originalQuestion, String svrIp, DatagramSocket dnsResolutionSocket, int dnsPort, DNS dns, short q_type, Map<Integer, Ec2Val> ec2Map, ServerArgs serverArgs) throws IOException {
        DNS lookedUpDns = queryDNSServer(originalQuestion, svrIp, dnsResolutionSocket, dnsPort, dns);
        // end when there are no more authorities
        if (lookedUpDns.getAnswers().size() != 0) {
            if (q_type == DNS.TYPE_A || q_type == DNS.TYPE_AAAA) {
                List<DNSResourceRecord> crecords = resolveCNAMEs(originalQuestion, lookedUpDns, svrIp, dnsResolutionSocket, dnsPort, dns, q_type, ec2Map, serverArgs);
                for (DNSResourceRecord rr : crecords) {
                    // FIXME I just drop additional records whenever I resolve a cname. Im not sure the right thing to do
                    // FIXME but im guessing it isn't right
                    lookedUpDns.setAdditional(new ArrayList<DNSResourceRecord>());

                    boolean dup = false;
                    for (DNSResourceRecord ar : lookedUpDns.getAnswers()) {
                        if (rr.getName().equals(ar.getName()))
                            dup = true;
                    }
                    if (!dup)
                        lookedUpDns.getAnswers().add(rr);
                }
            }
            if (q_type == DNS.TYPE_A)
                appendEC2TextRecords(lookedUpDns, ec2Map);
            return lookedUpDns;
        } else {
            for (DNSResourceRecord rr : lookedUpDns.getAuthorities()) {
                try {
                    return recurQueryDNSServer(originalQuestion, rr.getData().toString(), dnsResolutionSocket, dnsPort, dns, q_type, ec2Map, serverArgs);
                } catch (RuntimeException e) {
                    System.out.println(e.getMessage());
                    System.out.println("NS fallback");
                    continue;
                }
            }
            throw new RuntimeException("No answers and no authority, what do I do");
        }
    }

    private static void appendEC2TextRecords(DNS lookedUpDns, Map<Integer, Ec2Val> ec2) {
        final int IP_V4_SIZE = 32;
        List<DNSResourceRecord> oldRRs = new ArrayList<DNSResourceRecord>();
        for (DNSResourceRecord rr : lookedUpDns.getAnswers()) {
            if (rr.getType() != DNS.TYPE_CNAME)
                oldRRs.add(rr);
        }
        for (DNSResourceRecord rr : oldRRs) {
            Integer lookForKey = toIPv4Address(rr.getData().toString());
            iterateThroughMask(lookedUpDns, ec2, IP_V4_SIZE, rr, lookForKey);
        }
    }

    private static void iterateThroughMask(DNS lookedUpDns, Map<Integer, Ec2Val> ec2, int IP_V4_SIZE, DNSResourceRecord rr, Integer lookForKey) {
        for (int i=0; i < IP_V4_SIZE; i++) {
            int mask = (int) (Math.pow(2, i) - 1);
            Integer maskedLookForKey = lookForKey & ~mask;
            if (ec2.containsKey(maskedLookForKey)) {
                lookedUpDns.getAnswers().add(generateRREc2RR(rr.getName(), lookForKey, ec2.get(maskedLookForKey)));
                return;
            }
        }
    }

    private static DNSResourceRecord generateRREc2RR(String name, Integer ip, Ec2Val val) {
        DNSResourceRecord rr = new DNSResourceRecord();
        rr.setName(name);
        rr.setType((short) 16);
        rr.setTtl(0);
        DNSRdata data = new DNSRdataString(val.location + "-" + fromIPv4Address(ip));
        rr.setData(data);
        return rr;
    }

    private static List<DNSResourceRecord> resolveCNAMEs(DNSQuestion originalQuestion, DNS lookedUpDns, String svrIp,
                                                         DatagramSocket dnsResolutionSocket, int dnsPort, DNS dns,
                                                         short q_type, Map<Integer, Ec2Val> ec2File, ServerArgs serverArgs) throws IOException {
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
        return rrResults;
    }

    private static DNS queryDNSServer(final DNSQuestion originalQuestion, final String rootSvrIp, DatagramSocket socket,
                                      int dnsPort, DNS originalDns) throws IOException {
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
        dns.setRecursionDesired(true);
        dns.setRecursionAvailable(false);
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

    /**
     * Accepts an IPv4 address of the form xxx.xxx.xxx.xxx, ie 192.168.0.1 and
     * returns the corresponding 32 bit integer.
     *
     * This is not my code this is from the project 3 code
     *
     * @param ipAddress
     * @return
     */
    public static int toIPv4Address(String ipAddress) {
        if (ipAddress == null)
            throw new IllegalArgumentException("Specified IPv4 address must" +
                    "contain 4 sets of numerical digits separated by periods");
        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4)
            throw new IllegalArgumentException("Specified IPv4 address must" +
                    "contain 4 sets of numerical digits separated by periods");

        int result = 0;
        for (int i = 0; i < 4; ++i) {
            result |= Integer.valueOf(octets[i]) << ((3 - i) * 8);
        }
        return result;
    }

    /**
     * Accepts an IPv4 address and returns of string of the form xxx.xxx.xxx.xxx
     * ie 192.168.0.1
     *
     * This is not my code this is from the project 3 code
     *
     * @param ipAddress
     * @return
     */
    public static String fromIPv4Address(int ipAddress) {
        StringBuffer sb = new StringBuffer();
        int result = 0;
        for (int i = 0; i < 4; ++i) {
            result = (ipAddress >> ((3 - i) * 8)) & 0xff;
            sb.append(Integer.valueOf(result).toString());
            if (i != 3)
                sb.append(".");
        }
        return sb.toString();
    }
}