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
    _sendBuff = {}
    _SEQ_NUM_LOCK = None
    _BUFF_LOCK = None
    _oldestPack = 0
    _lastAckRecv = -1
    _lastFrameSent = -1
    _timers = {}

    def __init__(self, remote_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(remote_address=remote_address,
                loss_probability=loss_probability)

        _timers = {}
        # A variety of locks and semaphores for maintaining window size, and
        # packet ordering
        self._WINDOW_LOCK = threading.BoundedSemaphore(value=self._SEND_WINDOW_SIZE)
        self._BUFF_LOCK = threading.Semaphore()
        self._SEQ_NUM_LOCK = threading.Semaphore()
        self._TIMER_LOCK = threading.Semaphore()
        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()

        # Create the actual window
        #self._sendBuff = [None] * self._SEND_WINDOW_SIZE


    def send(self, data):
        for i in range(0, len(data), SWPPacket.MAX_DATA_SIZE):
            self._send(data[i:i+SWPPacket.MAX_DATA_SIZE])
        
    def _send(self, data):
        # TODO - Should be DONE
        self._WINDOW_LOCK.acquire()
        with self._SEQ_NUM_LOCK:
            packet = SWPPacket(type=SWPType.DATA, seq_num=(self._seqNum), data=data)
            timer = threading.Timer(self._TIMEOUT, self._retransmit, args=[self._seqNum])
            self._enqueue(self._seqNum, packet)
            #with self._TIMER_LOCK:
            SWPSender._timers[self._seqNum] = timer
                
            logging.debug("Sending: %s" % packet)
            timer.start()
            self._llp_endpoint.send(packet.to_bytes())
            self._lastFrameSent = self._seqNum
            self._seqNum += 1
        return

    def _enqueue(self, seqNum, data):
        with self._BUFF_LOCK:
            self._sendBuff[seqNum] = data
#            logging.debug("Data enqueued, new window contents: %s" % self._sendBuff)

#    def _remove(self, seqNum):
#        with self._BUFF_LOCK:
#            logging.debug("SEQ NUM OF ACK! %s" % seqNum)
#            for i in range(self._lastAckRecv, seqNum + 1):
#                # Traverse the buffer and look for holes
#                self._sendBuff[seqNum] = None
#            logging.debug("Data dequeued, New window contents: %s" % self._sendBuff)

    def _get(self, seqNum):
        data = None
        with self._BUFF_LOCK:
            data = self._sendBuff[seqNum]
        return data

    def _retransmit(self, seq_num):
        # TODO - Should be DONE
        with self._SEQ_NUM_LOCK:
            packet = self._get(seq_num)
            timer = SWPSender._timers.get(seq_num, None)
            if timer is None:
                return
            if packet == None:
                return # Means that we shouldn't actually retransmit
            #with self._TIMER_LOCK:
                #logging.debug("TIMERS %s" % SWPSender._timers)
            timer = threading.Timer(self._TIMEOUT, self._retransmit, args=[seq_num])
            timer.start()
            SWPSender._timers[seq_num] = timer
            self._llp_endpoint.send(packet.to_bytes())
        return

    def _recv(self):
        while True:
            # Receive SWP packet
            raw = self._llp_endpoint.recv()
            if raw is None:
                continue
            packet = SWPPacket.from_bytes(raw)
            logging.debug("SWPSender Received: %s" % packet)
            # We only care about ACKs on this guy
            if packet.type != SWPType.ACK: 
                continue
            with self._SEQ_NUM_LOCK:
           # with self._TIMER_LOCK:
                i = 0
                while i <= packet.seq_num:
                    timer = SWPSender._timers.get(i, None)
                    if timer is not None:
                       # logging.debug("CANCELLING TIMER %s" % i)
                        SWPSender._timers[i].cancel() 
                        del SWPSender._timers[i]
                    i += 1
           # self._remove(packet.seq_num)
           # if self._lastAckRecv > packet.seq_num:
           #     self._lastAckRecv = packet.seq_num
            # TODO - FIXME, need to cancel a timer, also need to know which byte
            # to resend
                try:
                    self._WINDOW_LOCK.release()
                except ValueError:
                    logging.error("Window is oversized")
        return

class SWPReceiver:
    _RECV_WINDOW_SIZE = 5
    _maxSeqNum = 0
    _lastFrameRecvd = -1
    _largestAcptFrame = 4
    _recvBuff = {}
    #_RECV_LOCK = None

    def __init__(self, local_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(local_address=local_address, 
                loss_probability=loss_probability)

        # Received data waiting for application to consume
        self._ready_data = queue.Queue()
        self._SEQ_LOCK = threading.Semaphore()
        self._RECV_LOCK = threading.Semaphore()
        self._RECV_BUFF_LOCK = threading.Semaphore()
        self._WINDOW_LOCK = threading.Semaphore(value=self._RECV_WINDOW_SIZE)
        # Start receive thread
    
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()
        
        # TODO: Add additional state variables
        

    def recv(self):
        return self._ready_data.get()

    def _recv(self):
        while True:
            # Receive data packet
            raw = self._llp_endpoint.recv()
            packet = SWPPacket.from_bytes(raw)
            logging.debug("SWPReceiver Received: %s" % packet)
        
        # Outside our window
            if packet.seq_num > self._largestAcptFrame:
                continue
                
          
            with self._SEQ_LOCK:
                if packet.seq_num <= self._lastFrameRecvd:
                    self._sendAck()
                    continue
            
                self._add(packet)
                seqNum = self._computeMaxSeqNum(packet.seq_num)
        # Ack for most recent packet
                self._sendAck()
                self._lastFrameRecvd = seqNum
                self._largestAcptFrame = self._lastFrameRecvd + self._RECV_WINDOW_SIZE

        return
    
    def _sendAck(self):
        logging.error("MAX SEQ: %s" % self._maxSeqNum)
        if self._maxSeqNum != -1:
            ackPack = SWPPacket(type=SWPType.ACK, seq_num=int(self._maxSeqNum))
            self._llp_endpoint.send(ackPack.to_bytes())  

    def _add(self, data):
        with self._RECV_BUFF_LOCK:
            self._recvBuff[data.seq_num] = data

    def _computeMaxSeqNum(self, seqNum):
        with self._RECV_BUFF_LOCK:
            # Traverse the buffer and look for holes
            i = self._lastFrameRecvd + 1
            while i in self._recvBuff:
                if i > self._largestAcptFrame:
                    break
                self._maxSeqNum = i
                self._ready_data.put(self._recvBuff[i].data)
                i += 1
            # We need to ack the last complete packet before None
            #self._maxSeqNum = newMax 
        return self._maxSeqNum

