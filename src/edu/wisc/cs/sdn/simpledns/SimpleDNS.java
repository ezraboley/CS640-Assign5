package edu.wisc.cs.sdn.simpledns;
import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;

public class SimpleDNS 
{
	private static final int port = 8053;
	public static void main(String[] args)
	{
		try {
			DatagramSocket socket = new DatagramSocket(port);
			while (true) {
				handlePacket(socket);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/**
	 * Waits for an incoming packet and handles it
	 * @param socket
	 * @throws IOException
	 */
	private static void handlePacket(DatagramSocket socket) throws IOException {
		byte[] buff = new byte[1024]; // what size should this be?
		DatagramPacket pk = new DatagramPacket(buff, buff.length);
		System.out.println("Waiting for packet!");
		socket.receive(pk);
		System.out.println("Packet Received! + " + Arrays.toString(pk.getData()));
		DNS dns = DNS.deserialize(pk.getData(), pk.getLength());
		System.out.println("DNS info: " + dns);
		if (dns.getOpcode() == DNS.OPCODE_STANDARD_QUERY) {
            System.out.println("Opcode is 0");
            handleQuestions(dns);
        }
	}

	/**
	 * Handles all questions within a DNS packet
	 * @param dns
	 */
	private static void handleQuestions(DNS dns) {
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
                    System.out.println("Received question invalid type");
                    return;
            }
        }
	}
}
