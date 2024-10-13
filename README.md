GUDP transfers UDP datagrams with guarantees on delivery and ordering. This is an extension of regular UDP, which does not guarantee the delivery or ordering of datagrams. 

To guarantee delivery and ordering, GUDP adds the following mechanisms to UDP:
1)Sliding window flow control
2)Detection and ARQ-based retransmission of lost or reordered packets

Since GUDP provides a unidirectional communication service, there is no data flow in the opposite direction onto which control information can be "piggybacked". Instead, GUDP uses separate control packets (DATA, BSN, ACK, and FIN) to guarantee delivery.
GUDP provides guaranteed communication from a sender UDP endpoint to a receiver UDP endpoint. A UDP endpoint (sender or receiver) is uniquely defined by an IP address and a UDP port number.  
