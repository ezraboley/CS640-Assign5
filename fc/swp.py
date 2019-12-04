import enum
import logging
import llp
import queue
import struct
import threading

class SWPType(enum.IntEnum):
    DATA = ord('D')
    ACK = ord('A')

class SWPPacket:
    _PACK_FORMAT = '!BI'
    _HEADER_SIZE = struct.calcsize(_PACK_FORMAT)
    MAX_DATA_SIZE = 1400 # Leaves plenty of space for IP + UDP + SWP header 

    def __init__(self, type, seq_num, data=b''):
        self._type = type
        self._seq_num = seq_num
        self._data = data

    @property
    def type(self):
        return self._type

    @property
    def seq_num(self):
        return self._seq_num
    
    @property
    def data(self):
        return self._data

    def to_bytes(self):
        header = struct.pack(SWPPacket._PACK_FORMAT, self._type.value, 
                self._seq_num)
        return header + self._data
       
    @classmethod
    def from_bytes(cls, raw):
        header = struct.unpack(SWPPacket._PACK_FORMAT,
                raw[:SWPPacket._HEADER_SIZE])
        type = SWPType(header[0])
        seq_num = header[1]
        data = raw[SWPPacket._HEADER_SIZE:]
        return SWPPacket(type, seq_num, data)

    def __str__(self):
        return "%s %d %s" % (self._type.name, self._seq_num, repr(self._data))

class SWPSender:
    _SEND_WINDOW_SIZE = 5
    _TIMEOUT = 1
    _WINDOW_LOCK = None
    _seqNum = 0
    _sendBuff = []
    _SEQ_NUM_LOCK = None
    _BUFF_LOCK = None

    def __init__(self, remote_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(remote_address=remote_address,
                loss_probability=loss_probability)

        # A variety of locks and semaphores for maintaining window size, and
        # packet ordering
        self._WINDOW_LOCK = threading.BoundedSemaphore(value=self._SEND_WINDOW_SIZE)
        self._BUFF_LOCK = threading.Semaphore()
        self._SEQ_NUM_LOCK = threading.Semaphore()
        
        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()

        # Create the actual window
        self._sendBuff = [None] * self._SEND_WINDOW_SIZE

    def send(self, data):
        for i in range(0, len(data), SWPPacket.MAX_DATA_SIZE):
            self._send(data[i:i+SWPPacket.MAX_DATA_SIZE])

    def _send(self, data):
        # TODO - Should be DONE
        self._WINDOW_LOCK.acquire()
        with self._SEQ_NUM_LOCK:
            packet = SWPPacket(type=SWPType.DATA, seq_num=self._seqNum, data=data)
            timer = threading.Timer(self._TIMEOUT, self._retransmit, args=[self._seqNum])
            self._enqueue(self._seqNum, packet)
            logging.debug("Sending: %s" % packet)
            self._llp_endpoint.send(packet.to_bytes())
            self._seqNum += 1
            timer.start()
        return

    def _enqueue(self, seqNum, data):
        with self._BUFF_LOCK:
            self._sendBuff[seqNum % self._SEND_WINDOW_SIZE] = data
            logging.debug("Data enqueued, new window contents: %s" % self._sendBuff)

    def _remove(self, seqNum):
        data = None
        with self._BUFF_LOCK:
            data = self._sendBuff[seqNum % self._SEND_WINDOW_SIZE]
            self._sendBuff[seqNum % self._SEND_WINDOW_SIZE] = None  # FIXME Shouldnt be deleting!
            logging.debug("Data dequeued, New window contents: %s" % self._sendBuff)
        return data

    def _get(self, seqNum):
        data = None
        with self._BUFF_LOCK:
            data = self._sendBuff[seqNum % self._SEND_WINDOW_SIZE]
        return data

    def _retransmit(self, seq_num):
        # TODO - Should be DONE
        packet = self._get(seq_num)
        if packet == None or packet.seq_num != seq_num:
            return # Means that we shouldn't actually retransmit

        timer = threading.Timer(self._TIMEOUT, self._retransmit, args=[seq_num])
        self._llp_endpoint.send(packet.to_bytes())
        timer.start()
        return 

    def _recv(self):
        while True:
            # Receive SWP packet
            raw = self._llp_endpoint.recv()
            if raw is None:
                continue
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)
            # We only care about ACKs on this guy
            if packet.type != SWPType.ACK: 
                continue
            # TODO - FIXME, need to cancel a timer, also need to know which byte
            # to resend
            self._remove(packet.seq_num)
            try:
                self._WINDOW_LOCK.release()
            except ValueError:
                logging.debug("Window is oversized")
        return

class SWPReceiver:
    _RECV_WINDOW_SIZE = 5
    _maxSeqNum = 0
    _MAX_SEQ_LOCK = None
    #_RECV_LOCK = None

    def __init__(self, local_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(local_address=local_address, 
                loss_probability=loss_probability)

        # Received data waiting for application to consume
        self._ready_data = queue.Queue()

        self._MAX_SEQ_LOCK = threading.Semaphore()
        self._RECV_LOCK = threading.Semaphore()
        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()
        
        # TODO: Add additional state variables
        

    def recv(self):
        return self._ready_data.get()

    def _recv(self):
        while True:
            # Receive data packet
            with self._RECV_LOCK:
                raw = self._llp_endpoint.recv()
                packet = SWPPacket.from_bytes(raw)
                logging.debug("Received: %s" % packet)
            
                self._setMaxSeqNum(packet.seq_num)
                seqNum = self._getMaxSeqNum()
                ackPack = SWPPacket(type=SWPType.ACK, seq_num=seqNum, data=raw)
                self._llp_endpoint.send(ackPack.to_bytes())  
            # TODO

        return

    def _getMaxSeqNum(self):
        seqNum = -1
        with self._MAX_SEQ_LOCK:
            seqNum = self._maxSeqNum
        return seqNum
    
    def _setMaxSeqNum(self, seqNum):
        with self._MAX_SEQ_LOCK:
            self._maxSeqNum = max(self._maxSeqNum, seqNum)
        

